#!/usr/bin/env python3
"""Download ONLY plan/drawing PDFs from TxDOT's public Facilities archive.

Requires Python 3.9+; uses only the Python standard library.

Example:
    python txdot_facilities_plans.py --dry-run --years 2025 2026
    python txdot_facilities_plans.py --accept-license --years 2025 2026
    python txdot_facilities_plans.py --accept-license --output ~/TxDOT-Plans
    python txdot_facilities_plans.py --dry-run --ca-bundle /path/to/cacert.pem
    python txdot_facilities_plans.py --dry-run --insecure  # last resort

Review TxDOT's license first:
https://www.dot.state.tx.us/business/plansonline/agreement.htm
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from html.parser import HTMLParser
import os
from pathlib import Path
import re
import socket
import ssl
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import unquote, urljoin, urlsplit
from urllib.request import Request, urlopen

DEFAULT_ROOT = "https://ftp.txdot.gov/plans/Facilities/"
LICENSE = "https://www.dot.state.tx.us/business/plansonline/agreement.htm"
USER_AGENT = "TxDOT-Facilities-Plan-Downloader/1.0 (public archive PDF downloader)"
PDF_NAME = re.compile(r"(?i)(?:^|[^a-z])(?:plans?|drawings?|blueprints?)(?:$|[^a-z])")
EXCLUDE_NAME = re.compile(
    r"(?i)(?:spec(?:ification)?s?|proposal|conditions|ethics|"
    r"instructions|addend(?:um|a)|bid\s*tab|solicitation|"
    r"request\s*for\s*bid|geotechnical|report)"
)
EXCLUDE_DIR = re.compile(r"(?i)(?:^|[\s_-])addend(?:um|a)(?:$|[\s_-])")
CONTENT_RANGE = re.compile(r"bytes\s+(\d+)-(\d+)/(\d+|\*)", re.I)
RETRYABLE_HTTP = {408, 425, 429, 500, 502, 503, 504}
TLS_HELP = ("Update your system's trusted CA certificates, use --ca-bundle PATH "
            "with a trusted CA file, or use --insecure as a last resort "
            "(disables HTTPS certificate validation)")


def tls_verify_failed(exc: Exception) -> bool:
    """urllib wraps certificate failures in URLError on many platforms."""
    inner = exc.reason if isinstance(exc, URLError) else exc
    return isinstance(inner, ssl.SSLCertVerificationError)


class Anchors(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.hrefs: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() == "a":
            for key, value in attrs:
                if key.lower() == "href" and value:
                    self.hrefs.append(value)
                    break


@dataclass(frozen=True)
class Job:
    url: str
    relative: Path


class Downloader:
    def __init__(self, root: str, timeout: int, retries: int, years: set[str],
                 include_addenda: bool = False, pause: float = 0.1,
                 ca_bundle: Path | None = None, insecure: bool = False) -> None:
        root = root.rstrip("/") + "/"
        parsed = urlsplit(root)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            raise ValueError("Root URL must be an absolute http(s) URL")
        self.root = root
        self.scheme = parsed.scheme
        self.host = parsed.netloc.lower()
        self.root_parts = self._parts(parsed.path)
        self.timeout = timeout
        self.retries = retries
        self.years = years
        self.include_addenda = include_addenda
        self.pause = pause
        self.errors: list[str] = []
        # Use verified HTTPS by default. The override is opt-in; never silently
        # downgrade TLS security when a server or local CA configuration fails.
        self.tls_context = None
        if parsed.scheme == "https":
            self.tls_context = ssl.create_default_context(
                cafile=str(ca_bundle) if ca_bundle else None
            )
            if insecure:
                self.tls_context.check_hostname = False
                self.tls_context.verify_mode = ssl.CERT_NONE

    @staticmethod
    def _parts(path: str) -> tuple[str, ...]:
        parts = []
        for raw in path.split("/"):
            if not raw:
                continue
            part = unquote(raw)
            if part in (".", "..") or any(ch in part for ch in ("/", "\\", "\0")):
                raise ValueError("Unsafe URL path segment")
            parts.append(part)
        return tuple(parts)

    def relative_parts(self, url: str) -> tuple[str, ...] | None:
        p = urlsplit(url)
        if p.scheme != self.scheme or p.netloc.lower() != self.host:
            return None
        try:
            parts = self._parts(p.path)
        except ValueError:
            return None
        if parts[:len(self.root_parts)] != self.root_parts:
            return None
        return parts[len(self.root_parts):]

    def _open(self, url: str, headers: dict[str, str] | None = None):
        request = Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
        return urlopen(request, timeout=self.timeout, context=self.tls_context)

    def _listing(self, url: str) -> list[str]:
        for attempt in range(self.retries + 1):
            try:
                with self._open(url) as response:
                    if "html" not in (response.headers.get("Content-Type") or "").lower():
                        raise ValueError(f"Directory did not return HTML: {url}")
                    # Directory listings should not be enormous.
                    html = response.read(8_000_001)
                    if len(html) > 8_000_000:
                        raise ValueError(f"Directory listing is unexpectedly large: {url}")
                    parser = Anchors()
                    parser.feed(html.decode("utf-8", errors="replace"))
                    return parser.hrefs
            except (HTTPError, URLError, OSError, ValueError, TimeoutError) as exc:
                if tls_verify_failed(exc):
                    raise RuntimeError(f"Cannot list {url}: {exc}. {TLS_HELP}") from exc
                retry = attempt < self.retries and (
                    not isinstance(exc, HTTPError) or exc.code in RETRYABLE_HTTP
                )
                if not retry:
                    raise RuntimeError(f"Cannot list {url}: {exc}") from exc
                time.sleep(min(2 ** attempt, 8))
        raise AssertionError("unreachable")

    def find_jobs(self) -> list[Job]:
        pending = [self.root]
        visited: set[str] = set()
        found: dict[str, Job] = {}
        while pending:
            current = pending.pop(0)
            if current in visited:
                continue
            visited.add(current)
            try:
                links = self._listing(current)
            except RuntimeError as exc:
                self.errors.append(str(exc))
                print(f"[SCAN ERROR] {exc}", file=sys.stderr, flush=True)
                continue
            for href in links:
                if href.startswith(("#", "?")):
                    continue
                target = urljoin(current, href).split("#", 1)[0].split("?", 1)[0]
                rel = self.relative_parts(target)
                if not rel:  # Parent Directory, cross-host, outside-root, etc.
                    continue
                if self.years and rel[0] not in self.years:
                    continue
                if not self.include_addenda and any(EXCLUDE_DIR.search(segment) for segment in rel[:-1]):
                    continue
                is_dir = urlsplit(target).path.endswith("/")
                if is_dir:
                    if target not in visited and target not in pending:
                        pending.append(target)
                else:
                    name = rel[-1]
                    stem, ext = os.path.splitext(name)
                    if ext.lower() != ".pdf":
                        continue
                    if not PDF_NAME.search(stem) or EXCLUDE_NAME.search(stem):
                        continue
                    # Keep the complete archive hierarchy to avoid duplicate names.
                    dest = Path(*(safe_component(s) for s in rel))
                    found[target] = Job(url=target, relative=dest)
            if self.pause:
                time.sleep(self.pause)
        return sorted(found.values(), key=lambda job: str(job.relative).lower())

    def download(self, job: Job, output: Path) -> tuple[str, Job, str]:
        dest = output / job.relative
        if dest.exists():
            if is_valid_pdf(dest):
                return ("SKIP", job, "already downloaded")
            # Preserve any unexpectedly invalid existing file; never delete user data.
            return ("ERROR", job, f"Existing file is not a valid complete PDF: {dest}")
        try:
            dest.parent.mkdir(parents=True, exist_ok=True)
            part = dest.with_name(dest.name + ".part")
            last_error = None
            for attempt in range(self.retries + 1):
                offset = part.stat().st_size if part.exists() else 0
                headers = {"Range": f"bytes={offset}-"} if offset else {}
                try:
                    with self._open(job.url, headers=headers) as response:
                        status = response.status
                        content_type = (response.headers.get("Content-Type") or "").lower()
                        if "text/html" in content_type:
                            raise ValueError("Server returned HTML instead of PDF")
                        total = None
                        if status == 206:
                            match = CONTENT_RANGE.fullmatch(response.headers.get("Content-Range", ""))
                            if not match or int(match.group(1)) != offset:
                                raise ValueError("Unsafe or malformed partial-download response")
                            if match.group(3) != "*":
                                total = int(match.group(3))
                            mode = "ab"
                        elif status == 200:
                            # Server ignored Range request; restart from zero safely.
                            mode = "wb"
                            size_header = response.headers.get("Content-Length")
                            if size_header and size_header.isdigit():
                                total = int(size_header)
                        else:
                            raise ValueError(f"Unexpected response: HTTP {status}")
                        with part.open(mode) as outfile:
                            while True:
                                block = response.read(1024 * 1024)
                                if not block:
                                    break
                                outfile.write(block)
                        size = part.stat().st_size
                        if total is not None and size != total:
                            raise IOError(f"Incomplete download: {size}/{total} bytes")
                        if not is_valid_pdf(part):
                            raise IOError("PDF missing its header or end marker")
                        part.replace(dest)
                        return ("OK", job, f"{size / 1024**2:.1f} MiB")
                except HTTPError as exc:
                    if exc.code == 416 and offset and is_valid_pdf(part):
                        size_range = exc.headers.get("Content-Range", "")
                        if size_range == f"bytes */{offset}":
                            part.replace(dest)
                            return ("OK", job, "resumed file was already complete")
                    if exc.code not in RETRYABLE_HTTP:
                        return ("ERROR", job, f"HTTP {exc.code}")
                    last_error = str(exc)
                except (URLError, socket.timeout, TimeoutError, OSError, ValueError) as exc:
                    if tls_verify_failed(exc):
                        return ("ERROR", job, f"{exc}. {TLS_HELP}")
                    last_error = str(exc)
                if attempt < self.retries:
                    time.sleep(min(2 ** attempt, 8))
            return ("ERROR", job, f"{last_error}; partial download kept for resume")
        except OSError as exc:
            return ("ERROR", job, str(exc))


def safe_component(name: str) -> str:
    """Keep directory names useful, but safe on Windows/macOS/Linux."""
    value = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .")
    if not value:
        value = "unnamed"
    if value.split(".")[0].upper() in {
        "CON", "PRN", "AUX", "NUL", *{f"COM{i}" for i in range(1, 10)},
        *{f"LPT{i}" for i in range(1, 10)},
    }:
        value = "_" + value
    if len(value) > 150:
        import hashlib
        digest = hashlib.sha1(name.encode("utf-8")).hexdigest()[:10]
        stem, extension = os.path.splitext(value)
        value = stem[:130] + "_" + digest + extension[:10]
    return value


def is_valid_pdf(path: Path) -> bool:
    try:
        if not path.is_file() or path.stat().st_size < 20:
            return False
        with path.open("rb") as f:
            if b"%PDF-" not in f.read(1024):
                return False
            f.seek(max(0, path.stat().st_size - 8192))
            return b"%%EOF" in f.read()
    except OSError:
        return False


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Download only construction-plan PDFs from TxDOT's Facilities archive."
    )
    parser.add_argument("--output", type=Path, default=Path("TxDOT-Facilities-Plans"),
                        help="Download folder (default: ./TxDOT-Facilities-Plans)")
    parser.add_argument("--years", nargs="+", metavar="YYYY", default=[],
                        help="Only these years (default: all available years)")
    parser.add_argument("--dry-run", action="store_true", help="Show matched files without downloading")
    parser.add_argument("--accept-license", action="store_true",
                        help=f"Confirm you've reviewed the TxDOT terms: {LICENSE}")
    parser.add_argument("--include-addenda", action="store_true",
                        help="Also search addendum directories for plan/drawing PDFs")
    tls = parser.add_mutually_exclusive_group()
    tls.add_argument("--ca-bundle", type=Path,
                     help="Trusted PEM CA bundle for HTTPS certificate verification")
    tls.add_argument("--insecure", action="store_true",
                     help="Skip HTTPS certificate verification (unsafe; last resort only)")
    parser.add_argument("--workers", type=int, default=2,
                        help="Concurrent downloads, 1–4 (default: 2)")
    parser.add_argument("--limit", type=int, default=0,
                        help="Maximum number of new files per run (0 = unlimited)")
    parser.add_argument("--timeout", type=int, default=45, help="Connection/read timeout, seconds")
    parser.add_argument("--retries", type=int, default=3, help="Retries for connection/download errors")
    parser.add_argument("--root", default=DEFAULT_ROOT,
                        help=argparse.SUPPRESS)  # Test override; default intentionally Facilities only.
    args = parser.parse_args(argv)
    if any(not re.fullmatch(r"20\d{2}", year) for year in args.years):
        parser.error("--years must contain four-digit years (e.g. 2025 2026)")
    if not 1 <= args.workers <= 4:
        parser.error("--workers must be between 1 and 4")
    if args.limit < 0 or args.retries < 0 or args.timeout < 1:
        parser.error("--limit, --retries, and --timeout must be nonnegative (timeout at least 1)")
    if not args.dry_run and not args.accept_license:
        parser.error(f"Review {LICENSE} and rerun with --accept-license")
    if args.ca_bundle and not args.ca_bundle.is_file():
        parser.error(f"CA bundle not found: {args.ca_bundle}")
    if args.insecure:
        print("[TLS WARNING] HTTPS certificate verification is DISABLED. "
              "Only use this on a network and source you trust.", file=sys.stderr)

    try:
        crawler = Downloader(args.root, args.timeout, args.retries,
                             set(args.years), args.include_addenda,
                             ca_bundle=args.ca_bundle, insecure=args.insecure)
    except (OSError, ssl.SSLError) as exc:
        parser.error(f"Unable to load CA certificates: {exc}")
    print(f"Scanning: {crawler.root}", flush=True)
    jobs = crawler.find_jobs()
    if not jobs:
        print("No matching plan/drawing PDFs found.")
        return 2 if crawler.errors else 0
    # A rare collision from sanitized remote filenames must never overwrite another file.
    destinations: dict[Path, str] = {}
    collisions = []
    for job in jobs:
        if job.relative in destinations and destinations[job.relative] != job.url:
            collisions.append(str(job.relative))
        destinations[job.relative] = job.url
    if collisions:
        print("Destination filename collision(s), refusing unsafe download:", collisions,
              file=sys.stderr)
        return 2
    print(f"Found {len(jobs)} plan/drawing PDFs; listing errors: {len(crawler.errors)}", flush=True)
    if args.dry_run:
        for job in jobs:
            print(f"{job.relative}  {job.url}")
        return 2 if crawler.errors else 0

    new_jobs = [job for job in jobs if not is_valid_pdf(args.output / job.relative)]
    if args.limit:
        new_jobs = new_jobs[:args.limit]
    print(f"Already valid: {len(jobs) - len([j for j in jobs if not is_valid_pdf(args.output / j.relative)])}; "
          f"scheduled: {len(new_jobs)}", flush=True)
    completed = errors = 0
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        tasks = {pool.submit(crawler.download, job, args.output): job for job in new_jobs}
        try:
            for future in as_completed(tasks):
                status, job, note = future.result()
                print(f"[{status}] {job.relative} — {note}", flush=True)
                if status == "OK":
                    completed += 1
                elif status == "ERROR":
                    errors += 1
        except KeyboardInterrupt:
            print("Interrupted. Incomplete .part files will resume on the next run.",
                  file=sys.stderr)
            return 130
    print(f"Finished: downloaded {completed}, failed {errors}, scan errors {len(crawler.errors)}")
    return 2 if errors or crawler.errors else 0


if __name__ == "__main__":
    raise SystemExit(main())

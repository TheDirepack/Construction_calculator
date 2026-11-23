"""Local browser editor for blueprint training-label boxes.

Run from ml/symbol-detector:
  python -m annotator.web --pdf plans.pdf --out-dir labels/ --open-browser
  python -m annotator.web --edit labels/page.json --open-browser
  python -m annotator.web --pdf plans.pdf --proposals scan_SAM3.json

All coordinates are original rendered-page pixels, top-left origin. Pages
remain positive-only and non-exhaustive by default. SAM proposals are
unverified until individually accepted; the UI never attests full coverage.
No cloud requests, framework dependency, or remote listen address.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import secrets
import tempfile
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

import fitz

from annotator.model import FAMILY_DISCIPLINES, Sheet


MAX_BODY_BYTES = 4 * 1024 * 1024
MAX_PDF_PIXELS = 30_000_000
ID_PATTERN = re.compile(r"^[a-zA-Z0-9_.:-]{1,120}$")
MODULE_DIR = Path(__file__).resolve().parent


class LabelingError(ValueError):
    pass


class RevisionConflict(LabelingError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(part)
    return digest.hexdigest()


def _atomic_bytes(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(prefix="." + path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as dst:
            dst.write(data)
            dst.flush()
            os.fsync(dst.fileno())
        os.replace(temp, path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def _valid_box(box: object, width: int, height: int) -> None:
    if not isinstance(box, list) or len(box) != 4:
        raise LabelingError("bbox_xyxy must contain four coordinates")
    if any(type(v) not in (int, float) or not math.isfinite(v) for v in box):
        raise LabelingError("bbox_xyxy coordinates must be finite numbers")
    x0, y0, x1, y1 = box
    if not (0 <= x0 < x1 <= width and 0 <= y0 < y1 <= height):
        raise LabelingError("bbox_xyxy must have positive area within the rendered image")


def validate_instances(instances: object, width: int, height: int) -> list[dict]:
    if not isinstance(instances, list) or len(instances) > 150_000:
        raise LabelingError("instances must be a bounded list")
    seen: set[str] = set()
    for item in instances:
        if not isinstance(item, dict):
            raise LabelingError("each instance must be an object")
        ident = item.get("instance_id")
        if not isinstance(ident, str) or not ID_PATTERN.fullmatch(ident) or ident in seen:
            raise LabelingError("instance IDs must be unique, nonempty, and safe")
        seen.add(ident)
        family = item.get("symbol_family")
        variant = item.get("symbol_variant", "unspecified")
        if (not isinstance(family, str) or not family.strip() or len(family) > 100 or
                not isinstance(variant, str) or not variant.strip() or len(variant) > 160):
            raise LabelingError("each instance needs a symbol family and variant")
        _valid_box(item.get("bbox_xyxy"), width, height)
        rotated = item.get("bbox_rotated")
        if rotated is not None:
            if not isinstance(rotated, dict):
                raise LabelingError("bbox_rotated must be an object")
            values = [rotated.get(k) for k in ("cx", "cy", "w", "h", "angle_deg")]
            if any(type(v) not in (int, float) or not math.isfinite(v) for v in values):
                raise LabelingError("bbox_rotated must contain finite numbers")
            if values[2] <= 0 or values[3] <= 0 or not -360 <= values[4] <= 360:
                raise LabelingError("invalid rotated dimensions or angle")
        if type(item.get("uncertain", False)) is not bool:
            raise LabelingError("uncertain must be boolean")
        status = item.get("review_status")
        if status is not None and status not in ("unreviewed", "accepted"):
            raise LabelingError("rejected proposals must be removed from active instances")
        if status == "accepted":
            if (item.get("review_source") != "human" or
                    item.get("verified_ground_truth") is not True or
                    item.get("uncertain") is True):
                raise LabelingError("accepted annotations require explicit human verification")
        if item.get("verified_ground_truth") is True and item.get("uncertain") is True:
            raise LabelingError("a verified annotation cannot remain uncertain")
    return instances


class LabelingSession:
    def __init__(
        self, *, pdf: Path | None = None, edit: Path | None = None,
        out_dir: Path | None = None, page: int = 0, dpi: int = 150,
        proposals: Path | None = None,
    ) -> None:
        if (pdf is None) == (edit is None):
            raise LabelingError("specify exactly one of --pdf or --edit")
        if not 30 <= dpi <= 600:
            raise LabelingError("DPI must be between 30 and 600")
        self.pdf = Path(pdf).resolve() if pdf else None
        self.edit = Path(edit).resolve() if edit else None
        if self.pdf and not self.pdf.is_file():
            raise FileNotFoundError(self.pdf)
        if self.edit and not self.edit.is_file():
            raise FileNotFoundError(self.edit)
        self.dpi = dpi
        self.lock = threading.RLock()
        self.token = secrets.token_urlsafe(32)
        self._render_cache: dict[int, tuple[Path, int, int, str]] = {}
        self.source_hash = sha256_file(self.pdf) if self.pdf else None
        self.source_stat = self.pdf.stat() if self.pdf else None
        if self.pdf:
            with fitz.open(self.pdf) as doc:
                self.page_count = len(doc)
            if self.page_count < 1:
                raise LabelingError("PDF has no pages")
        else:
            self.page_count = 1
        self.start_page = page
        if page < 0 or page >= self.page_count:
            raise LabelingError("initial page outside source document")
        name = re.sub(r"[^a-zA-Z0-9_-]+", "_", self.pdf.stem)[:48] if self.pdf else ""
        self.out_dir = (Path(out_dir).resolve() if out_dir else
                        Path.cwd() / "annotations" / f"{name}_{self.source_hash[:12]}" if self.pdf else
                        self.edit.parent)
        self.out_dir.mkdir(parents=True, exist_ok=True)
        self.proposals = None
        if proposals is not None:
            if not self.pdf:
                raise LabelingError("--proposals requires --pdf")
            self.proposals = json.loads(Path(proposals).read_text())
            if not isinstance(self.proposals, dict) or not isinstance(self.proposals.get("devices"), list):
                raise LabelingError("proposal file lacks a devices list")
            if (self.proposals.get("source_pdf_sha256") and
                    self.proposals["source_pdf_sha256"] != self.source_hash):
                raise LabelingError("proposal source PDF SHA256 differs from selected drawing")
            if (not self.proposals.get("source_pdf_sha256") and
                    self.proposals.get("source_file") != self.pdf.name):
                raise LabelingError("unhashed proposals must match the PDF filename")

    def _assert_pdf_unchanged(self) -> None:
        if self.pdf:
            current = self.pdf.stat()
            if (current.st_size != self.source_stat.st_size or
                    current.st_mtime_ns != self.source_stat.st_mtime_ns):
                raise RevisionConflict("source PDF changed during labeling; reopen the session")

    def _page_index(self, index: int) -> int:
        if not isinstance(index, int) or not 0 <= index < self.page_count:
            raise LabelingError("page index out of range")
        return index

    def _json_path(self, index: int) -> Path:
        if self.edit:
            return self.edit
        return self.out_dir / f"{self.pdf.stem[:48]}_p{index + 1:04d}.json"

    def _raster(self, index: int) -> tuple[Path, int, int, str]:
        self._assert_pdf_unchanged()
        if index in self._render_cache:
            cached = self._render_cache[index]
            if cached[0].is_file():
                return cached
            self._render_cache.pop(index)
        if self.edit:
            data = json.loads(self.edit.read_text())
            image = (self.edit.parent / str(data["image_path"])).resolve()
            if not image.is_file():
                raise LabelingError(f"annotation image unavailable: {image}")
            result = image, int(data["image_width"]), int(data["image_height"]), sha256_file(image)
            self._render_cache[index] = result
            return result
        with fitz.open(self.pdf) as doc:
            sheet = doc[index]
            factor = self.dpi / 72.0
            if sheet.rect.width * factor * sheet.rect.height * factor > MAX_PDF_PIXELS:
                factor = math.sqrt(MAX_PDF_PIXELS / (sheet.rect.width * sheet.rect.height))
            pix = sheet.get_pixmap(matrix=fitz.Matrix(factor, factor), alpha=False)
            width, height = pix.width, pix.height
            path = self.out_dir / "images" / f"page_{index + 1:04d}_{self.source_hash[:12]}_{width}x{height}.png"
            if not path.is_file():
                _atomic_bytes(path, pix.tobytes("png"))
        result = path, width, height, sha256_file(path)
        self._render_cache[index] = result
        return result

    def _proposal_instances(self, index: int, width: int, height: int) -> list[dict]:
        if self.proposals is None:
            return []
        with fitz.open(self.pdf) as doc:
            page = doc[index]
            pw, ph = float(page.rect.width), float(page.rect.height)
        found = []
        for number, proposal in enumerate(self.proposals["devices"]):
            if proposal.get("pdf_page") != index + 1:
                continue
            pts = proposal.get("symbol_bbox")
            if not isinstance(pts, list) or len(pts) != 4:
                continue
            try:
                x0, y0, x1, y1 = map(float, pts)
                box = [
                    max(0., min(float(width), x0 * width / pw)),
                    max(0., min(float(height), (ph - y1) * height / ph)),
                    max(0., min(float(width), x1 * width / pw)),
                    max(0., min(float(height), (ph - y0) * height / ph)),
                ]
                _valid_box(box, width, height)
            except (ValueError, TypeError, ZeroDivisionError):
                continue
            found.append({
                "instance_id": f"proposal_p{index + 1}_{number}",
                "symbol_family": str(proposal.get("device_type") or "unknown_symbol"),
                "symbol_variant": str(proposal.get("subtype") or "unspecified"),
                "bbox_xyxy": [round(v, 3) for v in box],
                "uncertain": True,
                "review_status": "unreviewed",
                "review_source": "sam3",
                "verified_ground_truth": False,
                "proposal_score": proposal.get("score"),
                "proposal_visual_group": proposal.get("visual_group"),
                "proposal_neca_symbol_id": proposal.get("neca_symbol_id"),
                "proposal_alternative_labels": proposal.get("alternative_labels", []),
            })
        return found

    def _new_sheet(self, index: int, image: Path, width: int, height: int, image_hash: str) -> dict:
        page_path = self._json_path(index)
        if self.pdf:
            image_rel = str(image.relative_to(page_path.parent))
            instances = self._proposal_instances(index, width, height)
            result = {
                "format_version": 1, "source_id": self.pdf.stem,
                "source_license": "user-owned",
                "project_family_id": self.pdf.stem,
                "document_id": self.pdf.name,
                "page_index": index,
                "source_type": "real_construction_pdf_manual",
                "source_pdf_sha256": self.source_hash,
                "image_path": image_rel,
                "image_sha256": image_hash,
                "image_width": width, "image_height": height,
                "coordinate_system": "original_page_pixels_top_left_y_down",
                "exhaustive_families": [],
                "annotation_completeness": "partial",
                "annotation_completeness_verified": False,
                "annotation_scope": "positive_only",
                "instances": instances,
                "rejected_proposals": [],
            }
            return result
        return json.loads(self.edit.read_text())

    def _load(self, index: int) -> tuple[dict, str | None, Path]:
        self._page_index(index)
        image, width, height, image_hash = self._raster(index)
        path = self._json_path(index)
        if path.is_file():
            raw = path.read_bytes()
            data = json.loads(raw)
            revision = hashlib.sha256(raw).hexdigest()
            if self.pdf and data.get("source_pdf_sha256") != self.source_hash:
                raise RevisionConflict("annotation belongs to another PDF revision")
            if int(data.get("image_width", -1)) != width or int(data.get("image_height", -1)) != height:
                raise RevisionConflict("annotation dimensions differ from rendered image")
            if data.get("image_sha256") not in (None, "", image_hash):
                raise RevisionConflict("annotation image bytes changed")
        else:
            data = self._new_sheet(index, image, width, height, image_hash)
            revision = None
        if data.get("coordinate_system") != "original_page_pixels_top_left_y_down":
            raise LabelingError("unsupported annotation coordinate system")
        return data, revision, image

    def page_state(self, index: int) -> dict:
        with self.lock:
            data, revision, _ = self._load(index)
            return {
                "page_index": index, "page_count": self.page_count,
                "revision": revision, "document": data,
                "image_url": f"/api/image?page={index}",
                "families": sorted(FAMILY_DISCIPLINES),
                "proposals_enabled": self.proposals is not None,
                "mode": "edit" if self.edit else "pdf",
            }

    def image_bytes(self, index: int) -> bytes:
        with self.lock:
            _, _, path = self._load(index)
            return path.read_bytes()

    def save(self, index: int, *, revision: str | None, instances: object,
             rejected: object) -> str:
        with self.lock:
            baseline, actual_revision, _ = self._load(index)
            if revision != actual_revision:
                raise RevisionConflict("saved annotations changed; reload page before overwriting")
            width, height = int(baseline["image_width"]), int(baseline["image_height"])
            validate_instances(instances, width, height)
            if not isinstance(rejected, list) or len(rejected) > 150_000:
                raise LabelingError("rejected_proposals must be a bounded list")
            for item in rejected:
                if (not isinstance(item, dict) or
                        item.get("review_status") != "rejected" or
                        item.get("verified_ground_truth") is True):
                    raise LabelingError("rejection history must contain unverified rejected boxes")
            baseline["instances"] = instances
            baseline["rejected_proposals"] = rejected
            # Page-level completeness is never implicitly conferred by manual
            # positive labels or by exhausting SAM proposal review.
            if self.pdf:
                baseline["exhaustive_families"] = []
                baseline["annotation_completeness"] = "partial"
                baseline["annotation_completeness_verified"] = False
                baseline["annotation_scope"] = "positive_only"
            encoded = (json.dumps(baseline, ensure_ascii=False, indent=2) + "\n").encode()
            _atomic_bytes(self._json_path(index), encoded)
            return hashlib.sha256(encoded).hexdigest()


def make_handler(session: LabelingSession):
    class Handler(BaseHTTPRequestHandler):
        def _reply(self, code: int, body: bytes, mime: str) -> None:
            self.send_response(code)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Cross-Origin-Resource-Policy", "same-origin")
            self.send_header(
                "Content-Security-Policy",
                "default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                "script-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'"
            )
            self.end_headers()
            self.wfile.write(body)

        def _json(self, code: int, data: dict) -> None:
            self._reply(code, json.dumps(data, ensure_ascii=False).encode(), "application/json; charset=utf-8")

        def _trusted_host(self) -> bool:
            host = self.headers.get("Host", "").lower()
            port = self.server.server_port
            return host in (f"127.0.0.1:{port}", f"localhost:{port}")

        def _page(self, query: str) -> int:
            q = parse_qs(query, keep_blank_values=True)
            if set(q) - {"page"} or len(q.get("page", [])) > 1:
                raise LabelingError("unsupported page query")
            raw = q.get("page", [str(session.start_page)])[0]
            try:
                return session._page_index(int(raw))
            except (ValueError, TypeError):
                raise LabelingError("invalid page number") from None

        def do_GET(self) -> None:
            if not self._trusted_host():
                self._json(403, {"error": "local host header required"})
                return
            url = urlsplit(self.path)
            try:
                if url.path == "/":
                    self._reply(200, (MODULE_DIR / "web_ui.html").read_bytes(), "text/html; charset=utf-8")
                elif url.path == "/web_ui.js":
                    self._reply(200, (MODULE_DIR / "web_ui.js").read_bytes(), "text/javascript; charset=utf-8")
                elif url.path == "/api/session":
                    self._json(200, {"token": session.token, "initial_page": session.start_page})
                elif url.path == "/api/page":
                    self._json(200, session.page_state(self._page(url.query)))
                elif url.path == "/api/image":
                    self._reply(200, session.image_bytes(self._page(url.query)), "image/png")
                else:
                    self._json(404, {"error": "unknown endpoint"})
            except (LabelingError, ValueError, FileNotFoundError) as exc:
                self._json(409, {"error": str(exc)})

        def do_POST(self) -> None:
            if not self._trusted_host() or self.headers.get("X-Labeler-Token") != session.token:
                self._json(403, {"error": "local session authorization required"})
                return
            origin = self.headers.get("Origin")
            if origin not in (None, f"http://127.0.0.1:{self.server.server_port}",
                              f"http://localhost:{self.server.server_port}"):
                self._json(403, {"error": "foreign origin rejected"})
                return
            url = urlsplit(self.path)
            if url.path != "/api/save":
                self._json(404, {"error": "unknown endpoint"})
                return
            length_header = self.headers.get("Content-Length", "")
            try:
                length = int(length_header)
                if length < 1 or length > MAX_BODY_BYTES:
                    raise LabelingError("annotation request is too large or empty")
                raw = self.rfile.read(length)
                data = json.loads(raw)
                if not isinstance(data, dict):
                    raise LabelingError("save request must be an object")
                new_revision = session.save(
                    self._page(url.query), revision=data.get("revision"),
                    instances=data.get("instances"),
                    rejected=data.get("rejected_proposals"),
                )
                self._json(200, {"revision": new_revision, "saved": True})
            except RevisionConflict as exc:
                self._json(409, {"error": str(exc)})
            except (LabelingError, ValueError, TypeError, KeyError) as exc:
                self._json(400, {"error": str(exc)})

        def log_message(self, format: str, *args) -> None:
            pass

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--pdf", type=Path)
    mode.add_argument("--edit", type=Path)
    parser.add_argument("--out-dir", type=Path)
    parser.add_argument("--proposals", type=Path)
    parser.add_argument("--page", type=int, default=0, help="Initial 0-based PDF page")
    parser.add_argument("--dpi", type=int, default=150)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--open-browser", action="store_true")
    args = parser.parse_args()
    session = LabelingSession(
        pdf=args.pdf, edit=args.edit, out_dir=args.out_dir, page=args.page,
        dpi=args.dpi, proposals=args.proposals,
    )
    with ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(session)) as server:
        link = f"http://127.0.0.1:{server.server_port}/"
        print(f"Manual labeler: {link}")
        print(f"Annotations: {session.out_dir} (positive-only; no exhaustive labels inferred)")
        if args.open_browser:
            webbrowser.open(link)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()

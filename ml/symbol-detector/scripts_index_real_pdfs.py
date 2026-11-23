"""Index all eligible real PDF pages for on-demand SSL crop rendering.

Unlike scripts_build_page_pool.py, this creates NO full-page PNGs. Hundreds of
pages per document cost only a compact JSON index; PyMuPDF crops are rendered
at the *chosen training scale* by BlueprintSSLDataset each time sampled.

python scripts_index_real_pdfs.py DATA_ROOT WORK/pdf_page_index/index.json \
  --roles class_maps/page_roles.json \
  --reserved-manifest WORK/heldout_real_pdfs.json

An optional reserved manifest contains:
  {"source_pdfs": ["1_blueprints/.../reserved.pdf"],
   "project_family_ids": ["file_..."]}
Entries may use absolute or data-root-relative paths. Existing source-group
exclusions are independently enforced again by the SSL manifest builder.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from typing import Any

from scripts_build_page_pool import _page_role, group_for, load_rules, match_rule


def reserved_sources(path: Path | None, data_root: Path) -> tuple[set[str], set[str]]:
    if path is None:
        return set(), set()
    if not path.is_file():
        raise FileNotFoundError(f"PDF reservation manifest not found: {path}")
    data = json.loads(path.read_text())
    if not isinstance(data, dict):
        raise ValueError("Reserved PDF manifest must be an object")
    names = data.get("source_pdfs", data.get("pdfs", []))
    groups = data.get("project_family_ids", data.get("groups", []))
    if not isinstance(names, list) or not isinstance(groups, list):
        raise ValueError("Reserved PDF sources and groups must be arrays")
    paths = set()
    for name in names:
        candidate = Path(str(name)).expanduser()
        paths.add(str((candidate if candidate.is_absolute() else data_root / candidate).resolve()))
    return paths, {str(x) for x in groups}


def _reserved_dir(rel: Path) -> bool:
    """Never index a file obviously segregated into an evaluation directory."""
    return bool({x.lower() for x in rel.parts[:-1]} & {
        "reserved", "heldout", "held_out", "test_set", "evaluation_set",
        "eval_only", "final_eval",
    })


def index_pdf_pages(
    data_root: Path, out: Path, *, roles: Path,
    reserved_manifest: Path | None = None,
) -> dict[str, Any]:
    try:
        import fitz
    except ImportError as exc:
        raise RuntimeError("PyMuPDF is required for real PDF page indexing") from exc

    root = data_root.resolve()
    rules_path = roles.resolve()
    config = load_rules(rules_path)
    rules_sha = hashlib.sha256(rules_path.read_bytes()).hexdigest()
    excluded_paths, excluded_groups = reserved_sources(reserved_manifest, root)

    previous = {}
    if out.is_file():
        try:
            previous = json.loads(out.read_text())
        except (ValueError, OSError):
            previous = {}
    can_reuse = previous.get("rules_sha256") == rules_sha
    prior = previous.get("documents", {}) if can_reuse else {}

    documents: dict[str, Any] = {}
    stats = {"indexed_pdfs": 0, "indexed_pages": 0, "reused_pdfs": 0,
             "excluded_reserved_pdfs": 0, "unmatched_pdf_files": 0,
             "failed_pdfs": 0, "empty_pages": 0, "max_pages_in_pdf": 0}
    # Mixed-case file extensions are common in contractor drawing archives.
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() != ".pdf":
            continue
        rel_path = path.relative_to(root)
        rel = rel_path.as_posix()
        rule = match_rule(rel, config)
        if rule is None:
            stats["unmatched_pdf_files"] += 1
            continue
        if rule["role"] in ("skip", "asset"):
            continue
        group = group_for(rel, rule)
        if (_reserved_dir(rel_path) or str(path.resolve()) in excluded_paths
                or group in excluded_groups):
            stats["excluded_reserved_pdfs"] += 1
            continue
        st = path.stat()
        signature = [st.st_size, st.st_mtime_ns]
        cached = prior.get(rel)
        if cached and cached.get("signature") == signature:
            documents[rel] = cached
            stats["reused_pdfs"] += 1
            pages = len(cached["pages"])
            stats["indexed_pdfs"] += 1
            stats["indexed_pages"] += pages
            stats["max_pages_in_pdf"] = max(stats["max_pages_in_pdf"], pages)
            continue

        pages = []
        try:
            with fitz.open(path) as pdf:
                for index, page in enumerate(pdf):
                    box = page.rect
                    if box.is_empty or box.width < 72 or box.height < 72:
                        stats["empty_pages"] += 1
                        continue
                    # Skip empty content streams without decoding the page.
                    if not page.get_contents():
                        stats["empty_pages"] += 1
                        continue
                    role = _page_role(rule, index + 1) or rule["role"]
                    if role in ("skip", "asset"):
                        continue
                    pages.append({
                        "image_path": str(path.resolve()),
                        "source_pdf": str(path.resolve()),
                        "pdf_page_index": index,
                        "pdf_width_pt": float(box.width),
                        "pdf_height_pt": float(box.height),
                        "project_family_id": group,
                        "source_group_verified": True,
                        "source_relative_path": rel,
                    })
        except Exception:
            stats["failed_pdfs"] += 1
            continue

        if pages:
            documents[rel] = {"project_family_id": group,
                              "signature": signature, "pages": pages}
            stats["indexed_pdfs"] += 1
            stats["indexed_pages"] += len(pages)
            stats["max_pages_in_pdf"] = max(stats["max_pages_in_pdf"], len(pages))

    result = {
        "format_version": 1, "data_root": str(root),
        "rules_sha256": rules_sha,
        "reserved_manifest": str(reserved_manifest.resolve()) if reserved_manifest else None,
        "documents": documents, "stats": stats,
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_suffix(out.suffix + ".tmp")
    tmp.write_text(json.dumps(result, separators=(",", ":")))
    os.replace(tmp, out)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("data_root", type=Path)
    parser.add_argument("out", type=Path)
    parser.add_argument("--roles", required=True, type=Path)
    parser.add_argument("--reserved-manifest", type=Path)
    args = parser.parse_args()
    index = index_pdf_pages(
        args.data_root, args.out, roles=args.roles,
        reserved_manifest=args.reserved_manifest,
    )
    print(json.dumps(index["stats"], indent=2), flush=True)


if __name__ == "__main__":
    main()

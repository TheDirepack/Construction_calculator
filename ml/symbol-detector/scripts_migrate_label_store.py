"""Copy a flat label store (the old ``work/labels``) into the devices folders.

Each document folder ``<store>/<stem>_<sha12>/`` goes to the ``devices`` folder
beside its PDF (``1_blueprints/devices/`` or ``1_blueprints/evaluation/devices/``,
see ``annotator.label_store``). Photos and documents whose PDF is not on this
machine go to ``1_blueprints/devices/``. Agent batches and the scan memory go
to ``1_blueprints/devices/``.

Nothing is deleted or overwritten: a file that already exists at the target
with different bytes is reported as a conflict and left alone. Every copied
file is checked by SHA-256. Remove the old store yourself once the report is
clean. Dry run unless ``--apply``.

  python scripts_migrate_label_store.py --from work/labels \\
      --to ../../blueprints_and_libraries/1_blueprints            # report only
  python scripts_migrate_label_store.py --from work/labels \\
      --to ../../blueprints_and_libraries/1_blueprints --apply

On the training machine (from ~/Projects/blueprint-symbols):

  PYTHONPATH=code .venv/bin/python code/scripts_migrate_label_store.py \\
      --from work/labels --to data/blueprints_and_libraries/1_blueprints --apply
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from collections import Counter
from pathlib import Path

from annotator import label_store


def _sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _target(folder: Path, to: Path) -> tuple[Path, str]:
    """Where a document folder goes, and why."""
    try:
        doc = label_store.load_document(folder)
    except (FileNotFoundError, ValueError) as exc:
        return to / label_store.LABELS_DIR / folder.name, f"source not usable here ({exc})"
    if doc.kind != "pdf":
        return to / label_store.LABELS_DIR / folder.name, "photo"
    target = label_store.document_folder(to, doc.source, doc.sha256)
    if target.name != folder.name:                 # never rename a folder: pages refer to it
        return to / label_store.LABELS_DIR / folder.name, "folder name differs from the PDF's"
    return target, "beside its PDF"


def _copy_tree(src: Path, dst: Path, *, apply: bool, stats: Counter, conflicts: list[str]) -> None:
    for path in sorted(p for p in src.rglob("*") if p.is_file()):
        out = dst / path.relative_to(src)
        if out.exists():
            if _sha(out) == _sha(path):
                stats["files_already_there"] += 1
            else:
                stats["conflicts"] += 1
                conflicts.append(str(out))
            continue
        stats["files_to_copy"] += 1
        stats["bytes_to_copy"] += path.stat().st_size
        if apply:
            out.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, out)
            if _sha(out) != _sha(path):
                raise SystemExit(f"copy check failed: {out}")
            stats["files_copied"] += 1


def _photo_source_relative(folder: Path) -> None:
    """A photo document names its copy in images/; make that path relative so
    the document stays valid in its new folder."""
    meta_path = folder / label_store.DOCUMENT_FILE
    data = json.loads(meta_path.read_text(encoding="utf-8"))
    source = Path(data.get("source", ""))
    if data.get("kind") == "image" and source.is_absolute() and "images" in source.parts:
        data["source"] = f"images/{source.name}"
        meta_path.write_text(json.dumps(data, indent=1), encoding="utf-8")


def migrate(old: Path, to: Path, *, apply: bool) -> dict:
    old, to = old.resolve(), to.resolve()
    if not label_store.is_blueprints_root(to):
        raise SystemExit(f"{to} is not a 1_blueprints folder")
    stats: Counter = Counter()
    conflicts: list[str] = []
    placements: dict[str, list[str]] = {}
    for meta in sorted(old.glob(f"*/{label_store.DOCUMENT_FILE}")):
        folder = meta.parent
        target, why = _target(folder, to)
        stats["documents"] += 1
        placements.setdefault(why, []).append(f"{folder.name} -> {target.relative_to(to)}")
        _copy_tree(folder, target, apply=apply, stats=stats, conflicts=conflicts)
        if apply:
            _photo_source_relative(target)

    state = label_store.state_dir(to)
    batches = old / label_store.BATCH_DIR
    if batches.is_dir():
        _copy_tree(batches, state / label_store.BATCH_DIR, apply=apply, stats=stats, conflicts=conflicts)
    scanned = old / "scanned_pdfs.json"
    if scanned.is_file():
        target = state / "scanned_pdfs.json"
        merged = json.loads(target.read_text()) if target.is_file() else {}
        added = {k: v for k, v in json.loads(scanned.read_text()).items() if k not in merged}
        stats["scan_memory_entries_added"] += len(added)
        if apply and added:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps({**merged, **added}))

    if apply:                       # every document folder must load from its new place
        for meta in sorted(old.glob(f"*/{label_store.DOCUMENT_FILE}")):
            target, why = _target(meta.parent, to)
            if why in ("beside its PDF", "photo"):
                label_store.load_document(target)
                stats["documents_verified"] += 1
    return {"apply": apply, "from": str(old), "to": str(to), **stats,
            "placements": placements, "conflicts": conflicts}


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--from", dest="old", type=Path, required=True, help="old flat store (work/labels)")
    p.add_argument("--to", type=Path, required=True, help="the 1_blueprints folder")
    p.add_argument("--apply", action="store_true", help="copy (default: report only)")
    args = p.parse_args(argv)
    report = migrate(args.old, args.to, apply=args.apply)
    print(json.dumps(report, indent=1))
    return 1 if report["conflicts"] else 0


if __name__ == "__main__":
    sys.exit(main())

"""Crash-safe local SAM PDF result writes and resume validation."""
from __future__ import annotations

import json
import os
import hashlib
from pathlib import Path
from typing import Any


def file_sha256(path: Path, *, chunk_size: int = 1024 * 1024) -> str:
    """Hash complete PDF bytes for resume/review source provenance.

    One sequential pass per input PDF avoids relying on file metadata or
    partial first/last-block checks. A 1 MiB buffer bounds RAM.
    """
    if chunk_size < 1:
        raise ValueError('hash chunk size must be positive')
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(chunk_size), b''):
            h.update(block)
    return h.hexdigest()


def completed_sam_output(
    path: Path, *, run_config_hash: str, source_pdf_relative: str,
    source_pdf_sha256: str | None = None,
) -> bool:
    """Only skip an output if it is structurally complete for THIS run."""
    path = Path(path)
    if not path.is_file():
        return False
    try:
        value = json.loads(path.read_text())
    except (OSError, UnicodeError, ValueError, TypeError):
        return False
    if not isinstance(value, dict):
        return False
    if (value.get('run_config_hash') != run_config_hash or
            value.get('source_pdf_relative') != source_pdf_relative or
            value.get('status') != 'provisional_detections_for_refinement'):
        return False
    if (source_pdf_sha256 is not None and
            value.get('source_pdf_sha256') != source_pdf_sha256):
        return False
    devices = value.get('devices')
    pages = value.get('pages')
    if not isinstance(devices, list) or not isinstance(pages, list):
        return False
    if (type(value.get('total_devices')) is not int or
            value['total_devices'] != len(devices)):
        return False
    for key in ('total_pdf_pages', 'pages_scanned'):
        if type(value.get(key)) is not int or value[key] < 0:
            return False
    if value['pages_scanned'] > value['total_pdf_pages']:
        return False
    return True


def atomic_write_json(path: Path, data: dict[str, Any]) -> None:
    """Do not expose an incomplete JSON file after shutdown or OOM."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(f'{path.name}.{os.getpid()}.tmp')
    try:
        with temp.open('w', encoding='utf-8') as file:
            json.dump(data, file)
            file.flush()
            os.fsync(file.fileno())
        temp.replace(path)
    finally:
        temp.unlink(missing_ok=True)

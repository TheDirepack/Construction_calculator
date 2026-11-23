"""Crash-safe local SAM PDF result writes and resume validation."""
from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any


def completed_sam_output(
    path: Path, *, run_config_hash: str, source_pdf_relative: str,
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

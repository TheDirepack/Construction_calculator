"""Render exact construction-PDF negative-control candidates for review.

Example:
 python scripts_audit_negative_controls.py reviewed_regions.json --out work/negatives_qa

Input: {"controls": [
  {"id":"notes_01","pdf":"/data/plans.pdf","page":3,
   "rect_pt":[120, 300, 300, 480], "kind":"text_heavy"}
]}
Page numbers are 1-based; rect_pt uses PyMuPDF top-left page coordinates.
Source review candidates do NOT become verified no-device training labels:
the complete raster view needs independent exhaustive annotation.
Never use reserved/final-evaluation sources for training by exporting a QA set.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np


CONTROL_KINDS = {
    "text_heavy", "walls_only", "sparse_lines", "blank",
    "non_device_geometry",
}


def render_controls(
    manifest: Path, out: Path, *, size: int = 512, dpi: float = 200,
) -> dict:
    import fitz

    if size < 32 or not 100 <= dpi <= 400:
        raise ValueError("size must be >=32 and QA DPI in 100..400")
    spec = json.loads(manifest.read_text(encoding="utf8"))
    controls = spec.get("controls", [])
    if not isinstance(controls, list) or not controls:
        raise ValueError("a nonempty controls list is required")
    out.mkdir(parents=True, exist_ok=True)
    pages = []
    seen = set()
    counts = Counter()
    for item in controls:
        name = item["id"]
        kind = item["kind"]
        if not isinstance(name, str) or not name or name in seen:
            raise ValueError("nonunique/invalid negative control ID")
        seen.add(name)
        if kind not in CONTROL_KINDS:
            raise ValueError(f"unknown kind: {kind}")
        source = Path(item["pdf"]).expanduser().resolve()
        if not source.is_file() or source.suffix.lower() != ".pdf":
            raise FileNotFoundError(source)
        page_idx = int(item["page"]) - 1
        with fitz.open(source) as document:
            if not 0 <= page_idx < len(document):
                raise IndexError(f"page {page_idx+1} invalid in {source}")
            page = document[page_idx]
            coords = item["rect_pt"]
            if not isinstance(coords, list) or len(coords) != 4:
                raise ValueError("rect_pt must contain four numbers")
            rect = fitz.Rect(*[float(v) for v in coords])
            if (not rect.is_finite or rect.is_empty or
                    rect.width < 20 or rect.height < 20 or
                    not page.rect.contains(rect)):
                raise ValueError(f"control {name} has invalid source crop")
            # Render exactly the reviewed viewport; scaling to 512px is
            # display normalization, not an invented physical source DPI.
            scale = dpi / 72.
            pix = page.get_pixmap(
                matrix=fitz.Matrix(scale, scale), clip=rect,
                colorspace=fitz.csRGB, alpha=False,
            )
            rgb = np.frombuffer(pix.samples, np.uint8).reshape(
                pix.height, pix.width, 3
            )
            interpolation = cv2.INTER_AREA if max(rgb.shape[:2]) > size else cv2.INTER_LINEAR
            rgb = cv2.resize(rgb, (size, size), interpolation=interpolation)
        filename = hashlib.sha256(
            f"{source}|{page_idx}|{coords}|{name}".encode()
        ).hexdigest()[:16] + "_" + kind + ".png"
        bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
        if not cv2.imwrite(str(out / filename), bgr):
            raise OSError(f"failed to save {filename}")
        gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
        counts[kind] += 1
        pages.append({
            "id": name, "kind": kind, "source_pdf": str(source),
            "page": page_idx + 1, "rect_pt": [round(float(x), 4) for x in coords],
            "render_dpi": float(dpi), "output": filename,
            "ink_fraction_lt_210": round(float(np.mean(gray < 210)), 6),
            "mean_luminance": round(float(gray.mean()), 3),
            # This is *only* a visual-review candidate, not a detector label.
            "synthetic_suppressed": True, "detector_negative_verified": False,
            "review_status": "pending_exact_crop_exhaustive_review",
            "use_for_detector_evaluation": False,
        })
    report = {
        "purpose": "real_pdf_negative_candidate_visual_qa_not_detector_ground_truth",
        "count": len(pages), "kind_counts": dict(counts), "controls": pages,
    }
    (out / "manifest.json").write_text(
        json.dumps(report, indent=2), encoding="utf8"
    )
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--dpi", type=float, default=200)
    a = parser.parse_args()
    result = render_controls(a.manifest, a.out, size=a.size, dpi=a.dpi)
    print(json.dumps({key: value for key, value in result.items()
                      if key != "controls"}, indent=2))


if __name__ == "__main__":
    main()

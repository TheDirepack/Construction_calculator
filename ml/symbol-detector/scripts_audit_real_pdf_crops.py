"""QA-only render gallery for real uploaded construction-plan PDFs.

Examples:
  python scripts_audit_real_pdf_crops.py /path/to/plan.pdf --pages 31 33 34 \
    --samples-per-page 6 --out work/pdf_crop_qa
  python scripts_audit_real_pdf_crops.py /path/to/a.pdf /path/to/b.pdf \
    --neca-dir /path/to/NECA_100_Vector_Library --out work/pdf_crop_qa

The source PDFs are read directly. No PDFs are copied into training directories,
no labels are created, and these QA samples are NOT training or evaluation data.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np

from blueprint_symbols.pdf_crop import PdfCropSampler


def audit_pdfs(
    pdfs: list[Path],
    output: Path,
    *,
    pages: list[int] | None = None,
    samples_per_page: int = 4,
    size: int = 512,
    seed: int = 2026,
    max_symbols: int = 8,
    neca_dir: Path | None = None,
    repeat_region_dpis: int = 1,
    dpi_epoch: int = 0,
) -> dict:
    """Return counts and visual diagnostics without writing training labels."""
    import fitz

    if (samples_per_page < 1 or size < 32 or not 0 <= max_symbols <= 16
            or not 1 <= repeat_region_dpis <= 32 or dpi_epoch < 0):
        raise ValueError("Invalid audit sample count, crop size, or symbol limit")
    if not pdfs or not all(p.is_file() for p in pdfs):
        raise FileNotFoundError("Every QA PDF must already exist")
    output.mkdir(parents=True, exist_ok=True)

    bank = None
    if neca_dir is not None:
        from blueprint_symbols.neca_svg_compositor import NecaSymbolBank
        bank = NecaSymbolBank(neca_dir)
    from blueprint_symbols.ssl_augmentation import compose_ssl_symbols

    records = []
    class_counts = Counter()
    sampler = PdfCropSampler()
    try:
        for pdf_path in pdfs:
            pdf_path = pdf_path.resolve()
            source_key = hashlib.sha256(str(pdf_path).encode()).hexdigest()[:10]
            with fitz.open(pdf_path) as pdf:
                indices = (list(range(len(pdf))) if pages is None
                           else [p - 1 for p in pages])
                for index in indices:
                    if not 0 <= index < len(pdf):
                        raise IndexError(f"Page {index+1} missing in {pdf_path}")
                    entry = {"source_pdf": str(pdf_path), "pdf_page_index": index}
                    for sample_id in range(samples_per_page):
                        # Deterministic per PDF/page/sample across runs.
                        identity = f"{seed}|{pdf_path.name}|{index}|{sample_id}"
                        state = int.from_bytes(
                            hashlib.sha256(identity.encode()).digest()[:8], "big"
                        )
                        rng = np.random.default_rng(state)
                        if repeat_region_dpis > 1:
                            # Hold physical source anchor across neighboring
                            # samples, but render each at its own real DPI.
                            group_index = sample_id // repeat_region_dpis
                            anchor_key = (f"{seed}|{pdf_path}|{index}|"
                                          f"region:{group_index}")
                            anchor_seed = int.from_bytes(
                                hashlib.sha256(anchor_key.encode()).digest()[:8],
                                "big"
                            )
                            clean, meta = sampler.sample(
                                entry, size, rng, crop_seed=anchor_seed,
                                dpi_variant=sample_id % repeat_region_dpis,
                                dpi_variants=repeat_region_dpis,
                                dpi_epoch=dpi_epoch,
                            )
                        else:
                            clean, meta = sampler.sample(entry, size, rng)
                        composite, symbol_meta = compose_ssl_symbols(
                            clean, rng, neca_bank=bank, max_symbols=max_symbols,
                            drawing_dpi=meta["effective_dpi"],
                            symbol_chaos_probability=0.0,
                            placement_chaos=0.0,
                        )
                        if clean.shape != composite.shape:
                            raise AssertionError("Compositor changed crop dimensions")
                        placements = symbol_meta["placements"]
                        for item in placements:
                            if item["drawing_dpi"] != meta["effective_dpi"]:
                                raise AssertionError("PDF/symbol DPI mismatch")
                            bbox = item["bbox_xyxy"]
                            if not (0 <= bbox[0] < bbox[2] <= size
                                    and 0 <= bbox[1] < bbox[3] <= size):
                                raise AssertionError("Composite symbol leaves crop")
                            class_counts[item.get("symbol_family", "unknown")] += 1
                        pair = np.hstack((clean, composite))
                        file_name = (f"{pdf_path.stem[:40]}_{source_key}_p{index+1:03d}"
                                     f"_s{sample_id:03d}.png")
                        if not cv2.imwrite(str(output / file_name), pair):
                            raise OSError(f"Failed to write QA image: {file_name}")
                        gray = cv2.cvtColor(clean, cv2.COLOR_BGR2GRAY)
                        records.append({
                            "pdf_name": pdf_path.name,
                            "pdf_source_key": source_key,
                            "page": index + 1,
                            "sample": sample_id,
                            "output": file_name,
                            "pdf_crop": meta,
                            "ink_fraction_gray_lt_240": round(
                                float(np.mean(gray < 240)), 6
                            ),
                            "symbol_requested": symbol_meta["requested"],
                            "symbol_placed": symbol_meta["placed"],
                            "placements": placements,
                        })
    finally:
        sampler.close()

    dpis = [row["pdf_crop"]["effective_dpi"] for row in records]
    result = {
        "purpose": "real_pdf_visual_qa_only_not_training",
        "sample_count": len(records),
        "pdf_count": len({row["pdf_source_key"] for row in records}),
        "dpi_min": min(dpis) if dpis else None,
        "dpi_max": max(dpis) if dpis else None,
        "repeat_region_dpis": repeat_region_dpis,
        "dpi_epoch": dpi_epoch,
        "symbols_by_type": dict(class_counts),
        "records": records,
    }
    (output / "report.json").write_text(
        json.dumps(result, indent=2, default=str), encoding="utf-8"
    )
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdfs", nargs="+", type=Path)
    parser.add_argument("--pages", nargs="+", type=int,
                        help="1-based PDF pages; omit to audit every page")
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--samples-per-page", type=int, default=4)
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--max-symbols", type=int, default=8)
    parser.add_argument("--repeat-region-dpis", type=int, default=1,
                        help="Render each physical PDF region at N different true DPI values")
    parser.add_argument("--dpi-epoch", type=int, default=0,
                        help="Refresh random DPI variants while retaining the same PDF anchor")
    parser.add_argument("--neca-dir", type=Path)
    parser.add_argument("--seed", type=int, default=2026)
    a = parser.parse_args()
    r = audit_pdfs(
        a.pdfs, a.out, pages=a.pages, samples_per_page=a.samples_per_page,
        size=a.size, max_symbols=a.max_symbols, neca_dir=a.neca_dir,
        seed=a.seed, repeat_region_dpis=a.repeat_region_dpis,
        dpi_epoch=a.dpi_epoch,
    )
    print(json.dumps({k: v for k, v in r.items() if k != "records"}, indent=2))


if __name__ == "__main__":
    main()

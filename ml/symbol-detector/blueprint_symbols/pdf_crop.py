"""Direct on-demand crops from multi-page PDF construction drawing sets.

Rather than decoding a 24 MP page raster and discarding almost all pixels,
keep a small low-resolution density thumbnail (for crop-site selection) and
ask PyMuPDF to render ONLY a randomly selected page rectangle at the final
training scale. Per-worker LRU caches bound documents, thumbnails, and
replayed clean crops. Each crop can receive many independent augmentation
views without repeatedly decoding the PDF.
"""
from __future__ import annotations

from collections import OrderedDict
import math
from pathlib import Path

import cv2
import numpy as np


class PdfCropSampler:
    def __init__(self, max_open_documents: int = 3,
                 max_cached_thumbnails: int = 96,
                 thumbnail_long_side: int = 384,
                 max_cached_crops: int = 32):
        if min(max_open_documents, max_cached_thumbnails, thumbnail_long_side,
               max_cached_crops) < 1:
            raise ValueError("PDF cache limits must be positive")
        self.max_open_documents = int(max_open_documents)
        self.max_cached_thumbnails = int(max_cached_thumbnails)
        self.thumbnail_long_side = int(thumbnail_long_side)
        self.max_cached_crops = int(max_cached_crops)
        self._docs = OrderedDict()
        self._thumbs = OrderedDict()
        self._crops = OrderedDict()

    def close(self) -> None:
        for doc in self._docs.values():
            doc.close()
        self._docs.clear()
        self._thumbs.clear()
        self._crops.clear()

    def _open(self, path: str):
        doc = self._docs.get(path)
        if doc is not None:
            self._docs.move_to_end(path)
            return doc
        import fitz
        doc = fitz.open(path)
        self._docs[path] = doc
        while len(self._docs) > self.max_open_documents:
            self._docs.popitem(last=False)[1].close()
        return doc

    def _density(self, path: str, index: int, page) -> tuple[np.ndarray, int, int]:
        key = (path, index)
        old = self._thumbs.get(key)
        if old is not None:
            self._thumbs.move_to_end(key)
            return old
        import fitz
        box = page.rect
        factor = self.thumbnail_long_side / max(box.width, box.height)
        pix = page.get_pixmap(matrix=fitz.Matrix(factor, factor),
                              colorspace=fitz.csGRAY, alpha=False)
        gray = np.frombuffer(pix.samples, np.uint8).reshape(pix.height, pix.width)
        # At 384px across a construction sheet, antialiased thin/faint CAD
        # strokes may be much lighter than 190. Estimate local paper level
        # from the thumbnail instead of silently treating these as blank.
        paper = float(np.percentile(gray, 90))
        ink_depth = paper - float(np.percentile(gray, 2))
        threshold = paper - max(6.0, min(32.0, ink_depth * .24))
        ink = gray < threshold
        integral = cv2.integral(ink.astype(np.uint8))
        # A bounded per-page ink-anchor index finds small, isolated drawing
        # regions which 8 uniform crop proposals routinely miss.
        anchors = np.flatnonzero(ink).astype(np.int32)
        if len(anchors) > 8192:
            anchors = anchors[np.linspace(0, len(anchors) - 1, 8192,
                                           dtype=np.intp)]
        result = integral, pix.width, pix.height, anchors, round(threshold, 2)
        self._thumbs[key] = result
        while len(self._thumbs) > self.max_cached_thumbnails:
            self._thumbs.popitem(last=False)
        return result

    @staticmethod
    def _ink_fraction(integral: np.ndarray, sw: int, sh: int,
                      x: float, y: float, span: float,
                      page_width: float, page_height: float) -> float:
        x0 = max(0, min(sw - 1, int(x / page_width * sw)))
        y0 = max(0, min(sh - 1, int(y / page_height * sh)))
        x1 = max(x0 + 1, min(sw, int(math.ceil((x + span) / page_width * sw))))
        y1 = max(y0 + 1, min(sh, int(math.ceil((y + span) / page_height * sh))))
        count = (integral[y1, x1] - integral[y0, x1]
                 - integral[y1, x0] + integral[y0, x0])
        return float(count) / max(1, (x1 - x0) * (y1 - y0))

    @staticmethod
    def _spatial_stratum(
        slot: int, bank_size: int, max_x: float, max_y: float,
        width: float, height: float,
    ) -> tuple[tuple[float, float, float, float], tuple[int, int]]:
        """Distribute stable crop slots across the whole drawable page.

        Exact factor grids avoid vacant final-row cells (e.g. 32 -> 8x4 on
        landscape, 4x8 on portrait). Coordinates describe valid crop origins,
        not PDF image boundaries. All slots retain their own random DPI.
        """
        if not 1 <= bank_size <= 128 or not 0 <= slot < bank_size:
            raise ValueError("invalid PDF crop bank slot")
        divisors = [c for c in range(1, bank_size + 1)
                    if bank_size % c == 0]
        cols = min(divisors, key=lambda c: abs(math.log(
            (c / (bank_size // c)) / max(.01, width / height)
        )))
        rows = bank_size // cols
        col, row = slot % cols, slot // cols
        return (
            (max_x * col / cols, max_x * (col + 1) / cols,
             max_y * row / rows, max_y * (row + 1) / rows),
            (cols, rows),
        )

    def sample(self, entry: dict, size: int, rng: np.random.Generator,
               *, crop_seed: int | None = None,
               crop_slot: int | None = None,
               bank_size: int | None = None,
               dpi_variant: int | None = None,
               dpi_variants: int | None = None,
               dpi_epoch: int = 0) -> tuple[np.ndarray, dict]:
        """Clip-render from PDF; optionally reuse an exact physical region anchor.

        crop_seed controls base site selection. With dpi_variant, multiple
        real 100–400 DPI zoom levels share its physical center, each cached
        separately. The caller independently randomizes symbols and damage.
        Seedless calls preserve the previous fresh-random behavior.
        """
        if size < 32:
            raise ValueError("PDF training crop must be at least 32 pixels")
        if not isinstance(dpi_epoch, int) or dpi_epoch < 0:
            raise ValueError("dpi_epoch must be a nonnegative integer")
        if (dpi_variant is None) != (dpi_variants is None):
            raise ValueError("dpi_variant and dpi_variants must be provided together")
        if dpi_variant is not None and not (
            isinstance(dpi_variants, int) and 1 <= dpi_variants <= 32
            and isinstance(dpi_variant, int) and 0 <= dpi_variant < dpi_variants
            and crop_seed is not None
        ):
            raise ValueError("invalid seeded multi-DPI variant")
        if (crop_slot is None) != (bank_size is None):
            raise ValueError("crop_slot and bank_size must be set together")
        if crop_slot is not None and not (
            isinstance(bank_size, int) and 1 <= bank_size <= 128
            and isinstance(crop_slot, int) and 0 <= crop_slot < bank_size
            and crop_seed is not None
        ):
            raise ValueError("invalid seeded PDF spatial crop bank")
        pdf_path = str(entry.get("source_pdf") or entry["image_path"])
        page_index = int(entry["pdf_page_index"])
        key = ((pdf_path, page_index, int(size), int(crop_seed),
                crop_slot, bank_size, dpi_variant, dpi_variants,
                dpi_epoch if dpi_variant is not None else 0)
               if crop_seed is not None else None)
        if key is not None and key in self._crops:
            self._crops.move_to_end(key)
            saved_image, saved_meta = self._crops[key]
            return saved_image.copy(), {**saved_meta, "clean_crop_cache_hit": True}
        # Seeded replay must not consume the independent augmentation RNG.
        if crop_seed is not None:
            rng = np.random.default_rng(crop_seed)
        doc = self._open(pdf_path)
        if not 0 <= page_index < len(doc):
            raise IndexError(f"Invalid PDF page {page_index}: {pdf_path}")
        page = doc.load_page(page_index)
        bounds = page.rect
        width, height = float(bounds.width), float(bounds.height)
        if min(width, height) < 72:
            raise ValueError("Invalid small/empty PDF drawing page")

        # Pick the *rendering* DPI, not a post-resize approximation. The
        # physical PDF crop is size * 72 / dpi points on each side. For a 512
        # pixel crop this spans ~369pt at 100dpi down to ~92pt at 400dpi.
        # A too-small PDF page can require higher than 100dpi to fit the
        # square; do not shrink/pad it and then mislabel its physical scale.
        dpi_min = max(100.0, size * 72.0 / min(width, height))
        if dpi_min > 400.0:
            raise ValueError("PDF page too small for requested 100..400 DPI square")
        requested_dpi = float(rng.uniform(dpi_min, 400.0))
        span = float(size * 72.0 / requested_dpi)
        # Detailed views naturally occur at high DPI, broader views at low DPI.
        profile = int(np.clip(np.searchsorted((150, 225, 325), requested_dpi), 0, 3))
        max_x = max(0., width - span)
        max_y = max(0., height - span)

        integral, sw, sh, anchors, ink_threshold = self._density(
            pdf_path, page_index, page
        )
        # Spatial strata prevent a 32-slot crop bank from accidentally
        # concentrating most of its durable slots in the same small region.
        # Fresh exploratory crops retain the legacy whole-page mix.
        stratum = None
        grid = None
        local_anchors = anchors
        if crop_slot is not None:
            stratum, grid = self._spatial_stratum(
                crop_slot, bank_size, max_x, max_y, width, height
            )
            left, right, top, bottom = stratum
            if len(anchors):
                ax = ((anchors % sw) + .5) * width / sw
                ay = ((anchors // sw) + .5) * height / sh
                valid = ((ax >= left) & (ax <= right + span)
                         & (ay >= top) & (ay <= bottom + span))
                local_anchors = anchors[valid]
        candidates = []
        anchored_proposals = 0
        for attempt in range(16):
            # Mixed uniform and ink-anchored sites cover disconnected plan
            # areas (especially on large, sparse multi-sheet drawings).
            # Keep uniformly random sites so blank/margin views remain possible.
            if attempt > 0 and len(local_anchors) and rng.random() < .58:
                anchor = int(local_anchors[int(rng.integers(0, len(local_anchors)))])
                ax = ((anchor % sw) + .5) * width / sw
                ay = ((anchor // sw) + .5) * height / sh
                lo_x, hi_x = (0., max_x) if stratum is None else stratum[:2]
                lo_y, hi_y = (0., max_y) if stratum is None else stratum[2:]
                x = float(np.clip(ax - rng.uniform(0, span), lo_x, hi_x))
                y = float(np.clip(ay - rng.uniform(0, span), lo_y, hi_y))
                anchored_proposals += 1
            elif stratum is not None:
                left, right, top, bottom = stratum
                x, y = float(rng.uniform(left, right)), float(rng.uniform(top, bottom))
            elif attempt == 0 or rng.random() < .60:
                x, y = float(rng.uniform(0, max_x)), float(rng.uniform(0, max_y))
            else:
                ix, iy = int(rng.integers(0, 4)), int(rng.integers(0, 4))
                x = (ix + rng.random()) / 4 * max_x
                y = (iy + rng.random()) / 4 * max_y
            ink = self._ink_fraction(integral, sw, sh, x, y, span, width, height)
            candidates.append((x, y, ink))
        # A controlled sparse fraction trains against blank margins and low-
        # density plans; otherwise choose a nonempty region stochastically.
        if rng.random() < .14:
            x, y, ink = candidates[int(rng.integers(len(candidates)))]
            placement = "sparse_or_uniform"
        else:
            weights = np.array([
                .10 + min(1.0, ink / .018) for _, _, ink in candidates
            ], np.float64)
            weights /= weights.sum()
            x, y, ink = candidates[int(rng.choice(len(candidates), p=weights))]
            placement = "ink_mixed"

        # Independent 100–400 DPI rasterizations of one stable source region.
        # Select the physical site above using the region seed, not this view's
        # DPI; then expand/contract its field of view around that same point.
        # A stratified random DPI per view covers the full range without
        # locking a region to one fixed zoom for the entire training run.
        region_anchor = None
        if dpi_variant is not None:
            bin_index = (dpi_variant + int(crop_seed % dpi_variants)) % dpi_variants
            lo = dpi_min + (400.0 - dpi_min) * bin_index / dpi_variants
            hi = dpi_min + (400.0 - dpi_min) * (bin_index + 1) / dpi_variants
            variant_seed = np.random.SeedSequence([
                int(crop_seed & 0xffffffff),
                int((crop_seed >> 32) & 0xffffffff),
                dpi_variant, dpi_variants, dpi_epoch, 0xD91,
            ])
            dpi_rng = np.random.default_rng(variant_seed)
            new_dpi = float(dpi_rng.uniform(lo, hi))
            max_span = size * 72.0 / dpi_min
            anchor_x = float(np.clip(x + span / 2.0,
                                     max_span / 2.0, width - max_span / 2.0))
            anchor_y = float(np.clip(y + span / 2.0,
                                     max_span / 2.0, height - max_span / 2.0))
            requested_dpi = new_dpi
            span = size * 72.0 / requested_dpi
            x, y = anchor_x - span / 2.0, anchor_y - span / 2.0
            region_anchor = [round(anchor_x, 5), round(anchor_y, 5)]
            profile = int(np.clip(
                np.searchsorted((150, 225, 325), requested_dpi), 0, 3
            ))
            ink = self._ink_fraction(
                integral, sw, sh, x, y, span, width, height
            )

        import fitz
        clip = fitz.Rect(bounds.x0+x, bounds.y0+y,
                         bounds.x0+x+span, bounds.y0+y+span)
        zoom = requested_dpi / 72.0
        pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom),
                              clip=clip, colorspace=fitz.csRGB, alpha=False)
        if pix.n != 3 or pix.width <= 0 or pix.height <= 0:
            raise RuntimeError("PDF clip rasterization returned an invalid pixmap")
        rgb = np.frombuffer(pix.samples, np.uint8).reshape(
            pix.height, pix.width, pix.n
        )
        bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
        if bgr.shape[:2] != (size, size):
            bgr = cv2.resize(bgr, (size, size),
                             interpolation=(cv2.INTER_AREA if max(bgr.shape[:2]) > size
                                            else cv2.INTER_LINEAR))
        meta = {
            "pdf_page_index": page_index,
            "clip_rect_pt": [round(v, 3) for v in (x, y, x + span, y + span)],
            "effective_dpi": round(requested_dpi, 3),
            "requested_dpi": round(requested_dpi, 3),
            "dpi_range": [100, 400],
            "physical_crop_size_points": round(span, 5),
            "profile": profile, "placement": placement,
            "thumbnail_ink_fraction": round(ink, 5),
            "thumbnail_ink_threshold": ink_threshold,
            "ink_anchored_proposals": anchored_proposals,
            "sampling_policy": (
                "stratified_ink_guided_v3" if stratum is not None
                else "adaptive_ink_guided_v2"
            ),
            "bank_slot": crop_slot,
            "bank_size": bank_size,
            "bank_grid": list(grid) if grid is not None else None,
            "dpi_variant": dpi_variant,
            "dpi_variants": dpi_variants,
            "dpi_epoch": dpi_epoch if dpi_variant is not None else None,
            "region_anchor_pt": region_anchor,
            "clean_crop_seed": int(crop_seed) if crop_seed is not None else None,
            "clean_crop_cache_hit": False,
        }
        if key is not None:
            # Return a distinct copy, so training cannot mutate cached pixels.
            self._crops[key] = (bgr.copy(), meta.copy())
            while len(self._crops) > self.max_cached_crops:
                self._crops.popitem(last=False)
        return bgr, meta

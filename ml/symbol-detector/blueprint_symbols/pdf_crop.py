"""Direct on-demand crops from multi-page PDF construction drawing sets.

Rather than decoding a 24 MP page raster and discarding almost all pixels,
keep a small low-resolution density thumbnail (for crop-site selection) and
ask PyMuPDF to render ONLY a randomly selected page rectangle at the final
training scale. Separate per-worker LRU caches bound both documents and
thumbnails; no rendered 512px samples are stored, so each visit generates a
new spatial/scale combination.
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
                 thumbnail_long_side: int = 384):
        if min(max_open_documents, max_cached_thumbnails, thumbnail_long_side) < 1:
            raise ValueError("PDF cache limits must be positive")
        self.max_open_documents = int(max_open_documents)
        self.max_cached_thumbnails = int(max_cached_thumbnails)
        self.thumbnail_long_side = int(thumbnail_long_side)
        self._docs = OrderedDict()
        self._thumbs = OrderedDict()

    def close(self) -> None:
        for doc in self._docs.values():
            doc.close()
        self._docs.clear()
        self._thumbs.clear()

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
        integral = cv2.integral((gray < 190).astype(np.uint8))
        result = integral, pix.width, pix.height
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

    def sample(self, entry: dict, size: int, rng: np.random.Generator
               ) -> tuple[np.ndarray, dict]:
        if size < 32:
            raise ValueError("PDF training crop must be at least 32 pixels")
        pdf_path = str(entry.get("source_pdf") or entry["image_path"])
        page_index = int(entry["pdf_page_index"])
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

        integral, sw, sh = self._density(pdf_path, page_index, page)
        candidates = []
        for attempt in range(8):
            # Uniform coordinate sampling provides coverage; density-guided
            # sampling rejects blank paper but does not fixate on title blocks.
            if attempt == 0 or rng.random() < .60:
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
        return bgr, {
            "pdf_page_index": page_index,
            "clip_rect_pt": [round(v, 3) for v in (x, y, x + span, y + span)],
            "effective_dpi": round(requested_dpi, 3),
            "requested_dpi": round(requested_dpi, 3),
            "dpi_range": [100, 400],
            "physical_crop_size_points": round(span, 5),
            "profile": profile, "placement": placement,
            "thumbnail_ink_fraction": round(ink, 5),
        }

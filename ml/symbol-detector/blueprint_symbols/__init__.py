"""Minimal prototype for a raster-first blueprint symbol and snap pipeline."""
from .tiles import Tile, tile_page
from .geometry import FittedLine, closest_point_on_segment, fit_isolated_stroke_centerline, heatmap_peak
from .postprocess import Detection, decode_heads, to_page, merge_tiles
from .crops import CropTransform, crop_for_refiner


def __getattr__(name):
    # The models need PyTorch, which only the training machine installs. Load them on first use.
    if name in ("AnchorRefiner", "SymbolDetector"):
        from . import models
        return getattr(models, name)
    raise AttributeError(name)

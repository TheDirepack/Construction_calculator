"""Minimal prototype for a raster-first blueprint symbol and snap pipeline."""
from .models import AnchorRefiner, SymbolDetector
from .tiles import Tile, tile_page
from .geometry import FittedLine, closest_point_on_segment, fit_isolated_stroke_centerline, heatmap_peak
from .postprocess import Detection, decode_heads, to_page, merge_tiles
from .crops import CropTransform, crop_for_refiner

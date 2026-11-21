"""Generate weakly realistic but *mathematically labeled* raster sheets.

This is for pipeline bootstrapping, not a substitute for held-out real plans.
Use real extracted CAD/SVG glyph PNGs (with alpha) and an optional explicit
anchor map. The authoring transform is retained; no AI guesses or hand boxes.
"""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import json
import math
import cv2
import numpy as np


@dataclass(frozen=True)
class SymbolAsset:
    family: str
    variant: str
    rgba: np.ndarray
    anchors_normalized: dict[str, tuple[float, float]]

    def __post_init__(self) -> None:
        if self.rgba.ndim != 3 or self.rgba.shape[2] != 4 or self.rgba.dtype != np.uint8:
            raise ValueError('asset must be uint8 RGBA with transparency')
        if self.rgba.shape[0] < 2 or self.rgba.shape[1] < 2:
            raise ValueError('invalid asset image')
        if not np.any(self.rgba[:,:,3] > 0):
            raise ValueError('symbol has no visible pixels')
        for pt in self.anchors_normalized.values():
            if len(pt) != 2 or not all(math.isfinite(v) for v in pt):
                raise ValueError('invalid normalized anchor')


def load_asset_png(path: str | Path, family: str, variant: str = 'unspecified',
                   anchors_normalized: dict | None = None) -> SymbolAsset:
    # imread returns BGRA; convert to RGBA before use.
    raw = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if raw is None:
        raise FileNotFoundError(path)
    if raw.ndim == 2:
        rgba = cv2.cvtColor(raw,cv2.COLOR_GRAY2RGBA)
        rgba[:,:,3] = 255 - raw
    elif raw.shape[2] == 3:
        rgba = cv2.cvtColor(raw,cv2.COLOR_BGR2RGBA)
        gray = cv2.cvtColor(raw,cv2.COLOR_BGR2GRAY)
        rgba[:,:,3] = 255 - gray
    elif raw.shape[2] == 4:
        rgba = cv2.cvtColor(raw,cv2.COLOR_BGRA2RGBA)
    else:
        raise ValueError('invalid image channels')
    anchors = anchors_normalized or {'visual_center':(0.5,0.5)}
    return SymbolAsset(family,variant,rgba,anchors)


def generate_sheet(assets: list[SymbolAsset], *, width: int, height: int,
                   count: int, seed: int, instance_scale: tuple[float,float] = (.5,2.),
                   add_background_lines: bool = True) -> tuple[np.ndarray, list[dict]]:
    """Produce raster image and exact per-instance coordinates.

    `instance_scale` expresses symbol growth relative to each asset's original
    pixel dimensions, not original page resolution. Scale/orientation sampled
    independently for each object. Instances stay fully inside the sheet and
    avoid severe overlap to retain unequivocal training boxes.
    """
    if width < 64 or height < 64 or count < 0 or not assets:
        raise ValueError('invalid sheet size / count / assets')
    lo, hi = instance_scale
    if lo <= 0 or hi < lo:
        raise ValueError('invalid scale range')
    rng = np.random.default_rng(seed)
    page = np.full((height,width,3),255,np.uint8)
    if add_background_lines:
        # Lightweight plan-like distractors; not a real distribution emulator.
        for _ in range(int((width*height)**.5/30)):
            x0, y0 = int(rng.integers(0,width)), int(rng.integers(0,height))
            dx, dy = (int(rng.integers(-width//5,width//5+1)),0) if rng.random()<.5 else (0,int(rng.integers(-height//5,height//5+1)))
            cv2.line(page,(x0,y0),(x0+dx,y0+dy),(int(rng.integers(25,140)),)*3,int(rng.integers(1,4)),cv2.LINE_AA)
    output = []
    occupied: list[tuple[float,float,float,float]] = []
    for index in range(count):
        asset = assets[int(rng.integers(0,len(assets)))]
        ah, aw = asset.rgba.shape[:2]
        s = float(np.exp(rng.uniform(math.log(lo), math.log(hi))))
        angle = float(rng.uniform(-180,180))
        rad = math.radians(angle)
        cs, sn = math.cos(rad)*s, math.sin(rad)*s
        # local pixel EDGE -> rotated canvas with center anchored to (0,0)
        R = np.array([[cs,-sn],[sn,cs]],np.float64)
        corners = np.array([[0,0],[aw,0],[aw,ah],[0,ah]],np.float64)
        corners_rot = (corners-np.array([aw/2,ah/2])) @ R.T
        minx,miny = corners_rot.min(axis=0)
        maxx,maxy = corners_rot.max(axis=0)
        symbol_w, symbol_h = maxx-minx,maxy-miny
        pad = 2
        if symbol_w+2*pad > width or symbol_h+2*pad > height:
            continue
        chosen = None
        for _ in range(60):
            tx = float(rng.uniform(pad-minx,width-pad-maxx))
            ty = float(rng.uniform(pad-miny,height-pad-maxy))
            # x,y in page coords = R @ (asset_pixel - asset_center) + tx,ty
            box = (tx+minx, ty+miny, tx+maxx, ty+maxy)
            if all((box[2]+2 < b[0] or b[2]+2 < box[0] or box[3]+2 < b[1] or b[3]+2 < box[1]) for b in occupied):
                chosen = tx, ty, box
                break
        if chosen is None:
            continue
        tx,ty,box = chosen
        occupied.append(box)
        affine = np.array([[R[0,0], R[0,1], tx-R[0] @ [aw/2,ah/2]],
                           [R[1,0], R[1,1], ty-R[1] @ [aw/2,ah/2]]], np.float64)
        # Render only this symbol's ROI. A 4K page with 100 tiny symbols
        # must not allocate/process 100 full 4K RGBA framebuffers.
        left=max(0,int(math.floor(box[0]))-2)
        top=max(0,int(math.floor(box[1]))-2)
        right=min(width,int(math.ceil(box[2]))+2)
        bottom=min(height,int(math.ceil(box[3]))+2)
        local=affine.copy()
        local[:,2]-=[left,top]
        rgba=cv2.warpAffine(asset.rgba,local,(right-left,bottom-top),
                            flags=cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT,
                            borderValue=(255,255,255,0))
        alpha=rgba[:,:,3:4].astype(np.float32)/255
        foreground=rgba[:,:,:3].astype(np.float32)
        target=page[top:bottom,left:right]
        target[:]=np.clip(target.astype(np.float32)*(1-alpha)+foreground*alpha,0,255).astype(np.uint8)
        anchors = {}
        for name,(u,v) in asset.anchors_normalized.items():
            src = np.array([u*aw, v*ah],np.float64)
            pt = R @ (src - [aw/2,ah/2]) + [tx,ty]
            anchors[name] = {'xy':pt.tolist(),'visible':True,'annotated':True}
        output.append({'instance_id':f'synthetic_{seed}_{index:04d}',
                       'symbol_family':asset.family,'symbol_variant':asset.variant,
                       'bbox_xyxy':list(box), 'anchors':anchors,
                       'rotation_degrees_image':angle,
                       'source':{'kind':'synthetic_composite','label_method':'known_placement_transform'}})
    return page, output


def write_sheet(path: str | Path, image: np.ndarray, instances: list[dict], *, group: str, seed: int) -> Path:
    """Persist image + paired annotations and provenance. No OCR."""
    path = Path(path)
    path.parent.mkdir(parents=True,exist_ok=True)
    if not cv2.imwrite(str(path),cv2.cvtColor(image,cv2.COLOR_RGB2BGR)):
        raise IOError(f'failed to write {path}')
    metadata = {'format_version':1,'image_path':path.name,'image_width':image.shape[1],
                'image_height':image.shape[0], 'source_type':'synthetic_cad_composite',
                'project_family_id':group, 'source_group_verified':True,
                'calibration_verified':True, 'generator_seed':seed,
                'instances':instances}
    out = path.with_suffix('.json')
    out.write_text(json.dumps(metadata,indent=2))
    return out

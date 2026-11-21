"""Render verified SVG/PDF geometry at LOWER source resolution, preserving labels.

No training. This is distinct from shrinking/re-expanding raster images: the
vector renderer produces genuinely sparse, antialiased pixels at target DPI.

python scripts_render_vector_variants.py source_labels.json source.svg out/ \\
    --fractions 0.75,0.5,0.25 --aligned-verified

`source_labels.json` bounding boxes MUST already be in the source raster's
continuous pixel-edge frame and aligned to the vector page viewport. Never
infer a CAD/PDF export transform from matching nominal page dimensions.
"""
from __future__ import annotations
import argparse
import copy
import json
from pathlib import Path
import cv2
import numpy as np


def _render(path: Path, w: int, h: int, *, page: int) -> np.ndarray:
    if path.suffix.lower() == '.svg':
        import cairosvg
        png = cairosvg.svg2png(url=str(path.resolve()), output_width=w, output_height=h)
        array = np.frombuffer(png, dtype=np.uint8)
        image = cv2.imdecode(array, cv2.IMREAD_UNCHANGED)
        if image is None:
            raise RuntimeError('SVG rasterization failed')
        if image.ndim == 3 and image.shape[2] == 4:
            alpha = image[:, :, 3:4].astype(np.float32) / 255
            image = (image[:, :, :3].astype(np.float32) * alpha + 255 * (1 - alpha)).round().astype(np.uint8)
        return cv2.cvtColor(image, cv2.COLOR_GRAY2BGR) if image.ndim == 2 else image
    if path.suffix.lower() == '.pdf':
        import fitz
        with fitz.open(str(path)) as doc:
            if not 0 <= page < len(doc):
                raise ValueError('page index out of range')
            p = doc[page]
            pix = p.get_pixmap(matrix=fitz.Matrix(w / p.rect.width, h / p.rect.height),
                                  colorspace=fitz.csRGB, alpha=False)
            # Renderer rounding may produce +/-1 px; enforce the declared viewport.
            a = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width, pix.n)
            img = cv2.cvtColor(a, cv2.COLOR_RGB2BGR)
            return cv2.resize(img, (w, h), interpolation=cv2.INTER_AREA) if img.shape[:2] != (h,w) else img
    raise ValueError('only SVG or PDF vectors supported; DXF must be exported with a verified transform first')


def render_variants(annotation: Path, vector: Path, output: Path, *,
                    fractions: tuple[float,...] = (.75,.5,.25),
                    aligned_verified: bool = False, page: int = 0) -> list[Path]:
    if not aligned_verified:
        raise ValueError('explicit --aligned-verified required: vector and annotation raster must share viewport')
    data = json.loads(annotation.read_text())
    ow, oh = int(data['image_width']), int(data['image_height'])
    if min(ow,oh) < 10 or not isinstance(data.get('instances'),list):
        raise ValueError('invalid annotation canvas or instances')
    if not vector.is_file() or not (annotation.parent/data['image_path']).is_file():
        raise FileNotFoundError('vector source or paired raster missing')
    if not fractions or any(not 0.05 <= f <= 1 for f in fractions) or len(set(fractions)) != len(fractions):
        raise ValueError('fractions must be unique and between .05 and 1')
    output.mkdir(parents=True,exist_ok=True)
    results=[]
    for i, f in enumerate(fractions):
        w,h=max(2,round(ow*f)),max(2,round(oh*f))
        sx,sy=w/ow,h/oh
        image=_render(vector,w,h,page=page)
        if image.shape[:2]!=(h,w):
            raise ValueError('vector renderer returned unexpected viewport')
        stem=f'vector_{i:02d}_f{f:.3f}'.replace('.','p')
        image_file=output/f'{stem}.png'
        record_file=output/f'{stem}.json'
        if image_file.exists() or record_file.exists():
            raise FileExistsError('refusing to overwrite existing render variants')
        if not cv2.imwrite(str(image_file),image):
            raise RuntimeError('image write failed')
        new=copy.deepcopy(data)
        new['image_path']=image_file.name
        new['image_width'],new['image_height']=w,h
        new['source_type']='vector_rerender'
        new['calibration_verified']=True
        new['variant_origin']={'original_annotation':str(annotation.resolve()),
                               'source_vector':str(vector.resolve()),'source_group':data.get('project_family_id'),
                               'requested_fraction':f,'actual_scale_xy':[sx,sy],
                               'source_viewport_alignment':'user_verified'}
        for inst in new['instances']:
            if 'bbox_xyxy' in inst:
                a,b,c,d=inst['bbox_xyxy']
                inst['bbox_xyxy']=[a*sx,b*sy,c*sx,d*sy]
            for anchor in inst.get('anchors',{}).values():
                if isinstance(anchor,dict) and 'xy' in anchor:
                    anchor['xy']=[anchor['xy'][0]*sx,anchor['xy'][1]*sy]
        for primitive in new.get('context_primitives',[]):
            if 'points_xy' in primitive:
                primitive['points_xy']=[[pt[0]*sx,pt[1]*sy] for pt in primitive['points_xy']]
        record_file.write_text(json.dumps(new,indent=2))
        results.append(record_file)
    return results


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('annotation',type=Path)
    p.add_argument('vector',type=Path)
    p.add_argument('output',type=Path)
    p.add_argument('--fractions',default='0.75,0.5,0.25')
    p.add_argument('--aligned-verified',action='store_true')
    p.add_argument('--page',type=int,default=0)
    args=p.parse_args()
    created=render_variants(args.annotation,args.vector,args.output,
                            fractions=tuple(float(x) for x in args.fractions.split(',')),
                            aligned_verified=args.aligned_verified,page=args.page)
    print(json.dumps([str(x) for x in created],indent=2))


if __name__=='__main__':main()

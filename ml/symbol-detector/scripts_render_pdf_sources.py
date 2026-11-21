"""Rasterize real scanned/vector PDF pages for UNLABELED blueprint pretraining.

Does not invent object labels. The same verified original project ID is applied
across all pages/exports to avoid split leakage. No assumed PDF vectors.

python scripts_render_pdf_sources.py plan_set.pdf pdf_rasters/ \\
  --project-group ORIGINAL_PROJECT_ID --dpi 200 --max-pixels 50000000

The renderer chooses at least the requested DPI, and optionally enough source
pixels to retain the largest embedded image's native detail when possible.
The cap avoids accidental multi-gigabyte allocations on enormous sheets.
Output `unlabeled_sources.json` can feed scripts_prepare_ssl_manifest.py.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
from pathlib import Path
import numpy as np
import cv2


def render_pdf_pages(source: Path, output: Path, group: str, *, dpi: float = 200,
                     max_pixels: int = 50_000_000, preserve_embedded: bool=True) -> list[dict]:
    if not source.is_file() or not group.strip() or not 36 <= dpi <= 1200 or max_pixels<100_000:
        raise ValueError('invalid PDF source/rendering arguments')
    if output.exists() and any(output.iterdir()):
        raise FileExistsError('refusing to overwrite nonempty PDF raster folder')
    try: import fitz
    except ImportError as e:raise RuntimeError('install pymupdf to render PDF sources') from e
    digest=hashlib.sha256(source.read_bytes()).hexdigest()[:12]
    rows=[]
    with fitz.open(source) as pdf:
        if not len(pdf):raise ValueError('PDF has no pages')
        output.mkdir(parents=True,exist_ok=True)
        for n,page in enumerate(pdf):
            scale=float(dpi/72.)
            if preserve_embedded:
                # Pixels per PDF point, estimated from images actually placed
                # on this page. Avoid downsampling a high-resolution scan just
                # because its embedded PDF page dimensions are large.
                for item in page.get_image_info():
                    x0,y0,x1,y1=item['bbox']
                    if x1>x0 and y1>y0:
                        scale=max(scale, float(item['width'])/(x1-x0),
                                  float(item['height'])/(y1-y0))
            w,h=page.rect.width,page.rect.height
            cap=math.sqrt(max_pixels/(w*h))
            actual_scale=min(scale,cap)
            if actual_scale<=0:raise ValueError('invalid PDF dimensions')
            pix=page.get_pixmap(matrix=fitz.Matrix(actual_scale,actual_scale),
                                colorspace=fitz.csRGB,alpha=False)
            arr=np.frombuffer(pix.samples,dtype=np.uint8).reshape(pix.height,pix.width,pix.n)
            image=cv2.cvtColor(arr,cv2.COLOR_RGB2BGR)
            path=output/f'{digest}_page_{n:05d}.png'
            if not cv2.imwrite(str(path),image):
                raise OSError(f'could not write PDF raster: {path}')
            rows.append({'image_path':str(path.resolve()),'project_family_id':group,
                         'source_group_verified':True,'source_pdf':str(source.resolve()),
                         'source_page_index':n,'requested_dpi':dpi,
                         'actual_dpi':actual_scale*72,
                         'render_resolution':[pix.width,pix.height],
                         'resolution_capped':actual_scale<scale})
    (output/'unlabeled_sources.json').write_text(json.dumps(rows,indent=2))
    return rows


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('pdf',type=Path)
    p.add_argument('output',type=Path)
    p.add_argument('--project-group',required=True)
    p.add_argument('--dpi',type=float,default=200)
    p.add_argument('--max-pixels',type=int,default=50_000_000)
    p.add_argument('--no-embedded-resolution',action='store_true')
    args=p.parse_args()
    records=render_pdf_pages(args.pdf,args.output,args.project_group,
                             dpi=args.dpi,max_pixels=args.max_pixels,
                             preserve_embedded=not args.no_embedded_resolution)
    print(json.dumps({'pages':len(records),'resolution_capped_pages':sum(x['resolution_capped'] for x in records),
                      'source_manifest':str((args.output/'unlabeled_sources.json').resolve()),
                      'training_performed':False},indent=2))

if __name__=='__main__':main()

"""Turn every raw source (PDF, scan, photo, TIF, WebP, DXF) into a pool of pages.

    python scripts_build_page_pool.py DATA_ROOT OUT --roles class_maps/page_roles.json --workers 6

For each page this writes an image plus a normalized page JSON with no
instances, under OUT/<role>/. Roles come from the rules file (see its
comments): `background` pages feed scripts_composite_electrical.py,
`label_queue` pages wait for proposals and human review, `asset` files are
listed in OUT/assets.json for the glyph harvester.

Re-running is incremental: a source whose output JSON already exists is
skipped. Exact duplicate rasters are dropped, keeping the first one. Blank
pages are dropped too. Everything is logged in OUT/pool_report.json.

Pixel budget: pages are rendered at --dpi (vector PDFs), and any page is
downscaled to at most --max-mp megapixels, because the 7.7 GB training box
loads whole pages during export.
"""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import re
import sys
import traceback
from collections import Counter
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

import cv2
import numpy as np

RASTER = {'.jpg', '.jpeg', '.png', '.tif', '.tiff', '.webp', '.bmp'}
VECTOR = {'.pdf', '.dxf'}
NEEDS_CONVERSION = {'.dwg'}
PHOTO_DIRS = ('field_plans_album', 'field_panels_album', 'user_scans', 'uw_cities', 'olmsted', 'library_of_congress')


def load_rules(path: Path) -> dict:
    cfg = json.loads(path.read_text())
    cfg['electrical_words'] = [w.upper() for w in cfg.get('electrical_words', [])]
    return cfg


def match_rule(rel: str, cfg: dict) -> dict | None:
    for rule in cfg['rules']:
        if fnmatch.fnmatch(rel, rule['glob']) or fnmatch.fnmatch(rel.lower(), rule['glob'].lower()):
            return rule
    return None


def _page_role(rule: dict, page: int) -> str | None:
    for key, role in (rule.get('pages') or {}).items():
        a, _, b = key.partition('-')
        if int(a) <= page <= int(b or a):
            return role
    return None


def group_for(rel: str, rule: dict) -> str:
    g = rule.get('group', 'file')
    if g.startswith('set:'):
        return g[4:]
    stem = Path(rel).stem
    if g.startswith('dir:'):
        return 'dir_' + '_'.join(rel.split('/')[:int(g[4:])])
    if g.startswith('regex:'):
        m = re.search(g[6:], stem)
        return 'rx_' + Path(rel).parent.name + '_' + (m.group(1) if m else stem)
    if g == 'series':
        s = re.sub(r'-[0-9a-f]{8}$', '', stem)
        s = re.sub(r'-\d+$', '', s)
        return 'series_' + s
    return 'file_' + re.sub(r'[^A-Za-z0-9]+', '_', str(Path(rel).with_suffix('')))[-120:]


def cap_pixels(img: np.ndarray, max_mp: float) -> tuple[np.ndarray, float]:
    h, w = img.shape[:2]
    mp = h * w / 1e6
    if mp <= max_mp:
        return img, 1.0
    s = (max_mp / mp) ** .5
    return cv2.resize(img, (max(1, int(w * s)), max(1, int(h * s))), interpolation=cv2.INTER_AREA), s


def ink_fraction(img: np.ndarray) -> float:
    # Full-resolution sample: thin drafting lines vanish in an averaged thumbnail.
    g = img if img.ndim == 2 else cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    return float((g[::2, ::2] < 160).mean())


def sniff(path: Path) -> str:
    """Real file type. Library downloads often carry the wrong extension
    (innerscene `.dxf` files include DWG R10-R12, PSD, ZIP, PDF and SVG)."""
    with path.open('rb') as f:
        head = f.read(32)
    if head.startswith(b'AC10'):
        return 'dwg'
    if head.startswith(b'%PDF'):
        return 'pdf'
    if head.startswith(b'PK'):
        return 'zip'
    if head.startswith(b'8BPS'):
        return 'raster'
    if head.startswith(b'AutoCAD Binary DXF'):
        return 'dxf'
    if head.lstrip().startswith((b'<?xml', b'<svg')):
        return 'svg'
    ext = path.suffix.lower()
    if ext in RASTER:
        return 'raster'
    if ext in ('.pdf', '.dwg'):
        return ext[1:]
    return 'dxf'


def svg_page(path: Path, max_mp: float) -> np.ndarray | None:
    import cairosvg
    png = cairosvg.svg2png(url=str(path), output_width=3000, background_color='white')
    return cv2.imdecode(np.frombuffer(png, np.uint8), cv2.IMREAD_COLOR)


def read_raster(path: Path) -> np.ndarray | None:
    img = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if img is None:
        try:
            from PIL import Image
            Image.MAX_IMAGE_PIXELS = None
            with Image.open(path) as im:
                img = cv2.cvtColor(np.array(im.convert('RGB')), cv2.COLOR_RGB2BGR)
        except Exception:
            return None
    if img.dtype != np.uint8:
        img = cv2.normalize(img, None, 0, 255, cv2.NORM_MINMAX).astype(np.uint8)
    if img.ndim == 2:
        img = cv2.cvtColor(img, cv2.COLOR_GRAY2BGR)
    elif img.shape[2] == 4:
        a = img[:, :, 3:4].astype(np.float32) / 255
        img = (img[:, :, :3] * a + 255 * (1 - a)).astype(np.uint8)
    return img


def pdf_pages(path: Path, dpi: int, max_mp: float):
    import pymupdf
    doc = pymupdf.open(path)
    for i, page in enumerate(doc):
        w_in, h_in = page.rect.width / 72, page.rect.height / 72
        d = min(dpi, (max_mp * 1e6 / max(w_in * h_in, 1e-6)) ** .5)
        pix = page.get_pixmap(dpi=max(36, int(d)), colorspace=pymupdf.csRGB, alpha=False)
        img = np.frombuffer(pix.samples, np.uint8).reshape(pix.height, pix.width, 3)
        yield i + 1, cv2.cvtColor(img, cv2.COLOR_RGB2BGR), page.get_text().upper(), int(d)


def dxf_page(path: Path, max_mp: float) -> np.ndarray | None:
    from ezdxf import recover
    from ezdxf.addons.drawing import Frontend, RenderContext, layout, pymupdf as pmb
    from ezdxf.addons.drawing.config import Configuration, BackgroundPolicy, ColorPolicy
    doc, _ = recover.readfile(str(path))
    msp = doc.modelspace()
    if not len(msp):
        return None
    backend = pmb.PyMuPdfBackend()
    cfg = Configuration(background_policy=BackgroundPolicy.WHITE, color_policy=ColorPolicy.BLACK)
    Frontend(RenderContext(doc), backend, config=cfg).draw_layout(msp)
    # Fit to a fixed D-size sheet: auto-sized pages of huge CAD models exceed
    # PyMuPDF's image limit ("Overly large image").
    from ezdxf import bbox
    ext = bbox.extents(msp, fast=True)
    landscape = not ext.has_data or ext.size.x >= ext.size.y
    w, h = (36, 24) if landscape else (24, 36)
    page = layout.Page(w, h, layout.Units.inch, margins=layout.Margins.all(.25))
    png = backend.get_pixmap_bytes(page, fmt='png', dpi=150)
    img = cv2.imdecode(np.frombuffer(png, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        return None
    # Auto-fit layouts of huge models come out enormous or tiny; normalise the long side.
    long_side = max(img.shape[:2])
    target = min(6000, int((max_mp * 1e6) ** .5))
    if long_side > target or long_side < 2000:
        s = target / long_side if long_side > target else 2000 / long_side
        img = cv2.resize(img, None, fx=s, fy=s, interpolation=cv2.INTER_AREA if s < 1 else cv2.INTER_CUBIC)
    return img


def process(src: str, rel: str, rule: dict, cfg: dict, out: str, dpi: int, max_mp: float) -> list[dict]:
    # Exceptions are returned as text: some carry PyMuPDF SWIG objects that cannot be
    # pickled back to the parent, which would hide the real error.
    try:
        return _process(src, rel, rule, cfg, out, dpi, max_mp)
    except Exception as e:
        return [{'source': rel, 'page': None, 'status': f'error: {type(e).__name__}: {str(e)[:300]}'}]


def _process(src: str, rel: str, rule: dict, cfg: dict, out: str, dpi: int, max_mp: float) -> list[dict]:
    src_p, out_p = Path(src), Path(out)
    kind = sniff(src_p)
    group = group_for(rel, rule)
    photo = any(k in rel for k in PHOTO_DIRS)
    results = []

    def emit(page_no: int | None, img: np.ndarray, role: str, extra: dict):
        img, scale = cap_pixels(img, max_mp)
        ink = ink_fraction(img)
        slug = re.sub(r'[^A-Za-z0-9]+', '_', str(Path(rel).with_suffix('')))[-140:]
        name = slug + (f'_p{page_no:03d}' if page_no else '')
        if ink < .0005:
            results.append({'source': rel, 'page': page_no, 'status': 'blank'})
            return
        folder = out_p / role
        folder.mkdir(parents=True, exist_ok=True)
        # JPEG q95 is visually equivalent for line art at a fraction of the PNG
        # size, and the training loader ignores the container format.
        fmt = '.jpg'
        img_path = folder / (name + fmt)
        ok = cv2.imwrite(str(img_path), img, [cv2.IMWRITE_JPEG_QUALITY, 95])
        if not ok:
            raise OSError(img_path)
        digest = hashlib.sha256(img_path.read_bytes()).hexdigest()
        doc = {
            'format_version': 1, 'source_id': rel.split('/')[1] if '/' in rel else rel,
            'project_family_id': group, 'source_group_verified': True,
            'document_id': name, 'page_index': (page_no or 1) - 1,
            'source_type': 'photo_or_scan' if photo else 'rendered_drawing',
            'image_path': img_path.name, 'image_sha256': digest,
            'image_width': img.shape[1], 'image_height': img.shape[0],
            'coordinate_system': 'original_page_pixels_top_left_y_down',
            'exhaustive_families': [], 'instances': [],
            'pool': {'role': role, 'source_path': rel, 'page': page_no, 'downscale': scale,
                     'ink_fraction': ink, **extra},
        }
        img_path.with_suffix('.json').write_text(json.dumps(doc, indent=1))
        results.append({'source': rel, 'page': page_no, 'status': 'ok', 'role': role,
                        'json': str(img_path.with_suffix('.json')), 'sha256': digest, 'group': group})

    base_role = rule['role']
    if kind in ('dwg', 'zip'):
        results.append({'source': rel, 'page': None, 'status': f'needs_conversion:{kind}'})
        return results
    if kind == 'pdf':
        for page_no, img, text, used_dpi in pdf_pages(src_p, dpi, max_mp):
            role = _page_role(rule, page_no) or base_role
            reason = 'rule'
            if role == 'auto_text':
                hits = [w for w in cfg['electrical_words'] if w in text]
                role = 'label_queue' if hits else 'background'
                reason = f'text:{",".join(hits[:4])}' if hits else 'text:none'
            emit(page_no, img, role, {'dpi': used_dpi, 'role_reason': reason})
    elif kind in ('dxf', 'svg'):
        img = dxf_page(src_p, max_mp) if kind == 'dxf' else svg_page(src_p, max_mp)
        if img is None:
            results.append({'source': rel, 'page': None, 'status': 'empty_dxf'})
        else:
            emit(None, img, 'background' if base_role == 'auto_text' else base_role,
                 {'role_reason': 'rule', 'real_type': kind})
    else:
        img = read_raster(src_p)
        if img is None:
            results.append({'source': rel, 'page': None, 'status': 'unreadable'})
        else:
            emit(None, img, 'background' if base_role == 'auto_text' else base_role, {'role_reason': 'rule'})
    return results


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('data_root', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--roles', type=Path, required=True)
    p.add_argument('--dpi', type=int, default=150)
    p.add_argument('--max-mp', type=float, default=24.0)
    p.add_argument('--workers', type=int, default=6)
    p.add_argument('--skip-pdfs', action='store_true',
                   help='Skip expensive full-page PDF rasterization for PDF-native SSL crop indexing')
    a = p.parse_args()
    cfg = load_rules(a.roles)
    a.output.mkdir(parents=True, exist_ok=True)
    report_path = a.output / 'pool_report.json'
    previous = json.loads(report_path.read_text()) if report_path.exists() else {'done': {}}
    done: dict = previous.get('done', {})
    assets, unconverted, jobs = [], [], []
    counts: Counter = Counter()
    for f in sorted(a.data_root.rglob('*')):
        if not f.is_file():
            continue
        rel = f.relative_to(a.data_root).as_posix()
        ext = f.suffix.lower()
        if ext not in RASTER | VECTOR | NEEDS_CONVERSION | {'.svg'}:
            continue
        if a.skip_pdfs and ext == '.pdf':
            counts['pdf_sources_native_index'] += 1
            continue
        rule = match_rule(rel, cfg)
        if rule is None:
            counts['no_rule'] += 1
            print('no rule for', rel, file=sys.stderr)
            continue
        if rule['role'] == 'skip':
            counts['skipped_by_rule'] += 1
            continue
        if rule['role'] == 'asset':
            assets.append(rel)
            continue
        if ext in NEEDS_CONVERSION:
            unconverted.append(rel)
            continue
        if rel in done and not any(r.get('status', '').startswith('error') for r in done[rel]):
            continue                      # failed files are retried on every run
        jobs.append((str(f), rel, rule))
    print(f'{len(jobs)} sources to render, {len(done)} already done, {len(assets)} assets, '
          f'{len(unconverted)} DWG waiting for conversion', flush=True)
    with ProcessPoolExecutor(max_workers=a.workers) as pool:
        futs = {pool.submit(process, s, r, rule, cfg, str(a.output), a.dpi, a.max_mp): r for s, r, rule in jobs}
        for n, fut in enumerate(as_completed(futs), 1):
            rel = futs[fut]
            try:
                done[rel] = fut.result()
            except Exception as e:  # keep going; one bad file must not stop the pool
                done[rel] = [{'source': rel, 'status': f'error: {type(e).__name__}: {e}'}]
                traceback.print_exc()
            if n % 50 == 0 or n == len(jobs):
                print(f'{n}/{len(jobs)}', flush=True)
                report_path.write_text(json.dumps({'done': done}, indent=1))
    # Global exact-duplicate removal (keep first by path order).
    seen: dict[str, str] = {}
    for rel in sorted(done):
        for r in done[rel]:
            if r.get('status', '').startswith('needs_conversion'):
                unconverted.append(rel)
            if r.get('status') != 'ok':
                counts[r.get('status', '?').split(':')[0]] += 1
                continue
            if r['sha256'] in seen and seen[r['sha256']] != r['json']:
                j = Path(r['json'])
                if j.exists():
                    doc = json.loads(j.read_text())
                    (j.parent / doc['image_path']).unlink(missing_ok=True)
                    j.unlink()
                r['status'] = 'duplicate_of:' + seen[r['sha256']]
                counts['duplicate'] += 1
                continue
            seen[r['sha256']] = r['json']
            counts['pages_' + r['role']] += 1
    (a.output / 'assets.json').write_text(json.dumps(assets, indent=1))
    (a.output / 'dwg_unconverted.json').write_text(json.dumps(unconverted, indent=1))
    report_path.write_text(json.dumps({'done': done, 'counts': counts}, indent=1))
    print(json.dumps(dict(counts), indent=1))


if __name__ == '__main__':
    main()

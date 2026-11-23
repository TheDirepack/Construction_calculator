"""Interactive PDF Annotation & Training Tool for ML System.

Provides:
  1. PDF rendering to images (PyMuPDF).
  2. Loading and editing existing export JSON annotations.
  3. Bounding box selection (axis-aligned or rotated with angle).
  4. Line-snap algorithm integration (shrinking boxes to wall centerlines).
  5. Extra snap vector addition for snapping bias.
  6. Device classification (mapping symbol families/variants to disciplines).
  7. Exporting updated JSONs in the standard training format.

Usage:
  python3 -m annotator.tool --pdf blueprint.pdf --page 1 --output annotation.json
  python3 -m annotator.tool --edit annotation.json --walls walls.json
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

try:
    import fitz
    import cv2
    import numpy as np
except ImportError:
    print("Error: PyMuPDF (fitz), OpenCV (cv2), and numpy are required.")
    sys.exit(1)

from annotator.model import Sheet, Instance, RotatedBox, SnapVector, discipline_for
from annotator.snapping import apply_snap_shrink


def render_pdf_page(pdf_path: Path, page_index: int, dpi: int = 150) -> tuple[np.ndarray, Path]:
    doc = fitz.open(str(pdf_path))
    if page_index < 0 or page_index >= len(doc):
        raise ValueError(f"Page index {page_index} out of range (0-{len(doc)-1})")
    page = doc[page_index]
    zoom = dpi / 72.0
    mat = fitz.Matrix(zoom, zoom)
    pix = page.get_pixmap(matrix=mat)
    img_data = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.h, pix.w, pix.n)
    if pix.n == 4:
        img = cv2.cvtColor(img_data, cv2.COLOR_RGBA2BGR)
    elif pix.n == 3:
        img = cv2.cvtColor(img_data, cv2.COLOR_RGB2BGR)
    else:
        img = cv2.cvtColor(img_data, cv2.COLOR_GRAY2BGR)
    
    out_dir = Path("/tmp/opencode/annotator_cache")
    out_dir.mkdir(parents=True, exist_ok=True)
    img_path = out_dir / f"{pdf_path.stem}_p{page_index}.png"
    cv2.imwrite(str(img_path), img)
    return img, img_path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", type=Path, help="PDF file to annotate")
    parser.add_argument("--page", type=int, default=0, help="Page index (0-based)")
    parser.add_argument("--edit", type=Path, help="Existing JSON annotation to edit/inspect")
    parser.add_argument("--walls", type=Path, help="JSON file containing verified wall centerlines")
    parser.add_argument("--output", type=Path, default=Path("annotation_output.json"), help="Output JSON path")
    parser.add_argument("--classify", nargs=2, metavar=("INSTANCE_ID", "FAMILY"), action="append",
                        help="Classify or reclassify a device instance (e.g. --classify fixture_001 receptacle)")
    parser.add_argument("--add-box", nargs=6, metavar=("ID", "FAMILY", "X0", "Y0", "X1", "Y1"), action="append",
                        help="Add an axis-aligned bounding box instance")
    parser.add_argument("--rotate", nargs=2, metavar=("INSTANCE_ID", "ANGLE_DEG"), action="append",
                        help="Set rotation angle for an instance")
    parser.add_argument("--snap-vector", nargs=5, metavar=("INSTANCE_ID", "OX", "OY", "DX", "DY"), action="append",
                        help="Add an extra snap vector (origin + direction) for an instance")
    parser.add_argument("--run-snap", action="store_true", help="Run line-snap and box shrinking on instances")
    args = parser.parse_args()

    # Load sheet
    if args.edit:
        if not args.edit.exists():
            print(f"Error: annotation file {args.edit} not found", file=sys.stderr)
            sys.exit(1)
        sheet = Sheet.load(args.edit)
        print(f"Loaded existing annotations from {args.edit} ({len(sheet.instances)} instances)")
    elif args.pdf:
        if not args.pdf.exists():
            print(f"Error: PDF file {args.pdf} not found", file=sys.stderr)
            sys.exit(1)
        print(f"Rendering PDF {args.pdf} page {args.page}...")
        img, img_path = render_pdf_page(args.pdf, args.page)
        rel_img_path = img_path.relative_to(Path.cwd()) if img_path.is_relative_to(Path.cwd()) else img_path
        sheet = Sheet(
            source_id=args.pdf.stem,
            document_id=args.pdf.name,
            page_index=args.page,
            image_path=str(rel_img_path),
            image_width=img.shape[1],
            image_height=img.shape[0],
            project_family_id=args.pdf.stem,
        )
        print(f"Created new sheet: {sheet.image_width}x{sheet.image_height} px")
    else:
        parser.error("Provide either --pdf or --edit")

    # Load image for snapping / preview
    img_full_path = (Path(args.edit).parent / sheet.image_path) if args.edit else Path(sheet.image_path)
    if not img_full_path.exists() and args.pdf:
        img_full_path, _ = render_pdf_page(args.pdf, args.page)
    
    img = cv2.imread(str(img_full_path)) if img_full_path.exists() else None

    # Load walls if provided
    walls = []
    if args.walls and args.walls.exists():
        wdata = json.loads(args.walls.read_text())
        if isinstance(wdata, dict):
            walls = wdata.get("walls", [])
        elif isinstance(wdata, list):
            walls = wdata
        print(f"Loaded {len(walls)} wall segments from {args.walls}")

    # Process CLI modifications
    if args.classify:
        for inst_id, family in args.classify:
            found = False
            for inst in sheet.instances:
                if inst.instance_id == inst_id:
                    inst.symbol_family = family
                    print(f"Classified {inst_id} -> family: {family}, discipline: {inst.discipline}")
                    found = True
            if not found:
                print(f"Warning: instance {inst_id} not found for classification", file=sys.stderr)

    if args.add_box:
        for inst_id, family, x0, y0, x1, y1 in args.add_box:
            box = RotatedBox.from_xyxy([float(x0), float(y0), float(x1), float(y1)])
            inst = Instance(instance_id=inst_id, symbol_family=family, box=box)
            sheet.instances.append(inst)
            print(f"Added instance {inst_id} ({family}) at [{x0}, {y0}, {x1}, {y1}]")

    if args.rotate:
        for inst_id, angle in args.rotate:
            ang = float(angle)
            for inst in sheet.instances:
                if inst.instance_id == inst_id:
                    inst.box.angle_deg = ang
                    print(f"Set rotation for {inst_id} to {ang}°")

    if args.snap_vector:
        for inst_id, ox, oy, dx, dy in args.snap_vector:
            sv = SnapVector((float(ox), float(oy)), (float(dx), float(dy)))
            for inst in sheet.instances:
                if inst.instance_id == inst_id:
                    inst.snap_vector = sv
                    print(f"Added extra snap vector to {inst_id}: origin=({ox},{oy}), dir=({dx},{dy})")

    if args.run_snap:
        if img is None:
            print("Error: raster image required for line-snap resolution", file=sys.stderr)
            sys.exit(1)
        print(f"Running line-snap and box shrinking on {len(sheet.instances)} instances with {len(walls)} walls...")
        for inst in sheet.instances:
            snap = apply_snap_shrink(inst, img, walls=walls, wall_attachable=True,
                                     snap_vector=inst.snap_vector)
            inst.snap = snap
            print(f"  Instance {inst.instance_id}: snap kind={snap.get('kind')}, note={snap.get('note')}")

    # Save output
    sheet.save(args.output)
    print(f"Successfully saved updated annotations to [annotation_output.json]({args.output.resolve()})")


if __name__ == "__main__":
    main()

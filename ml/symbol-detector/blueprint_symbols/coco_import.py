"""Convert existing COCO instance labels into our source-coordinate schema.

This reuses annotations from public datasets instead of relabeling drawings.
It does not fabricate snapping anchors. If no category matches the target
symbol taxonomy, do not pretend the dataset contains electrical devices.
"""
from __future__ import annotations
from collections import defaultdict
from pathlib import Path
import json
import math
from .label_schema import COORDINATE_SYSTEM, LABELS_SUFFIX, write_labels_file


def convert_coco(json_path: str | Path, images_root: str | Path,
                 output_folder: str | Path, class_map: dict[str,str],
                 *, project_groups: dict[str,str] | None = None,
                 include_unmapped_as_ignore: bool = True) -> dict[str,int]:
    """Convert COCO bbox xywh to per-image xyxy, retain image-level provenance.

    Writes one Format-2 labels file per image, ``image_<id:08d>_labels.json``
    (page 1; training names it ``image_00000001_labels.json#p1``).

    `class_map` is an explicit dataset class name -> requested symbol family.
    Labels not in `class_map` are retained as known-unmapped *ignore* objects
    so training does not learn to call them empty background.
    `project_groups` maps COCO image `file_name` to its original project ID.
    Without it, every image is its own group: inadequate for data where the
    same CAD source has multiple cropped images; must audit before holdout.
    """
    src=Path(json_path)
    root=Path(images_root)
    out=Path(output_folder)
    data=json.loads(src.read_text())
    categories={int(c['id']):str(c['name']) for c in data['categories']}
    images={int(im['id']):im for im in data['images']}
    instances=defaultdict(list)
    counters={'exported_images':0,'mapped_instances':0,'unmapped_instances':0,'skipped_invalid_boxes':0}
    for ann in data['annotations']:
        image_id=int(ann['image_id'])
        if image_id not in images:
            raise ValueError(f'unknown COCO image_id {image_id}')
        category=categories[int(ann['category_id'])]
        mapped=class_map.get(category)
        if mapped is None and not include_unmapped_as_ignore:
            continue
        image=images[image_id]
        x,y,w,h=map(float,ann['bbox'])
        W,H=int(image['width']),int(image['height'])
        if not all(map(math.isfinite,(x,y,w,h))) or w<=0 or h<=0:
            counters['skipped_invalid_boxes']+=1
            continue
        x0,y0,x1,y1=max(0,x),max(0,y),min(W,x+w),min(H,y+h)
        if x0>=x1 or y0>=y1:
            counters['skipped_invalid_boxes']+=1
            continue
        if mapped:
            counters['mapped_instances']+=1
        else:
            counters['unmapped_instances']+=1
        polys = ann.get('segmentation')
        # Polygon segmentations preserve exact available instance masks;
        # RLE may be retained for later COCO-aware decoding but is not a snap.
        polyseg = [list(map(float, poly)) for poly in polys if len(poly)>=6] if isinstance(polys,list) else None
        instances[image_id].append({
            'instance_id':f'coco_{image_id}_{ann["id"]}',
            'symbol_family':mapped if mapped else '__unmapped__',
            'symbol_variant':category,
            'bbox_xyxy':[x0,y0,x1,y1],
            'anchors':{}, # COCO boxes do not provide semantic snap locations.
            **({'segmentation_polygons':polyseg} if polyseg else {}),
            'uncertain':not bool(mapped) or bool(ann.get('iscrowd',0)),
            'source':{'kind':'coco_annotation','annotation_id':ann['id'],
                      'category_name':category,'label_method':'supplied_dataset_annotation'},
        })
    out.mkdir(parents=True,exist_ok=True)
    for image_id,im in images.items():
        name=str(im['file_name'])
        fpath=(root/name).resolve()
        if not fpath.is_file():
            raise FileNotFoundError(fpath)
        group=(project_groups or {}).get(name,f'unknown_group_{image_id}')
        # one Format-2 labels file per image (page 1): image_00000001_labels.json
        write_labels_file(out/f'image_{image_id:08d}{LABELS_SUFFIX}',{1:{
            'format_version':1,'image_path':str(fpath),
            'image_width':int(im['width']),'image_height':int(im['height']),
            'project_family_id':group,'source_type':'coco_public_dataset',
            'source_dataset_annotation':str(src.resolve()),
            'source_image_name':name,
            'source_group_verified':bool(project_groups and name in project_groups),
            'document_id':Path(name).stem,'page_index':0,
            'coordinate_system':COORDINATE_SYSTEM,
            'instances':instances[image_id]}},imported_by='coco')
        counters['exported_images']+=1
    return counters

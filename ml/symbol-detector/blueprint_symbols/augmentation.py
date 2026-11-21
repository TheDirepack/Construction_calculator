"""Repeatable raster degradation with pixel-perfect target transforms.

Photometric augmentations do not change coordinates. Geometric warps must
change boxes, keypoints, ignores and masks with the SAME H as the image.
Train on arbitrary source/tile sizes; model tensors remain independently sized.
"""
from __future__ import annotations
import cv2
import numpy as np

def photometric(img:np.ndarray, rng:np.random.Generator, *, jpeg:bool=True) -> np.ndarray:
    """Low-cost real-world scan/photo degradations; preserves shape and labels."""
    x=np.asarray(img)
    if x.dtype!=np.uint8:raise ValueError('expected uint8 image')
    gain=float(rng.uniform(.75,1.25));offset=float(rng.uniform(-18,18))
    out=np.clip(x.astype(np.float32)*gain+offset,0,255).astype(np.uint8)
    if rng.random()<.40:
        k=int(rng.choice([3,5]));out=cv2.GaussianBlur(out,(k,k),rng.uniform(.3,1.2))
    if rng.random()<.30:
        noise=rng.normal(0,rng.uniform(1,8),out.shape)
        out=np.clip(out.astype(np.float32)+noise,0,255).astype(np.uint8)
    if jpeg and rng.random()<.55:
        q=int(rng.integers(38,96));ok,b=cv2.imencode('.jpg',out,[cv2.IMWRITE_JPEG_QUALITY,q])
        if ok:out=cv2.imdecode(b,cv2.IMREAD_UNCHANGED)
    return out

def perspective_warp(img:np.ndarray, instances:list[dict], H:np.ndarray) -> tuple[np.ndarray,list[dict]]:
    """Apply known source->output homography; all labels and anchors follow.

    Clipped or projectively distorted instances are marked uncertain so their
    original exact CAD anchors aren't incorrectly treated as complete objects.
    """
    import copy
    h,w=img.shape[:2]
    matrix=np.asarray(H,dtype=np.float64)
    if matrix.shape!=(3,3) or not np.isfinite(matrix).all() or abs(np.linalg.det(matrix))<1e-10:
        raise ValueError('invalid warp H')
    out=cv2.warpPerspective(img,matrix,(w,h),borderValue=(255,255,255))
    def pt(p):
        a=matrix@np.array([p[0],p[1],1.])
        if abs(a[2])<1e-8:raise ValueError('projected point at infinity')
        return [float(a[0]/a[2]),float(a[1]/a[2])]
    labels=[]
    for original in instances:
        inst=copy.deepcopy(original)
        x0,y0,x1,y1=map(float,inst['bbox_xyxy'])
        corners=[pt((x,y)) for x in (x0,x1) for y in (y0,y1)]
        xs,ys=zip(*corners)
        box=[max(0,min(xs)),max(0,min(ys)),min(w,max(xs)),min(h,max(ys))]
        if box[2]<=box[0] or box[3]<=box[1]:continue
        if min(xs)<0 or min(ys)<0 or max(xs)>w or max(ys)>h:
            inst['uncertain']=True
        inst['bbox_xyxy']=box
        for anchor in inst.get('anchors',{}).values():
            if anchor.get('annotated',False) and 'xy' in anchor:
                anchor['xy']=pt(anchor['xy'])
                anchor['visible']=bool(0<=anchor['xy'][0]<w and 0<=anchor['xy'][1]<h)
        if 'segmentation_polygons' in inst:
            inst['segmentation_polygons']=[[c for pair in (pt(p[i:i+2]) for i in range(0,len(p),2)) for c in pair]
                                            for p in inst['segmentation_polygons']]
        labels.append(inst)
    return out,labels

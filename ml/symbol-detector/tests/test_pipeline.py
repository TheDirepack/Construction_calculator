import cv2
import numpy as np
import torch
from blueprint_symbols.models import SymbolDetector, AnchorRefiner, parameter_counts
from blueprint_symbols.tiles import tile_page, page_px_to_pdf_points
from blueprint_symbols.geometry import fit_isolated_stroke_centerline, closest_point_on_segment, heatmap_peak


def test_detector_shapes_and_param_budget():
    with torch.no_grad():
        d = SymbolDetector(14).eval()
        outs = d(torch.zeros(1, 3, 128, 128))
    assert [tuple(x.shape) for x in outs] == [(1, 19, 32, 32), (1, 19, 16, 16), (1, 19, 8, 8)]
    assert parameter_counts()['symbol_detector'] < 1_000_000


def test_refiner_shapes_and_param_budget():
    with torch.no_grad():
        model = AnchorRefiner(4).eval()
        out = model(torch.zeros(1, 3, 160, 160))
    assert tuple(out.shape) == (1, 5, 40, 40)
    assert parameter_counts()['anchor_refiner'] < 100_000


def test_tiles_cover_right_bottom_without_huge_bitmap():
    tiles = tile_page(10800, 7200, 512, 96)
    assert len(tiles) == 468
    assert tiles[0].x == 0 and tiles[0].y == 0
    assert max(t.x + t.width for t in tiles) == 10800
    assert max(t.y + t.height for t in tiles) == 7200
    assert tiles[-1].to_page_point(0, 0) == (10288, 6688)
    assert page_px_to_pdf_points(300, 600, 300) == (72, 144)


def test_isolated_horizontal_and_vertical_thick_stroke_subpixel_center():
    im = np.full((128, 128), 255, np.uint8)
    cv2.line(im, (15, 61), (110, 61), 0, 5, cv2.LINE_AA)
    fitted = fit_isolated_stroke_centerline(im)
    assert fitted is not None and fitted.anisotropy > 20
    assert abs((fitted.start[1]+fitted.end[1])/2 - 61) < .25
    im2 = np.full((128, 128), 255, np.uint8)
    cv2.line(im2, (63, 10), (63, 114), 0, 3, cv2.LINE_AA)
    fitted2 = fit_isolated_stroke_centerline(im2)
    assert fitted2 is not None
    assert abs((fitted2.start[0]+fitted2.end[0])/2 - 63) < .25


def test_snap_and_heatmap_peak():
    assert closest_point_on_segment((6, 4), (0, 0), (10, 0)) == (6, 0)
    h = np.zeros((40, 40), np.float32)
    h[11, 19] = 1.0
    assert heatmap_peak(h, 160, 160) == (78, 46)

from blueprint_symbols.postprocess import Detection, decode_heads, to_page, merge_tiles, iou
from blueprint_symbols.crops import crop_for_refiner


def test_decode_known_logits_and_tile_deduplication():
    heads = [np.full((1,7,32//(2**i),32//(2**i)), -20, dtype=np.float32) for i in range(3)]
    # 2 classes => 1 objectness + 2 classes + 4 box channels = 7.
    h = heads[0]
    h[0,0,3,4] = 12  # objectness
    h[0,2,3,4] = 12  # class 1
    h[0,3,3,4] = 0   # dx: sigmoid(0)=0.5
    h[0,4,3,4] = 0   # dy: sigmoid(0)=0.5
    h[0,5,3,4] = np.log(8)
    h[0,6,3,4] = np.log(4)
    hits = decode_heads(tuple(heads), (128,128), .5)
    assert len(hits) == 1
    assert hits[0].class_id == 1
    assert np.allclose(hits[0].box, (14,12,22,16), atol=1e-5)
    p1 = to_page(hits, tile_page(512,512,128,64)[1], tile_id=1)[0]
    p2 = Detection(1,.8,p1.box, tile_id=2)
    assert len(merge_tiles([p1,p2])) == 1
    assert iou((0,0,10,10),(5,5,15,15)) == 25/175
    assert len(merge_tiles([p1,Detection(0,.8,p1.box)])) == 2


def test_crop_transform_and_boundary_padding():
    im = np.full((60,90),255,np.uint8)
    cv2.circle(im,(11,9),3,0,1)
    patch, transform = crop_for_refiner(im, (4,2,18,16), size=160)
    assert patch.shape == (160,160)
    assert np.min(patch)<128
    x,y = transform.to_page(80,80)
    assert abs(x-11)<1e-5 and abs(y-9)<1e-5
    edge, transform2 = crop_for_refiner(im, (0,0,5,5), size=160)
    assert edge.shape == (160,160)
    assert transform2.origin_x < 0

from blueprint_symbols.training import detector_loss


def test_training_loss_backward_and_density_warning():
    torch.manual_seed(1)
    model = SymbolDetector(3)
    outputs = model(torch.zeros((1,3,128,128)))
    gt = [[(1,(20.,18.,36.,32.)), (0,(72.,68.,90.,84.))]]
    loss, stats = detector_loss(outputs, gt, (128,128))
    assert stats == {'assigned':2, 'collisions':0}
    assert torch.isfinite(loss)
    loss.backward()
    assert model.head2[-1].weight.grad is not None
    assert torch.isfinite(model.head2[-1].weight.grad).all()
    # Same center feature-grid cell cannot represent two separate objects.
    _, stats2 = detector_loss(outputs, [[(0,(20.,18.,36.,32.)),(1,(21.,18.,35.,32.))]], (128,128))
    assert stats2['collisions'] == 1

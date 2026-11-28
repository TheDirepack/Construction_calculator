"""Training-data generation against false detections: symbol sizes, panelboard
context, procedural hard negatives on verified-blank composites, per-document
blank balance, seeds, and mined hard-negative windows in the tile export."""
from __future__ import annotations

import hashlib
import json
from collections import Counter
from pathlib import Path

import cv2
import numpy as np
import pytest

from blueprint_symbols.neca_svg_compositor import (
    HARD_NEGATIVE_KINDS,
    NecaGlyph,
    composite_neca_page,
    draw_hard_negatives,
    sample_symbol_px,
)


def _glyph(family: str, variant: str, alpha: np.ndarray, device_type: str | None = None) -> NecaGlyph:
    return NecaGlyph(f'{family}.{variant}', '', variant, 'test', 'other_device',
                     device_type or family, variant, alpha, family, variant)


def _ring() -> np.ndarray:
    a = np.zeros((48, 48), np.uint8)
    cv2.circle(a, (24, 24), 16, 255, 3)
    cv2.line(a, (8, 24), (40, 24), 255, 3)
    return a


class _Bank:
    def __init__(self, glyphs):
        self.glyphs = glyphs

    def sample(self, rng):
        return self.glyphs[int(rng.integers(len(self.glyphs)))]


def _walled(h: int = 1200, w: int = 1600) -> np.ndarray:
    img = np.full((h, w, 3), 255, np.uint8)
    cv2.rectangle(img, (80, 80), (w - 80, h - 80), (20, 20, 20), 8)
    cv2.line(img, (700, 80), (700, h - 80), (20, 20, 20), 6)
    cv2.line(img, (700, 600), (w - 80, 600), (20, 20, 20), 6)
    return img


# --------------------------------------------------------------------------- C2 sizes

def test_size_sampler_covers_20_to_140_px_with_real_weight_on_large_symbols():
    rng = np.random.default_rng(7)
    sizes = np.array([sample_symbol_px(rng, 20.0, 140.0, large_px=90.0, large_share=0.35)
                      for _ in range(20000)])
    assert sizes.min() >= 20.0 and sizes.max() <= 140.0
    assert sizes.min() < 21.0 and sizes.max() > 138.0          # the whole range is reached
    assert 0.32 <= float((sizes >= 90).mean()) <= 0.38          # meaningful 90-140 px share
    assert float((sizes > 100).mean()) >= 0.20                  # not the old ~5%
    assert float((sizes < 48).mean()) >= 0.30                   # small symbols stay common


def test_size_sampler_without_large_share_is_the_old_single_draw():
    a, b = np.random.default_rng(3), np.random.default_rng(3)
    for _ in range(50):
        old = float(np.exp(b.uniform(np.log(20.0), np.log(64.0))))
        assert sample_symbol_px(a, 20.0, 64.0) == old


def test_composite_places_large_symbols_at_absolute_page_pixels():
    img = np.full((1600, 1600, 3), 255, np.uint8)
    big = np.zeros((128, 128), np.uint8)
    cv2.circle(big, (64, 64), 56, 255, 6)
    _, placed = composite_neca_page({'instances': []}, img, _Bank([_glyph('luminaire', 'downlight', big)]),
                                    np.random.default_rng(11), per_page=60, wall_fraction=0.0,
                                    size_px_range=(20.0, 140.0), large_px=90.0, large_share=0.35,
                                    absolute_size=True, max_upscale=3.0)
    sides = [max(p['bbox_xyxy'][2] - p['bbox_xyxy'][0], p['bbox_xyxy'][3] - p['bbox_xyxy'][1]) for p in placed]
    assert len(sides) >= 40
    assert max(sides) <= 141 and sum(s >= 90 for s in sides) >= 0.2 * len(sides)


# --------------------------------------------------------------------------- C3 hard negatives

def _composite(doc, seed=5, hard_negatives=0, log=None, glyphs=None):
    return composite_neca_page(doc, _walled(), _Bank(glyphs or [_glyph('receptacle', 'duplex', _ring())]),
                               np.random.default_rng(seed), per_page=16, wall_fraction=0.6,
                               size_px_range=(20.0, 140.0), large_px=90.0, large_share=0.35,
                               absolute_size=True, hard_negatives=hard_negatives, distractor_log=log)


def test_distractors_only_on_verified_blank_backgrounds_and_never_touch_symbols():
    plain, placed0 = _composite({'instances': [], 'verified_no_symbols': True})
    log: list[dict] = []
    with_hn, placed1 = _composite({'instances': [], 'verified_no_symbols': True}, hard_negatives=20, log=log)
    assert placed0 == placed1                       # labels are unchanged by the distractors
    assert len(log) >= 10
    assert len({d['kind'] for d in log}) >= 4
    assert not np.array_equal(plain, with_hn)
    for p in placed1:                               # symbol pixels (plus margin) untouched
        x0, y0, x1, y1 = (int(round(v)) for v in p['bbox_xyxy'])
        assert np.array_equal(plain[max(0, y0 - 6):y1 + 6, max(0, x0 - 6):x1 + 6],
                              with_hn[max(0, y0 - 6):y1 + 6, max(0, x0 - 6):x1 + 6])
        for d in log:
            a = d['bbox_xyxy']
            if d['parts'] == 1:                     # single-piece distractors: whole box clear
                assert a[2] + 6 < x0 or x1 + 6 < a[0] or a[3] + 6 < y0 or y1 + 6 < a[1]

    # Unreviewed (positive-only) backgrounds: unlabelled ink could hide real
    # devices' look-alikes, and nothing is verified background -> no distractors.
    for doc in ({'instances': [], 'verified_no_symbols': False}, {'instances': []}):
        log2: list[dict] = []
        img2, _ = _composite(doc, hard_negatives=20, log=log2)
        ref2, _ = _composite(doc)
        assert log2 == [] and np.array_equal(img2, ref2)


@pytest.mark.parametrize('kind', sorted(HARD_NEGATIVE_KINDS))
def test_every_distractor_kind_draws(kind):
    from blueprint_symbols.wall_alignment import WallSite
    page = np.full((900, 900, 3), 255, np.uint8)
    sites = [WallSite(450.0, 100.0, 0.0, 1.0), WallSite(100.0, 450.0, 1.0, 0.0)]
    log = draw_hard_negatives(page, np.random.default_rng(1), count=4, avoid=[], wall_sites=sites,
                              kinds={kind: 1.0})
    assert log and all(d['kind'] == kind for d in log)
    assert page.min() < 200
    if kind == 'dash_row':                          # rows of equal dashes, never a lone bar
        assert all(d['parts'] >= 3 for d in log)


def test_panelboards_get_tags_and_a_lower_wall_share():
    bar = np.full((20, 70), 255, np.uint8)
    glyphs = [_glyph('panelboard', 'panelboard', bar)]
    placed = []
    for seed in range(6):
        _, got = composite_neca_page({'instances': []}, _walled(), _Bank(glyphs), np.random.default_rng(seed),
                                     per_page=12, wall_fraction=0.6, size_px_range=(40.0, 90.0),
                                     absolute_size=True)
        placed += got
    assert len(placed) >= 40
    wall = [p['on_wall'] for p in placed]
    tags = [p['source']['context_tag'] for p in placed]
    assert 0.25 <= np.mean(wall) <= 0.65            # was ~0.9 with the 5:1 wall odds
    assert np.mean([t is not None for t in tags]) >= 0.6
    for p, t in zip(placed, tags):
        if t is None:
            continue
        a, b = p['bbox_xyxy'], t['bbox_xyxy']
        assert b[2] <= a[0] or a[2] <= b[0] or b[3] <= a[1] or a[3] <= b[1]   # tag is outside the box
        assert t['text'].strip()
    # other devices keep the placement policy
    _, other = composite_neca_page({'instances': []}, _walled(), _Bank([_glyph('receptacle', 'duplex', _ring())]),
                                   np.random.default_rng(0), per_page=12, wall_fraction=0.6,
                                   size_px_range=(30.0, 60.0), absolute_size=True)
    assert all(p['source']['context_tag'] is None for p in other)


# --------------------------------------------------------------------------- C4 document balance

def test_verified_blank_draws_balance_documents():
    from scripts_composite_neca_svg import balanced_document_draws
    items = [('big', i) for i in range(100)] + [('small', i) for i in range(4)] + [('one', 0)]
    draws = balanced_document_draws(items, 60, seed=1, key=lambda it: it[0])
    by_doc = Counter(d[0] for d in draws)
    assert len(draws) == 60
    # sqrt(pages) shares 10 : 2 : 1 -> ~46 / 9 / 5 (uniform over pages gave ~57 / 2 / 1)
    assert by_doc['big'] <= 47
    assert by_doc['small'] >= 9 and by_doc['one'] >= 4
    assert len({d for d in draws if d[0] == 'small'}) == 4          # every small page used
    assert draws == balanced_document_draws(items, 60, seed=1, key=lambda it: it[0])
    assert draws != balanced_document_draws(items, 60, seed=2, key=lambda it: it[0])


def test_real_blank_tiles_capped_per_document():
    from scripts_export_yolo_tiles import real_blank_tiles_per_record
    groups = ['big'] * 100 + ['small'] * 2
    tiles = real_blank_tiles_per_record(groups, 64, 6 * 64)
    assert tiles[-2:] == [64, 64]
    assert set(tiles[:100]) == {8}                  # floor per page; 800 tiles, not 6400
    assert real_blank_tiles_per_record(groups, 64, 0) == [64] * 102


# --------------------------------------------------------------------------- export: seeds, hard negatives

def _sheet(path: Path, seed: int, symbol: bool) -> None:
    rng = np.random.default_rng(seed)
    img = np.full((900, 900, 3), 246, np.uint8)
    for _ in range(10):
        p1 = tuple(int(v) for v in rng.integers(30, 870, 2))
        p2 = tuple(int(v) for v in rng.integers(30, 870, 2))
        cv2.line(img, p1, p2, (25, 25, 25), int(rng.integers(2, 5)))
    for k in range(4):
        cv2.rectangle(img, (500 + 30 * k, 600), (560 + 30 * k, 620), (10, 10, 10), -1)   # a bold dash row
    if symbol:
        cv2.circle(img, (260, 260), 16, (0, 0, 0), 2)
    assert cv2.imwrite(str(path), img)


def _manifest(tmp_path: Path) -> tuple[Path, Path]:
    from blueprint_symbols.dataset_manifest import build_manifest
    paths = []

    def add(name, group, *, source_type, blank, complete):
        img = tmp_path / f'{name}.png'
        _sheet(img, len(paths) + 1, not blank)
        inst = [] if blank else [{
            'instance_id': f'{name}_0', 'symbol_family': 'receptacle', 'symbol_variant': 'duplex',
            'bbox_xyxy': [242.0, 242.0, 278.0, 278.0], 'uncertain': False, 'review_status': 'accepted',
            'review_source': 'human', 'verified_ground_truth': True}]
        doc = {'image_path': img.name, 'image_width': 900, 'image_height': 900, 'project_family_id': group,
               'document_id': f'{name}.pdf', 'page_index': 0, 'source_group_verified': True,
               'source_type': source_type, 'exhaustive_families': ['receptacle'] if (complete or blank) else [],
               'annotation_completeness_verified': bool(complete and not blank),
               'verified_no_symbols': blank, 'instances': inst}
        if 'synthetic' in source_type or 'neca' in source_type:
            doc['wall_only_background_verified'] = True
            doc['synthetic_targets_only'] = True
        ann = tmp_path / f'{name}.json'
        ann.write_text(json.dumps(doc))
        paths.append(ann)
        return img

    blank_img = add('real_blank', 'g-blank', source_type='real_construction_pdf_manual', blank=True, complete=False)
    add('real_comp', 'g-comp', source_type='real_construction_pdf_manual', blank=False, complete=True)
    for s in range(6):
        add(f'synth_{s}', f'g-synth-{s}', source_type='blueprint_with_neca_svg_devices', blank=False, complete=True)
    mf = build_manifest(paths, val_fraction=.25, test_fraction=.25, allow_partial_training=True,
                        evaluation_files=[p for p in paths if p.stem in ('synth_0', 'synth_1', 'synth_2', 'synth_3')])
    mf_path = tmp_path / 'source_manifest.json'
    mf_path.write_text(json.dumps(mf))
    return mf_path, blank_img


def _export(mf, out, seed=2026, **kw):
    from scripts_export_yolo_tiles import run
    return run(mf, out, samples=3, train_variants=2, seed=seed, allow_partial_training=True,
               real_blank_tiles_per_page=3, eval_empty_keep_fraction=1., eval_blank_keep_fraction=1., **kw)


def _train_digest(out: Path) -> str:
    digest = hashlib.sha256()
    for f in sorted((out / 'images' / 'train').glob('*.png')):
        digest.update(f.name.encode() + f.read_bytes())
    return digest.hexdigest()


def test_export_is_deterministic_per_seed_and_resamples_with_a_new_seed(tmp_path, monkeypatch):
    monkeypatch.delenv('PHASE2_HARD_NEGATIVES', raising=False)
    mf, _ = _manifest(tmp_path)
    _export(mf, tmp_path / 'a', seed=11)
    _export(mf, tmp_path / 'b', seed=11)
    _export(mf, tmp_path / 'c', seed=12)
    assert _train_digest(tmp_path / 'a') == _train_digest(tmp_path / 'b')
    assert _train_digest(tmp_path / 'a') != _train_digest(tmp_path / 'c')


def test_mined_windows_become_verified_empty_train_tiles_only_on_train_blank_pages(tmp_path, monkeypatch):
    from blueprint_symbols.partial_supervision import read_supervision
    from scripts_export_yolo_tiles import load_hard_negative_windows
    from scripts_mine_hard_negatives import windows_from_detections, write_windows

    mf, blank_img = _manifest(tmp_path)
    sha = hashlib.sha256(blank_img.read_bytes()).hexdigest()
    page = {'page_ref': 'x_labels.json#p1', 'image_path': str(blank_img), 'image_sha256': sha,
            'document_id': 'real_blank.pdf', 'project_family_id': 'g-blank', 'page_index': 0}
    dets = [{'symbol_family': 'panelboard.panelboard', 'confidence': .66, 'bbox_xyxy': [500, 600, 560, 620]},
            {'symbol_family': 'panelboard.panelboard', 'confidence': .41, 'bbox_xyxy': [560, 600, 620, 620]},
            {'symbol_family': 'luminaire.high_bay', 'confidence': .07, 'bbox_xyxy': [40, 40, 70, 70]}]
    wins = windows_from_detections(dets, 900, 900)
    assert len(wins) == 2 and len(wins[0]['detections']) == 2          # the dash row is one window
    assert all(w['window_xyxy'][2] - w['window_xyxy'][0] == 512 for w in wins)
    stray = {**page, 'image_path': str(tmp_path / 'missing.png'), 'image_sha256': '0' * 64}
    path = tmp_path / 'hn.json'
    write_windows(path, [page, stray], {sha: wins, '0' * 64: wins[:1]}, weights='best.pt', floor=.05)
    assert len(load_hard_negative_windows(path)) == 3

    monkeypatch.setenv('PHASE2_HARD_NEGATIVES', str(path))          # how prepare_training_data passes it
    report = _export(mf, tmp_path / 'out', hard_negative_variants=3)
    counts = report['counts']
    assert counts['train_hard_negative_windows'] == 2
    assert counts['train_hard_negative_images'] == 6
    assert counts['train_hard_negative_windows_skipped'] == 1          # unknown image: never exported
    sup = read_supervision(tmp_path / 'out' / 'data.yaml')
    names = [n for n in sup['samples'] if n.startswith('train_hn_')]
    assert len(names) == 6
    for n in names:
        assert sup['sample_roles'][n] == 'real_blank'
        assert sorted(sup['samples'][n]) == sorted(sup['class_ids'].values())
        assert (tmp_path / 'out' / 'labels' / 'train' / f'{n}.txt').read_text() == ''

    bad = json.loads(path.read_text())
    bad['windows'][0]['split'] = 'holdout'
    path.write_text(json.dumps(bad))
    with pytest.raises(ValueError, match='TRAIN'):
        load_hard_negative_windows(path)

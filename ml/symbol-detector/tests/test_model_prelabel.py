"""Model pre-labeling writes reviewable proposals and respects human decisions."""
from __future__ import annotations

import json
from types import SimpleNamespace

import numpy as np
import pytest

cv2 = pytest.importorskip("cv2")

import scripts_model_prelabel as mlq


class _Arr:
    def __init__(self, a):
        self.a = np.asarray(a)

    def cpu(self):
        return self

    def numpy(self):
        return self.a


class FakeModel:
    """Detects a 'receptacle' wherever a filled black square sits in the tile."""
    names = {0: "receptacle", 1: "mystery_class"}

    def predict(self, source, conf, device, imgsz, verbose):
        out = []
        for patch in source:
            gray = cv2.cvtColor(patch, cv2.COLOR_BGR2GRAY)
            n, _, stats, _ = cv2.connectedComponentsWithStats((gray < 60).astype(np.uint8))
            boxes, cls, scores = [], [], []
            for i in range(1, n):
                x, y, w, h, area = stats[i]
                if area > 30:
                    boxes.append([x, y, x + w, y + h]); cls.append(0); scores.append(0.5)
                    boxes.append([x, y, x + w, y + h]); cls.append(1); scores.append(0.9)
            out.append(SimpleNamespace(boxes=SimpleNamespace(
                xyxy=_Arr(np.array(boxes, float).reshape(-1, 4)), cls=_Arr(cls),
                conf=_Arr(scores), __len__=None) if boxes else None))
        return out


fitz = pytest.importorskip("fitz")
PX = 72 / 150          # store pages render at 150 dpi: 800 x 600 px = 384 x 288 pt


def _page(store, name, *, squares, extra=None):
    """A one-page PDF in the label store with filled 20 px squares; returns its page JSON."""
    from annotator import label_store
    pdf_path = store.parent / "pdfs" / f"{name}.pdf"
    pdf_path.parent.mkdir(parents=True, exist_ok=True)
    pdf = fitz.open()
    page = pdf.new_page(width=800 * PX, height=600 * PX)
    for x, y in squares:
        page.draw_rect(fitz.Rect(x * PX, y * PX, (x + 20) * PX, (y + 20) * PX),
                       color=(0, 0, 0), fill=(0, 0, 0), width=0)
    pdf.save(pdf_path)
    pdf.close()
    doc = label_store.create_document(store, pdf_path, targets={1: "test"},
                                      meta={"source_rel": f"1_blueprints/{name}.pdf"})
    if extra:
        session, index, _ = label_store.open_page(doc.page_json(1))
        data, _, _ = session.page_document(index)
        data.update(extra)
        doc.page_json(1).write_text(json.dumps(data))
    return doc.page_json(1)


def _args(tmp_path, store, **over):
    weights = tmp_path / "run1" / "weights" / "best.pt"
    weights.parent.mkdir(parents=True, exist_ok=True)
    weights.write_bytes(b"fake")
    args = mlq.build_parser().parse_args([
        "--weights", str(weights), "--store", str(store), "--out", str(tmp_path / "out"),
        "--device", "cpu", "--scales", "1.0"])
    for k, v in over.items():
        setattr(args, k, v)
    return args


@pytest.fixture(autouse=True)
def _len_boxes(monkeypatch):
    # SimpleNamespace has no len(); predict_page checks len(boxes).
    monkeypatch.setattr(mlq, "predict_page", _wrap(mlq.predict_page))


def _wrap(fn):
    def inner(model, image, **kw):
        return fn(_LenModel(model), image, **kw)
    return inner


class _LenModel:
    def __init__(self, model):
        self.model, self.names = model, model.names

    def predict(self, **kw):
        res = self.model.predict(**kw)
        for r in res:
            if r.boxes is not None:
                b = r.boxes
                r.boxes = type("B", (), {"xyxy": b.xyxy, "cls": b.cls, "conf": b.conf,
                                         "__len__": lambda s: len(b.cls.a)})()
        return res


def test_writes_unreviewed_model_proposals_and_priority(tmp_path):
    store = tmp_path / "work" / "labels"
    page = _page(store, "set", squares=[(100, 100), (400, 300)])
    result = mlq.run(_args(tmp_path, store), model=FakeModel())
    assert result["totals"]["added"] == 2
    assert result["totals"]["unmapped_class"] == 2          # mystery_class dropped, not guessed
    doc = json.loads(page.read_text())
    boxes = doc["instances"]
    assert {b["symbol_family"] for b in boxes} == {"receptacle"}
    for b in boxes:
        assert b["review_source"] == "model" and b["review_status"] == "unreviewed"
        assert b["uncertain"] is True and b["verified_ground_truth"] is False
        assert b["model_confidence"] == 0.5 and b["model_tag"].startswith("run1-")
    x0, y0, x1, y1 = sorted(b["bbox_xyxy"] for b in boxes)[0]
    assert abs(x0 - 100) <= 2 and abs(x1 - 120) <= 2
    priority = (tmp_path / "out" / result["model_tag"] / "review_priority.txt").read_text()
    assert priority.splitlines() == [page.parent.name]


def test_rerun_is_idempotent_and_respects_human_decisions(tmp_path):
    store = tmp_path / "work" / "labels"
    page = _page(store, "p1", squares=[(100, 100), (400, 300), (600, 450)])
    args = _args(tmp_path, store)
    first = mlq.run(args, model=FakeModel())
    tag = first["model_tag"]
    doc = json.loads(page.read_text())
    a, b, c = sorted(doc["instances"], key=lambda i: i["bbox_xyxy"][0])
    # Human accepts one, rejects one, leaves one.
    a.update(review_status="accepted", review_source="human", uncertain=False,
             verified_ground_truth=True, symbol_variant="duplex")   # verifying names the device
    doc["instances"] = [a, c]
    b_rej = dict(b, review_status="rejected", review_source="human")
    doc["rejected_proposals"] = [b_rej]
    page.write_text(json.dumps(doc))

    second = mlq.run(_args(tmp_path, store, force=True), model=FakeModel())
    doc = json.loads(page.read_text())
    statuses = sorted((i["review_status"], round(i["bbox_xyxy"][0])) for i in doc["instances"])
    assert statuses == [("accepted", 100), ("unreviewed", 600)]   # no duplicate, no re-proposal
    assert second["totals"]["dup_existing"] == 1 and second["totals"]["dup_rejected"] == 1
    assert second["totals"]["replaced"] == 1
    assert all(i.get("model_tag") in (tag, None) or i["review_status"] == "accepted"
               for i in doc["instances"])


def test_labels_evaluation_pages_skips_negatives_and_resumes(tmp_path):
    """Labeling covers the evaluation set too; only training leaves it out."""
    store = tmp_path / "work" / "labels"
    held = _page(store, "evaluation/Locked Set", squares=[(100, 100)])
    neg = _page(store, "neg", squares=[(100, 100)], extra={"verified_no_symbols": True})
    ok = _page(store, "ok", squares=[(100, 100)])
    result = mlq.run(_args(tmp_path, store), model=FakeModel())
    t = result["totals"]
    assert "skipped_heldout" not in t
    assert t["skipped_verified_negative"] == 1 and t["pages_written"] == 2
    assert len(json.loads(held.read_text())["instances"]) == 1
    assert json.loads(neg.read_text())["instances"] == []
    again = mlq.run(_args(tmp_path, store), model=FakeModel())
    assert again["totals"]["resumed"] == 2 and again["totals"].get("pages_written", 0) == 0


def test_class_map_overrides(tmp_path):
    cmap = tmp_path / "map.json"
    cmap.write_text(json.dumps({"mystery_class": ["receptacle", "quad"], "receptacle": None}))
    mapping = mlq.load_class_map(cmap)
    assert mapping["mystery_class"] == ("receptacle", "quad") and "receptacle" not in mapping

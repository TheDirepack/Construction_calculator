"""Shared test setup."""
import pytest


@pytest.fixture(autouse=True)
def _isolated_label_store(tmp_path, monkeypatch):
    """The annotator defaults to the real label store (work/labels); tests get
    a throwaway one so they never write into labeling data."""
    monkeypatch.setenv("BPS_LABELS_DIR", str(tmp_path / "label_store"))

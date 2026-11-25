"""One shared cache of PDF page fingerprints, filled lazily during training.

Exact duplicate pages (the same sheet in several PDFs: re-issued sets, the same
download under two names, standard detail sheets) should train once. Nothing
is hashed up front: when training picks a page, ``is_duplicate`` hashes it
(``blueprint_sets.page_hash``, cached by file size and mtime) and records the
first page seen with that fingerprint as its owner. Every other page with the
same fingerprint is a duplicate, and the caller moves on to another page.

A page identical to an evaluation drawing's page is always a duplicate
(``register_evaluation``), so evaluation content never trains.

One SQLite file, shared by all DataLoader workers, runs and scripts.
"""
from __future__ import annotations

import sqlite3
import threading
from collections import OrderedDict
from pathlib import Path

EVALUATION = "evaluation"


class PageHashCache:
    def __init__(self, path: str | Path):
        self.path = str(path)
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self._local = threading.local()
        self._docs: OrderedDict[str, object] = OrderedDict()
        with self._db() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("CREATE TABLE IF NOT EXISTS pages (pdf TEXT, page INTEGER, size INTEGER, "
                       "mtime INTEGER, hash TEXT, PRIMARY KEY (pdf, page))")
            db.execute("CREATE TABLE IF NOT EXISTS owners (hash TEXT PRIMARY KEY, pdf TEXT, "
                       "page INTEGER, role TEXT)")

    # Connections cannot cross fork(); each worker process opens its own.
    def __getstate__(self):
        return {"path": self.path}

    def __setstate__(self, state):
        self.__init__(state["path"])

    def _db(self) -> sqlite3.Connection:
        db = getattr(self._local, "db", None)
        if db is None:
            db = sqlite3.connect(self.path, timeout=60, isolation_level=None)
            self._local.db = db
        return db

    def _doc(self, pdf: str):
        import pymupdf
        doc = self._docs.get(pdf)
        if doc is None:
            doc = pymupdf.open(pdf)
            self._docs[pdf] = doc
            while len(self._docs) > 2:
                self._docs.popitem(last=False)[1].close()
        else:
            self._docs.move_to_end(pdf)
        return doc

    def page_hash(self, pdf: str | Path, page: int) -> str:
        from .blueprint_sets import page_hash
        pdf = str(Path(pdf).resolve())
        st = Path(pdf).stat()
        db = self._db()
        row = db.execute("SELECT size, mtime, hash FROM pages WHERE pdf=? AND page=?",
                         (pdf, int(page))).fetchone()
        if row and row[0] == st.st_size and row[1] == st.st_mtime_ns:
            return row[2]
        doc = self._doc(pdf)
        digest = page_hash(doc, doc[int(page)])
        db.execute("INSERT OR REPLACE INTO pages VALUES (?, ?, ?, ?, ?)",
                   (pdf, int(page), st.st_size, st.st_mtime_ns, digest))
        return digest

    def register_evaluation(self, pdfs: list[Path]) -> int:
        """Claim the fingerprints of evaluation pages (hashed once, then cached)."""
        import pymupdf
        claimed = 0
        db = self._db()
        for pdf in pdfs:
            try:
                count = pymupdf.open(pdf).page_count
            except Exception:
                continue
            for page in range(count):
                try:
                    digest = self.page_hash(pdf, page)
                except Exception:
                    continue
                db.execute("INSERT OR REPLACE INTO owners VALUES (?, ?, ?, ?)",
                           (digest, str(Path(pdf).resolve()), page, EVALUATION))
                claimed += 1
        return claimed

    def is_duplicate(self, pdf: str | Path, page: int) -> bool:
        """Whether another page (any PDF) already owns this page's content,
        or an evaluation page has the same content."""
        pdf = str(Path(pdf).resolve())
        digest = self.page_hash(pdf, page)
        db = self._db()
        db.execute("INSERT OR IGNORE INTO owners VALUES (?, ?, ?, 'train')", (digest, pdf, int(page)))
        owner_pdf, owner_page, role = db.execute(
            "SELECT pdf, page, role FROM owners WHERE hash=?", (digest,)).fetchone()
        if role == EVALUATION:
            return True
        if (owner_pdf, owner_page) == (pdf, int(page)):
            return False
        if not Path(owner_pdf).is_file():          # owner gone (deleted or moved): take over
            db.execute("UPDATE owners SET pdf=?, page=? WHERE hash=?", (pdf, int(page), digest))
            return False
        return True

"""Shared download helpers for the research/dataset scripts.

TLS is always verified. Downloads go to `<path>.part` first and are renamed when complete, so an
interrupted run never leaves a truncated file that looks finished, and re-runs skip finished files.
"""
import os
import re
import ssl
import time
import urllib.error
import urllib.request
from pathlib import Path

CTX = ssl.create_default_context()
HEADERS = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
                         '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'}
BLUEPRINTS_ROOT = Path(
    os.environ.get('BLUEPRINTS_ROOT', Path(__file__).resolve().parent.parent / 'blueprints_and_libraries')
)


def _open(url, timeout):
    return urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=timeout, context=CTX)


def read(url, timeout=120, attempts=5, backoff=4):
    """The body of [url], retrying with growing waits; raises the last error."""
    last = None
    for attempt in range(attempts):
        try:
            with _open(url, timeout) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(backoff * (attempt + 1))
    raise last


def save(url, path, timeout=300, attempts=6, backoff=5, stop_on_404=False):
    """Downloads [url] to [path] (resumable by skipping finished files). Returns bytes written, 0 on failure."""
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return os.path.getsize(path)
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    for attempt in range(attempts):
        try:
            with _open(url, timeout) as r, open(path + '.part', 'wb') as f:
                while True:
                    chunk = r.read(1024 * 256)
                    if not chunk:
                        break
                    f.write(chunk)
            os.replace(path + '.part', path)
            return os.path.getsize(path)
        except urllib.error.HTTPError as e:
            if stop_on_404 and e.code == 404:
                break
            time.sleep(backoff * (attempt + 1))
        except Exception:  # noqa: BLE001
            time.sleep(backoff * (attempt + 1))
    if os.path.exists(path + '.part'):
        os.remove(path + '.part')
    return 0


def slug(text, limit=60, fallback='item'):
    """A safe lower-case file-name piece: tags stripped, non-alphanumerics collapsed to `_`."""
    text = re.sub(r'<[^>]+>', '', text or '')
    text = re.sub(r'[^A-Za-z0-9]+', '_', text).strip('_').lower()
    return text[:limit] or fallback

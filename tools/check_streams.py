"""Check every direct (hls) stream in channels.js: playlist -> variant -> one video segment.

    python tools/check_streams.py

YouTube and CCTV lines aren't checked here; the app switches away from dead YouTube lines itself.
"""
import re, time, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130 Safari/537.36"


def get(url, limit=None, timeout=12):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    t = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = r.read(limit) if limit else r.read()
        return data, r.geturl(), time.time() - t


def uris(text):
    return [l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")]


def probe(url):
    try:
        data, final, _ = get(url)
        text = data.decode("utf-8", "replace")
        if "#EXT-X-STREAM-INF" in text:
            data, final, _ = get(urllib.parse.urljoin(final, uris(text)[0]))
            text = data.decode("utf-8", "replace")
        segs = uris(text)
        if not segs:
            return False, "no segments"
        data, _, dt = get(urllib.parse.urljoin(final, segs[-1]), limit=4_000_000, timeout=20)
        return True, f"{int(len(data) * 8 / 1000 / max(dt, 0.01))} kbps"
    except Exception as e:
        return False, f"{type(e).__name__}: {e}"[:80]


def main():
    js = (Path(__file__).resolve().parent.parent / "channels.js").read_text(encoding="utf-8")
    urls = re.findall(r"hls\('([^']+)'\)", js)
    with ThreadPoolExecutor(10) as ex:
        for url, (ok, info) in zip(urls, ex.map(probe, urls)):
            print(f"{'OK  ' if ok else 'DEAD'} {info:>14} | {url[:100]}")


if __name__ == "__main__":
    main()

"""Build catalog.json: full dramas and movies published free by official broadcasters on YouTube.

For each official channel it collects the playlists (whole series) and, for movie channels,
the uploaded videos. The app loads catalog.json for its browse and search screens.

Run it every week or so to pick up new shows:
    pip install opencc-python-reimplemented   # once; lets search match simplified and traditional
    python tools/build_catalog.py
"""
import json, re, sys, time, urllib.request
from pathlib import Path

# Only official, broadcaster-run channels. kind: "playlists" = series, "videos" = single films.
PROVIDERS = [
    # TVB
    {"id": "tvb-best",    "name": "TVB 熱播劇場",   "sub": "粵語 · Cantonese",  "channel": "UC-vdzV9IVeIsp0YSCm16CkQ", "kind": "playlists"},
    {"id": "tvb-crime",   "name": "TVB 神秘頻道",   "sub": "警匪 · 懸疑",       "channel": "UCYEcRcwNhGS3fAYVrOneFew", "kind": "playlists"},
    {"id": "tvb-comedy",  "name": "TVB 喜劇台",     "sub": "喜劇",             "channel": "UC3zIehcse5c7xRdE-baFVfw", "kind": "playlists"},
    {"id": "tvb-mandarin","name": "TVB 國語劇場",   "sub": "国语 · Mandarin",   "channel": "UCJL8FI5LLq1G_qE5Ny-Zsqw", "kind": "playlists"},
    {"id": "tvb-anywhere","name": "TVB Anywhere",  "sub": "TVB 劇集",          "channel": "UC6xVm161oqITVgVJ5AIW-Xw", "kind": "playlists"},
    {"id": "tvb-variety", "name": "TVB 綜藝",       "sub": "综艺",             "channel": "UCKh2nOrPXCW4nXuhr_ZTIzg", "kind": "playlists"},
    # CCTV
    {"id": "cctv-drama",  "name": "CCTV 电视剧",    "sub": "央视经典剧",        "channel": "UC7Vl0YiY0rDlovqcCFN4yTA", "kind": "playlists"},
    {"id": "cctv-doc",    "name": "CCTV 纪录",      "sub": "纪录片",           "channel": "UCAYkj2Fz9EvAe2fGJEGMXnQ", "kind": "playlists"},
    # Mainland streaming platforms (their own official YouTube channels)
    {"id": "youku-drama", "name": "优酷 华语剧场",   "sub": "YOUKU",            "channel": "UCvj3DtAqbx12PPHL6IVdhiA", "kind": "playlists"},
    {"id": "youku-movie", "name": "优酷 电影",       "sub": "YOUKU MOVIE",      "channel": "UCE2UHXGOlqotRJgEWl_tiMw", "kind": "videos"},
    {"id": "iqiyi-drama", "name": "爱奇艺 电视剧",   "sub": "iQIYI",            "channel": "UCvNzI_xPIHhB3Vfstj4PMWw", "kind": "playlists"},
    {"id": "iqiyi-movie", "name": "爱奇艺 大电影",   "sub": "iQIYI MOVIE",      "channel": "UCZ8ZJNzzIiDQFuGxdGTyWcQ", "kind": "videos"},
    {"id": "tencent",     "name": "腾讯视频",        "sub": "Tencent Video",    "channel": "UCQatgKoA7lylp_UzvsLCgcw", "kind": "playlists"},
    {"id": "tencent-costume", "name": "腾讯 古装剧", "sub": "Tencent Costume",  "channel": "UCRABdhiBHX4Bie-jfPCd2pg", "kind": "playlists"},
    {"id": "hunan",       "name": "湖南卫视 芒果TV", "sub": "Hunan TV",         "channel": "UC1pHFqCMAIHP8gr4lYGtNLA", "kind": "playlists"},
    {"id": "huace",       "name": "华策影视",        "sub": "Huace TV",         "channel": "UCAMrnDQlsPnrScHun5PgrXw", "kind": "playlists"},
]

PAGES = 4  # pages of results per channel (about 30 items each)
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130 Safari/537.36",
      "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.5"}


def fetch(url, body=None):
    data = json.dumps(body).encode() if body else None
    headers = dict(UA, **({"Content-Type": "application/json"} if body else {}))
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=30) as r:
        return r.read().decode("utf-8", "replace")


def walk(node, key):
    if isinstance(node, dict):
        if key in node:
            yield node[key]
        for v in node.values():
            yield from walk(v, key)
    elif isinstance(node, list):
        for v in node:
            yield from walk(v, key)


def parse_lockup(lv):
    cid = lv.get("contentId")
    ctype = lv.get("contentType", "")
    s = json.dumps(lv, ensure_ascii=False)
    title = next(walk(lv, "title"), {}).get("content", "")
    thumbs = [src["url"] for img in walk(lv, "image") for src in img.get("sources", []) if "ytimg" in src.get("url", "")]
    badges = [b.get("text", "") for b in walk(lv, "thumbnailBadgeViewModel")]
    if not cid or not title:
        return None
    if "LIVE" in s and "BADGE_STYLE_LIVE" in s:
        return None  # live streams are handled on the live channel screen
    kind = "video" if ctype == "LOCKUP_CONTENT_TYPE_VIDEO" else "playlist"
    thumb = thumbs[0] if thumbs else ""
    if kind == "video":
        thumb = f"https://i.ytimg.com/vi/{cid}/mqdefault.jpg"
    return {"type": kind, "id": cid, "title": title, "thumb": thumb, "info": badges[0] if badges else ""}


def channel_items(channel, tab):
    html = fetch(f"https://www.youtube.com/channel/{channel}/{tab}")
    version = re.search(r'"INNERTUBE_CLIENT_VERSION":"([^"]+)"', html).group(1)
    data = json.loads(re.search(r"var ytInitialData = (\{.*?\});</script>", html).group(1))
    items = []
    for page in range(PAGES):
        items += [x for x in (parse_lockup(lv) for lv in walk(data, "lockupViewModel")) if x]
        token = next((c["token"] for c in walk(data, "continuationCommand")), None)
        if not token or page == PAGES - 1:
            break
        time.sleep(0.5)
        data = json.loads(fetch("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                                {"context": {"client": {"clientName": "WEB", "clientVersion": version}},
                                 "continuation": token}))
    seen, out = set(), []
    for it in items:
        if it["id"] not in seen:
            seen.add(it["id"])
            out.append(it)
    return out


def add_search_keys(catalog):
    """Give every title a simplified-Chinese search key, plus a traditional→simplified map
    for the characters that appear, so a search typed in either script finds both."""
    try:
        from opencc import OpenCC  # pip install opencc-python-reimplemented
    except ImportError:
        print("  (opencc not installed: search will not match across simplified/traditional)", file=sys.stderr)
        to_simple = lambda s: s
    else:
        to_simple = OpenCC("t2s").convert
    chars = set()
    for p in catalog["providers"]:
        for it in p["items"]:
            it["key"] = to_simple(it["title"]).lower()
            chars.update(it["title"])
    catalog["t2s"] = {c: s for c in sorted(chars) if len(s := to_simple(c)) == 1 and s != c}


def main():
    catalog = {"built": time.strftime("%Y-%m-%d"), "providers": []}
    for p in PROVIDERS:
        try:
            items = channel_items(p["channel"], p["kind"])
        except Exception as e:  # one broken channel shouldn't stop the rest
            print(f"  ! {p['name']}: {e}", file=sys.stderr)
            items = []
        if p["kind"] == "videos":
            # keep feature-length uploads only (badge looks like 1:32:10)
            items = [i for i in items if re.fullmatch(r"\d+:\d\d:\d\d", i["info"])]
        print(f"{p['name']}: {len(items)}")
        catalog["providers"].append({k: p[k] for k in ("id", "name", "sub")} | {"items": items})
    add_search_keys(catalog)
    out = Path(__file__).resolve().parent.parent / "catalog.json"
    out.write_text(json.dumps(catalog, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"wrote {out} ({out.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()

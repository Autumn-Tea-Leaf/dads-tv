"""Build catalog.json and episodes.json: full dramas and movies published free by official
broadcasters on YouTube.

For each official channel it collects the playlists (whole series) and, for movie channels,
the uploaded videos, then tidies them (see "tidy shows and episodes" below).
  catalog.json   the shows, for the app's browse and search screens
  episodes.json  each series' episodes in order, for its episode screen

Run it every week or so to pick up new shows (the Build workflow does, every Monday):
    pip install opencc-python-reimplemented   # once; lets search match simplified and traditional
    python tools/build_catalog.py
    python tools/build_catalog.py --cache fetched.json   # keep what was fetched, to rerun quickly
"""
import json, re, sys, time, urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
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
    for attempt in range(3):   # YouTube now and then answers with an error or an empty page
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=30) as r:
                text = r.read().decode("utf-8", "replace")
            if body or "ytInitialData" in text:
                return text
        except OSError:
            if attempt == 2:
                raise
        time.sleep(2 + 3 * attempt)
    raise OSError(f"no usable answer from {url}")


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


CACHE = {}   # filled from / saved to the file named by --cache, if given


def cached(kind):
    def wrap(fn):
        def inner(*args):
            key = kind + ":" + ":".join(args)
            if key not in CACHE:
                CACHE[key] = fn(*args)
            return CACHE[key]
        return inner
    return wrap


@cached("channel")
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


# ---------------------------------------------------------------- tidy shows and episodes
#
# The channels' own playlists are untidy: promotional titles, the same show several times,
# trailer and highlight playlists, episodes listed last-first or twice. So every playlist is
# opened, only full-length videos are kept, episode numbers are read from the video titles and
# the episodes sorted, and the show gets a plain name. The episode lists go to episodes.json:
#   {playlistId: [[videoId, "5"], [videoId, "6-7"], ...]}

MIN_EPISODE_SECONDS = 15 * 60   # shorter videos are trailers, previews and highlight clips
MAX_EPISODES = 400
ROOT = Path(__file__).resolve().parent.parent

JUNK = re.compile(r"精彩看点|看点|精華|精华|预告|預告|花絮|一口气|片段|会员专享|會員專享|会员抢先|會員搶先|Kiwi Only"
                  r"|主题曲|主題曲|歌曲|峰会|峰會|\btrailer|\bpreview|\bhighlight|\bclips?\b|\bost\b", re.I)
EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿⏩-⏺️‍]")
CJK = re.compile("[一-鿿]")
# Words that mark a 【...】 block as a label rather than the show's name.
LABEL = re.compile(r"GMT|会员|會員|\bFULL\b|SUB\b|\bAPP\b|On Air|Rerun|Kiwi|纯剧情|重映|重温|重溫|更新|完整"
                   r"|线上看|線上看|连播|連播|\bHD\b|1080|4K", re.I)
# A Chinese name followed by its English name, as the streaming platforms write titles.
BILINGUAL = re.compile("^[\u4e00-\u9fff][^|｜]{0,24}?\\s+[A-Za-z]")

EPISODE_PATTERNS = [  # (pattern, kind), tried in this order
    (re.compile(r"(?<!\d)(\d{1,3})\s*&\s*(\d{1,3})\s*/\s*\d{1,3}"), "pair"),             # 1 & 2/32
    (re.compile(r"(?<!\d)(\d{1,3})\s*/\s*(\d{1,3})(?!\d)"), "of"),                       # 01/20
    (re.compile(r"第\s*(\d{1,3})\s*[-~－]\s*(\d{1,3})\s*[集話话期]"), "pair"),              # 第1-2集
    (re.compile(r"第\s*(\d{1,3})\s*[集話话期]"), "one"),                                   # 第5集
    (re.compile(r"\bEP\.?\s*(\d{1,3})\s*[-~&]\s*(?:EP\.?\s*)?(\d{1,3})", re.I), "pair"),  # EP01-02
    (re.compile(r"\bEP\.?\s*(\d{1,3})", re.I), "one"),                                   # EP20
    (re.compile(r"\bE(\d{1,3})\b"), "one"),                                              # E05
]


def episode_of(title):
    """(number to sort by, label such as "5" or "1-2") read from a video title, or None."""
    for pattern, kind in EPISODE_PATTERNS:
        m = pattern.search(title)
        if not m:
            continue
        a = int(m.group(1))
        if kind == "of":
            if a <= int(m.group(2)):
                return a, str(a)
        elif kind == "pair":
            b = int(m.group(2))
            if a < b:
                return a, f"{a}-{b}"
        else:
            return a, str(a)
    return None


def seconds(badge):
    """'43:50' or '1:19:11' -> seconds; anything else (LIVE, members only, ...) -> 0."""
    if not re.fullmatch(r"\d+(:\d\d){1,2}", badge or ""):
        return 0
    n = 0
    for part in badge.split(":"):
        n = n * 60 + int(part)
    return n


@cached("playlist")
def playlist_videos(playlist):
    """Every video of a playlist as (id, title, seconds), in the playlist's own order."""
    html = fetch(f"https://www.youtube.com/playlist?list={playlist}")
    version = re.search(r'"INNERTUBE_CLIENT_VERSION":"([^"]+)"', html).group(1)
    data = json.loads(re.search(r"var ytInitialData = (\{.*?\});</script>", html).group(1))
    videos, used = [], set()
    while True:
        for lv in walk(data, "lockupViewModel"):
            if lv.get("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO" or not lv.get("contentId"):
                continue
            title = next(walk(lv, "title"), {}).get("content", "")
            badges = [b.get("text", "") for b in walk(lv, "thumbnailBadgeViewModel")]
            videos.append((lv["contentId"], title, max([seconds(b) for b in badges] or [0])))
        token = next((c["token"] for c in walk(data, "continuationCommand")
                      if c.get("token") and c["token"] not in used), None)
        if not token or len(videos) >= MAX_EPISODES:
            return videos
        used.add(token)
        time.sleep(0.3)
        data = json.loads(fetch("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                                {"context": {"client": {"clientName": "WEB", "clientVersion": version}},
                                 "continuation": token}))


def tidy_episodes(videos):
    """Full-length videos only, without repeats, in episode order.
    Returns ([[videoId, label], ...], [their titles])."""
    full, seen = [], set()
    for vid, title, secs in videos:
        if secs >= MIN_EPISODE_SECONDS and vid not in seen:
            seen.add(vid)
            full.append((vid, title, episode_of(title)))
    numbered = [v for v in full if v[2]]
    if len(numbered) >= 2 and len(numbered) >= 0.7 * len(full):
        by_number = {}
        for v in numbered:
            by_number.setdefault(v[2][0], v)   # the first copy of an episode wins
        ordered = [by_number[n] for n in sorted(by_number)]
        return [[v[0], v[2][1]] for v in ordered], [v[1] for v in ordered]
    return [[v[0], ""] for v in full], [v[1] for v in full]


def name_only(s):
    """'君子盟 A League of Nobleman' -> '君子盟' (drop an English name after a Chinese one)."""
    s = s.strip()
    return re.split(r"\s+(?=[A-Za-z])", s, maxsplit=1)[0].strip() if CJK.match(s) else s


def clean_title(raw, episode_titles=()):
    """A plain show name from a playlist or video title."""
    t = EMOJI.sub("", raw).strip()
    for m in re.finditer(r"【([^】]+)】", t):          # 【君子盟 A League of Nobleman】
        inner = m.group(1).strip()
        if CJK.search(inner) and not LABEL.search(inner) and not re.search(r"[|｜]", inner):
            return name_only(inner)
    names = Counter(n[0].strip() for n in (re.findall(r"《([^》]+)》", et) for et in episode_titles) if n)
    if names:                                         # 《国家使命》EP20 in most episode titles
        name, count = names.most_common(1)[0]
        if count >= 0.6 * len(episode_titles):
            return name_only(name)
    m = re.search(r"《([^》]+)》", t)
    if m:
        return name_only(m.group(1))
    if not CJK.search(t):                             # [Shadows of Desire] A driver's fight ...
        m = re.search(r"[\[【]\s*([^\]】]*[A-Za-z][^\]】]*?)\s*[\]】]", t)
        if m and not LABEL.search(m.group(1)):
            return m.group(1)
    t = re.sub(r"[\[［【][^\]］】]*[\]］】]", " ", t)                                  # [TVB完整劇] 【FULL】
    t = re.sub(r"[（(][^）)]*(?:字|語|语|sub|版)[^）)]*[）)]", " ", t, flags=re.I)       # （粵語中字）
    t = re.sub(r"TVB\s*完整版?(?:時裝|时装|古装|古裝)?(?:喜劇|喜剧|剧集|劇集|劇|剧)?", " ", t)
    t = re.sub(r"Get APP Now\]?|ENGSUB|ENGDUB|MULTISUB|Multi Sub|\b4K\b|\b60fps\b", " ", t, flags=re.I)
    parts = [re.sub(r"\s+", " ", part).strip(" -·:：") for part in re.split(r"[|｜]", t)]
    parts = [part for part in parts if part]
    # 医学风云，人心浮动… | 促醒者 The Neuron Doctors | 爱奇艺华语剧场  ->  促醒者
    named = next((part for part in parts if BILINGUAL.match(part)), parts[0] if parts else "")
    return name_only(named) or EMOJI.sub("", raw).strip()


def tidy_provider(items, kind, old_episodes, episodes):
    """One channel's items, cleaned. Fills `episodes` for its series.
    Returns (items to show, number of playlists that could not be read)."""
    if kind == "videos":
        for it in items:
            it["raw"] = it["title"]
            it["title"] = clean_title(it["title"])
        return items, 0

    def load(it):
        try:
            return tidy_episodes([tuple(v) for v in playlist_videos(it["id"])])
        except Exception as e:
            return e

    candidates = [it for it in items if not JUNK.search(it["title"])]
    with ThreadPoolExecutor(8) as pool:
        loaded = list(pool.map(load, candidates))
    failed, by_name = 0, {}
    for it, result in zip(candidates, loaded):
        if isinstance(result, Exception):
            failed += 1
            eps, titles = old_episodes.get(it["id"]), []   # keep last week's list if there is one
        else:
            eps, titles = result
        if not eps:
            continue
        it["raw"] = it["title"]
        it["title"] = clean_title(it["title"], titles)
        it["info"] = f"{len(eps)} 集"
        it["eps"] = eps
        it["thumb"] = f"https://i.ytimg.com/vi/{eps[0][0]}/mqdefault.jpg"   # a plain 16:9 picture
        best = by_name.get(it["title"])
        if not best or len(eps) > len(best["eps"]):        # same show twice: keep the fuller one
            by_name[it["title"]] = it
    kept = [it for it in candidates if by_name.get(it.get("title")) is it]
    for it in kept:
        episodes[it["id"]] = it.pop("eps")
    return kept, failed


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
            # the plain name and the channel's own title (English name, actors) are both searchable
            raw = it.pop("raw", "")
            it.pop("eps", None)
            it["key"] = to_simple(it["title"] + " " + raw).lower()
            chars.update(it["title"] + raw)
    catalog["t2s"] = {c: s for c in sorted(chars) if len(s := to_simple(c)) == 1 and s != c}


def main():
    cache_file = Path(sys.argv[sys.argv.index("--cache") + 1]) if "--cache" in sys.argv else None
    if cache_file and cache_file.exists():
        CACHE.update(json.loads(cache_file.read_text(encoding="utf-8")))
    try:
        build()
    finally:
        if cache_file:
            cache_file.write_text(json.dumps(CACHE, ensure_ascii=False), encoding="utf-8")


def build():
    catalog = {"built": time.strftime("%Y-%m-%d"), "providers": []}
    episodes_file = ROOT / "episodes.json"
    old_episodes = json.loads(episodes_file.read_text(encoding="utf-8")) if episodes_file.exists() else {}
    catalog_file = ROOT / "catalog.json"
    old_catalog = json.loads(catalog_file.read_text(encoding="utf-8")) if catalog_file.exists() else {"providers": []}
    old_items = {p["id"]: p["items"] for p in old_catalog["providers"]}
    episodes, listed, failed = {}, 0, 0
    for p in PROVIDERS:
        try:
            items = [dict(it) for it in channel_items(p["channel"], p["kind"])]
        except Exception as e:  # one unreadable channel shouldn't stop the rest: keep its last list
            items = old_items.get(p["id"], [])
            print(f"  ! {p['name']}: {e}; keeping the {len(items)} shows from last time", file=sys.stderr)
            for it in items:
                it["raw"] = it.pop("key", "")
                if it["id"] in old_episodes:
                    episodes[it["id"]] = old_episodes[it["id"]]
            catalog["providers"].append({k: p[k] for k in ("id", "name", "sub")} | {"items": items})
            continue
        if p["kind"] == "videos":
            # keep feature-length uploads only (badge looks like 1:32:10)
            items = [i for i in items if re.fullmatch(r"\d+:\d\d:\d\d", i["info"])]
        count = len(items)
        listed += count
        items, bad = tidy_provider(items, p["kind"], old_episodes, episodes)
        failed += bad
        print(f"{p['name']}: {len(items)} (of {count} listed, {bad} could not be read)", flush=True)
        catalog["providers"].append({k: p[k] for k in ("id", "name", "sub")} | {"items": items})
    kept = sum(len(p["items"]) for p in catalog["providers"])
    if kept < 100 or failed > 0.3 * max(listed, 1):
        # YouTube refused us (rate limit, changed page): better last week's lists than broken ones.
        print(f"only {kept} shows read, {failed} failures: keeping the existing files", file=sys.stderr)
        return
    add_search_keys(catalog)
    out = ROOT / "catalog.json"
    out.write_text(json.dumps(catalog, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    episodes_file.write_text(json.dumps(episodes, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"wrote {out} ({out.stat().st_size // 1024} KB) and {episodes_file} "
          f"({episodes_file.stat().st_size // 1024} KB, {len(episodes)} series)")


if __name__ == "__main__":
    main()

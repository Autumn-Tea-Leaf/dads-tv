"""Drive the real APK on an Android emulator like a TV remote would, and record what happens.

Run by .github/workflows/device-test.yml inside an emulator:
    python3 tools/device_test.py dads-tv.apk out/

It installs and opens the app, then for each check presses real keys over adb (D-pad, OK,
Back), reads page state through the WebView's DevTools socket, and saves a screenshot.
Results go to out/results.json and out/summary.txt; app logs to out/logcat.txt.
"""
import json, os, re, subprocess, sys, time, urllib.request

from websocket import create_connection  # pip install websocket-client

PKG = 'tv.dad.app'
APK, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
results = []


def adb(*args, check=False, timeout=60):
    return subprocess.run(['adb', *args], capture_output=True, text=True, timeout=timeout, check=check).stdout


def key(code, wait=1.5):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(wait)


def shot(name):
    png = subprocess.run(['adb', 'exec-out', 'screencap', '-p'], capture_output=True, timeout=60).stdout
    with open(os.path.join(OUT, name + '.png'), 'wb') as f:
        f.write(png)


def pages():
    """DevTools targets of the app's WebView (re-forwarded each time: the socket name has the pid)."""
    sockets = re.findall(r'@(webview_devtools_remote_\d+)', adb('shell', 'cat', '/proc/net/unix'))
    if not sockets:
        return []
    adb('forward', 'tcp:9222', 'localabstract:' + sockets[-1])
    try:
        return [p for p in json.load(urllib.request.urlopen('http://127.0.0.1:9222/json', timeout=10))
                if p.get('type') == 'page']
    except Exception:
        return []


def js(expr, url_has=None):
    """Evaluate in the page whose URL contains url_has (or the first page). Returns (value, url)."""
    for _ in range(10):
        ps = [p for p in pages() if not url_has or url_has in p.get('url', '')]
        if ps:
            break
        time.sleep(2)
    else:
        return 'NO PAGE' + (' matching ' + url_has if url_has else ''), None
    page = ps[0]
    try:
        ws = create_connection(page['webSocketDebuggerUrl'], suppress_origin=True, timeout=20)
        ws.send(json.dumps({'id': 1, 'method': 'Runtime.evaluate',
                            'params': {'expression': expr, 'returnByValue': True}}))
        while True:
            msg = json.loads(ws.recv())
            if msg.get('id') == 1:
                ws.close()
                res = msg.get('result', {})
                if 'exceptionDetails' in res:
                    return 'JS EXCEPTION: ' + json.dumps(res['exceptionDetails'])[:300], page['url']
                return res.get('result', {}).get('value'), page['url']
    except Exception as e:
        return 'DEVTOOLS ERROR: %s' % e, page.get('url')


def record(name, ok, **detail):
    results.append({'check': name, 'ok': bool(ok), **detail})
    print(('PASS ' if ok else 'FAIL ') + name + ' ' + json.dumps(detail, ensure_ascii=False)[:400], flush=True)


HOME_STATE = """(() => ({
  started: window.__dadtvStarted === true,
  errorBox: (document.getElementById('dadtv-error') || {}).innerText || null,
  screen: [...document.querySelectorAll('.screen.on')].map(s => s.id).join(),
  layoutWidth: innerWidth,
  rowsSideBySide: (() => { const t = document.querySelectorAll('#groups .tile');
                           return t.length > 1 && t[0].getBoundingClientRect().top === t[1].getBoundingClientRect().top; })(),
  tiles: document.querySelectorAll('#groups .tile').length,
  vodTiles: document.querySelectorAll('#vodGroup .tile').length,
  focused: document.activeElement ? (document.activeElement.innerText || '').split('\\n')[0] : null,
  headerVisible: document.getElementById('greet').getBoundingClientRect().top >= 0,
  engineNotice: !document.getElementById('engine').classList.contains('hidden'),
  bridge: typeof window.DadTV,
  ua: navigator.userAgent
}))()"""

VIDEO_STATE = """(() => { const v = document.querySelector('video');
  if (!v) return {video: false, note: (document.body ? document.body.innerText : '').slice(0, 200)};
  return {video: true, playing: !v.paused && v.currentTime > 0, t: v.currentTime, paused: v.paused, muted: v.muted,
          w: v.videoWidth, readyState: v.readyState, error: v.error && v.error.code,
          fullscreen: Math.round(v.getBoundingClientRect().width) >= innerWidth - 2}; })()"""


def focus_tile(name):
    expr = ("(() => { const t = [...document.querySelectorAll('#groups .tile')].find(x => "
            "x.querySelector('.name').textContent === %s); if (!t) return false; t.focus(); return true; })()"
            % json.dumps(name))
    return js(expr)[0] is True


def back_to_home(label):
    key(4, 3)  # KEYCODE_BACK
    state, url = js(HOME_STATE)
    record(label + ': Back returns to home', isinstance(state, dict) and state.get('screen') == 'home',
           url=url, state=state)


def live_channel(name, url_has, wait):
    if not focus_tile(name):
        record(name + ': tile found', False)
        return
    key(23, wait)  # KEYCODE_DPAD_CENTER (OK)
    shot('live-' + re.sub(r'\W+', '_', url_has or name.encode('unicode_escape').decode()))
    if url_has:  # CCTV/TVB: their own page and <video>
        state, url = js(VIDEO_STATE, url_has)
        ok = isinstance(state, dict) and state.get('playing')
    else:        # our player (hls.js or a YouTube frame): ask the app
        state, url = js('window.dadtvDebug && window.dadtvDebug()')
        ok = isinstance(state, dict) and state.get('played')
    record(name + ': video plays', ok, url=url, state=state)
    back_to_home(name)


def main():
    print(adb('install', '-r', '-g', APK, timeout=180), flush=True)
    adb('logcat', '-c')
    record('WebView version', True, webview=adb('shell', 'dumpsys', 'webviewupdate')[:600])
    adb('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(20)
    shot('01-home')

    state, url = js(HOME_STATE)
    ok = isinstance(state, dict) and state.get('started') and not state.get('errorBox')
    record('home starts without errors', ok, url=url, state=state)
    if isinstance(state, dict):
        record('tiles in rows, not one column', state.get('rowsSideBySide'), tiles=state.get('tiles'))
        record('layout is 1600 wide (TV scaling)', state.get('layoutWidth') == 1600, width=state.get('layoutWidth'))
        record('header visible', state.get('headerVisible'))
        record('remote has focus on a tile', bool(state.get('focused')), focused=state.get('focused'))

    # D-pad moves focus
    before = js("document.activeElement && document.activeElement.innerText")[0]
    key(20)  # DPAD_DOWN
    key(22)  # DPAD_RIGHT
    after = js("document.activeElement && document.activeElement.innerText")[0]
    record('D-pad moves focus', before != after, before=before, after=after)
    shot('02-after-dpad')

    live_channel('CGTN', None, 15)
    live_channel('凤凰卫视', None, 20)
    live_channel('CCTV-4', 'cctv.com', 30)
    live_channel('無綫新聞台', 'tvb.com', 30)

    # A drama from the catalogue, then Back twice (player -> list -> home)
    js("(() => { const t = document.querySelectorAll('#vodGroup .tile')[1]; t.focus(); return !!t; })()")
    key(23, 3)
    js("(() => { const c = document.querySelector('#cards .card'); c.focus(); return !!c; })()")
    key(23, 20)
    shot('vod-player')
    state, url = js("[...document.querySelectorAll('.screen.on')].map(s => s.id).join() + ' | ' + "
                    "((document.getElementById('osd') || {}).innerText || '')")
    record('drama opens in player', isinstance(state, str) and state.startswith('player'), state=state)
    key(4, 2)
    s1 = js("[...document.querySelectorAll('.screen.on')].map(s => s.id).join()")[0]
    key(4, 2)
    s2 = js("[...document.querySelectorAll('.screen.on')].map(s => s.id).join()")[0]
    record('Back steps player -> list -> home', s1 == 'browse' and s2 == 'home', after1=s1, after2=s2)

    # Back on home: first press must keep the app open (toast), not exit
    key(4, 1)
    top = adb('shell', 'dumpsys', 'activity', 'activities')
    record('one Back on home keeps the app open', PKG in (re.search(r'mResumedActivity.*', top) or [''])[0]
           or PKG in (re.search(r'topResumedActivity.*', top) or [''])[0])
    shot('99-end')


try:
    main()
finally:
    with open(os.path.join(OUT, 'logcat.txt'), 'w', encoding='utf-8') as f:
        f.write(adb('logcat', '-d', '-v', 'time', 'DadTV:V', 'chromium:V', 'cr_*:V', 'AndroidRuntime:E', '*:S',
                    timeout=60))
    with open(os.path.join(OUT, 'results.json'), 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=1)
    with open(os.path.join(OUT, 'summary.txt'), 'w', encoding='utf-8') as f:
        for r in results:
            f.write(('PASS ' if r['ok'] else 'FAIL ') + r['check'] + '\n')

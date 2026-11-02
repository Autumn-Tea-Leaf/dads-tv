"""Drive the real APK on an Android emulator like a TV remote would, and record what happens.

Run by .github/workflows/device-test.yml inside an emulator:
    python3 tools/device_test.py dads-tv-x86.apk out/

The app is started with a "#test" link, which makes the page log its state every 2 s
("DADTV_STATE {...}") and lets the test pre-select a tile ("&focus=cgtn"); the full-screen
extension logs CCTV/TVB video status ("DADTV_VIDEO {...}"). The test presses real keys over
adb (D-pad, OK, Back), reads those log lines, and saves screenshots.
Results go to out/results.json and out/summary.txt; app logs to out/logcat.txt.
"""
import json, os, re, subprocess, sys, time

PKG = 'tv.dad.app'
APK, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
results = []
full_log = []


def adb(*args, timeout=90):
    return subprocess.run(['adb', *args], capture_output=True, text=True, timeout=timeout,
                          encoding='utf-8', errors='replace').stdout


def key(code, wait=1.5):
    adb('shell', 'input', 'keyevent', str(code))
    time.sleep(wait)


def shot(name):
    png = subprocess.run(['adb', 'exec-out', 'screencap', '-p'], capture_output=True, timeout=60).stdout
    with open(os.path.join(OUT, name + '.png'), 'wb') as f:
        f.write(png)


def fresh_log():
    """Keep what's logged so far for the report, then start a new section."""
    full_log.append(adb('logcat', '-d', '-v', 'time'))
    adb('logcat', '-c')


def latest(tag):
    """The most recent DADTV_STATE / DADTV_VIDEO object logged since fresh_log()."""
    found = None
    for line in adb('logcat', '-d').splitlines():
        i = line.find(tag + ' ')
        if i >= 0:
            try:
                found = json.loads(line[i + len(tag) + 1:].strip())
            except ValueError:
                pass
    return found


def wait_state(cond, timeout=30, tag='DADTV_STATE'):
    """Poll the log until cond(state) holds; return the last state seen."""
    end, state = time.time() + timeout, None
    while time.time() < end:
        state = latest(tag)
        if state and cond(state):
            return state
        time.sleep(2)
    return state


def start(fragment):
    fresh_log()
    adb('shell', 'am', 'start', '-n', PKG + '/.MainActivity', '-a', 'android.intent.action.VIEW',
        '-d', 'dadtv://test#' + fragment)


def record(name, ok, **detail):
    results.append({'check': name, 'ok': bool(ok), **detail})
    print(('PASS ' if ok else 'FAIL ') + name + ' ' + json.dumps(detail, ensure_ascii=False)[:400], flush=True)


def back_home(label):
    fresh_log()
    key(4, 2)  # KEYCODE_BACK
    state = wait_state(lambda s: s.get('screen') == 'home', 25)
    record(label + ': Back returns to home', state and state.get('screen') == 'home', state=state)


def live(channel_id, label, web, wait):
    start('test&focus=' + channel_id)
    wait_state(lambda s: s.get('focused'), 30)
    fresh_log()
    key(23, 2)  # KEYCODE_DPAD_CENTER (OK)
    if web:  # CCTV / TVB official page: the extension reports the <video>
        state = wait_state(lambda s: s.get('playing'), wait, 'DADTV_VIDEO')
        ok = bool(state and state.get('playing'))
    else:    # our own player (hls.js or YouTube)
        state = wait_state(lambda s: s.get('played'), wait)
        ok = bool(state and state.get('played'))
    shot('live-' + channel_id)
    record(label + ': video plays', ok, state=state)
    back_home(label)


def main():
    print(adb('install', '-r', '-g', APK, timeout=300), flush=True)
    record('device', True, release=adb('shell', 'getprop', 'ro.build.version.release').strip(),
           abi=adb('shell', 'getprop', 'ro.product.cpu.abi').strip())

    start('test')
    state = wait_state(lambda s: s.get('started') and s.get('vodTiles'), 90)
    shot('01-home')
    record('home starts without errors', state and not state.get('errorBox'), state=state)
    if state:
        record('tiles in rows, not one column', state.get('rowsSideBySide'))
        record('layout is 1600 wide (TV scaling)', state.get('layoutWidth') == 1600, width=state.get('layoutWidth'))
        record('header visible', state.get('headerVisible'))
        record('remote has focus on a tile', bool(state.get('focused')), focused=state.get('focused'))

    before = (latest('DADTV_STATE') or {}).get('focused')
    fresh_log()
    key(20)  # DPAD_DOWN
    key(22)  # DPAD_RIGHT
    after = (wait_state(lambda s: True, 6) or {}).get('focused')
    record('D-pad moves focus', before != after, before=before, after=after)
    shot('02-after-dpad')

    live('cgtn', 'CGTN', False, 30)
    live('phoenix', 'Phoenix (YouTube)', False, 40)
    live('cctv4', 'CCTV-4', True, 45)
    live('tvbnews', 'TVB News', True, 45)

    # A drama: provider tile -> first card -> plays; Back twice: player -> list -> home
    start('test&focusvod=1')
    wait_state(lambda s: s.get('focused'), 30)
    key(23, 3)
    fresh_log()
    key(23, 2)
    state = wait_state(lambda s: s.get('screen') == 'player' and s.get('played'), 45)
    shot('vod-player')
    record('drama plays', state and state.get('played'), state=state)
    fresh_log()
    key(4, 2)
    s1 = (wait_state(lambda s: s.get('screen') != 'player', 10) or {}).get('screen')
    fresh_log()
    key(4, 2)
    s2 = (wait_state(lambda s: s.get('screen') == 'home', 10) or {}).get('screen')
    record('Back steps player -> list -> home', s1 == 'browse' and s2 == 'home', after1=s1, after2=s2)

    key(4, 1)  # one Back on home: the app must stay open
    top = adb('shell', 'dumpsys', 'activity', 'activities')
    resumed = ' '.join(re.findall(r'(?:mResumedActivity|topResumedActivity|mFocusedActivity).*', top))
    record('one Back on home keeps the app open', PKG in resumed, resumed=resumed[:200])
    shot('99-end')


try:
    main()
finally:
    full_log.append(adb('logcat', '-d', '-v', 'time'))
    with open(os.path.join(OUT, 'logcat.txt'), 'w', encoding='utf-8') as f:
        for section in full_log:
            f.write('\n'.join(l for l in section.splitlines()
                              if re.search(r'DadTV|DADTV_|GeckoConsole|Gecko.*(error|Error)|AndroidRuntime|FATAL', l)))
            f.write('\n')
    with open(os.path.join(OUT, 'results.json'), 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=1)
    with open(os.path.join(OUT, 'summary.txt'), 'w', encoding='utf-8') as f:
        for r in results:
            f.write(('PASS ' if r['ok'] else 'FAIL ') + r['check'] + '\n')

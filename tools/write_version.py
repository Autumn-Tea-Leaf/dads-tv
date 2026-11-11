"""Write version.json: what the TV app reads to find out whether a newer app exists.

Run by the Build workflow after the APKs are built:
    python tools/write_version.py OUT VERSION_CODE DOWNLOAD_BASE SOURCE PREVIOUS_URL

  OUT            where to write version.json (it is published next to the page)
  VERSION_CODE   the version of the APKs just built
  DOWNLOAD_BASE  address the APKs are downloaded from, e.g. https://github.com/me/repo/releases/download/latest/
  SOURCE         identifies the app's source code (the git tree of android/)
  PREVIOUS_URL   the version.json published by the last build

Boxes only update when VERSION_CODE goes up (appVersionCode in android/app/build.gradle), so
if the app's code changed and the version did not, this prints a warning in the workflow run.
"""
import hashlib, json, os, sys, urllib.request

APKS = {  # processor type -> file in the release
    'armeabi-v7a': 'dads-tv.apk',
    'arm64-v8a': 'dads-tv-arm64.apk',
    'x86': 'dads-tv-x86.apk',
    'x86_64': 'dads-tv-x86_64.apk',
}

out, version, base, source, previous_url = sys.argv[1:6]
version = int(version)

try:
    with urllib.request.urlopen(previous_url, timeout=30) as r:
        previous = json.load(r)
except Exception as e:  # the first build with this file, or the site is unreachable
    print(f'no previous version.json ({e})')
    previous = {}

if previous.get('versionCode') == version and previous.get('source'):
    if previous['source'] != source:
        print(f'::warning::The app code changed but appVersionCode is still {version}, so boxes will not '
              'update to this build. Raise appVersionCode in android/app/build.gradle to send it out.')
    source = previous['source']  # the code that this version number was first published with

apks = {}
for abi, name in APKS.items():
    with open(name, 'rb') as f:
        data = f.read()
    apks[abi] = {'url': base + name, 'sha256': hashlib.sha256(data).hexdigest(), 'size': len(data)}

os.makedirs(os.path.dirname(out) or '.', exist_ok=True)
with open(out, 'w', encoding='utf-8') as f:
    json.dump({'versionCode': version, 'source': source, 'apks': apks}, f, indent=1)
print(open(out, encoding='utf-8').read())

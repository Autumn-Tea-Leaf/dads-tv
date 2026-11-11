# 区tv

A big-button TV app for Chinese live TV, dramas and movies, made for a TV remote.
Every source is free and comes straight from the broadcaster (CCTV, Phoenix, CGTN, provincial
stations, TVB/Youku/iQIYI/Tencent/Mango official YouTube channels). There are no subscriptions
and no pirate sites, so nothing gets shut down or blocked.

## What Dad sees

- **央视 CCTV**: CCTV-1, 2, 3, 4, 5, 6, 8, 9, 11, 13, opened on CCTV's own site, full screen.
- **News / dramas / provincial channels**: Phoenix, CGTN, CCTV 24-hour drama marathons (三国, 水浒,
  西游记, 康熙王朝, 雍正王朝), a 24-hour movie channel, Hunan, Hebei, Zhejiang, Guangzhou and more.
- **台湾新闻**: TVBS, 东森, 中天, 民视, 公视, 中视, 寰宇.
- **点播 · 电视剧 电影**: about 1,100 complete series and films, with plain names (the channels'
  own titles are full of promotion), no trailers or highlight clips, and no repeats. Browse by
  channel or search; simplified or traditional characters both work. A series opens on its
  episode screen: episodes in order, the ones watched marked, and the one to continue with
  selected (继续观看 on the home screen goes straight back to it).

## Remote control

| Where | Key | Does |
|---|---|---|
| Lists | ↑ ↓ ← → / OK | move / open |
| Live TV | ↑ ↓ | previous / next channel |
| Live TV | ← → | change line (线路) by hand |
| Dramas, movies | ↑ ↓ | previous / next episode |
| Dramas, movies | ← → | back / forward 30 seconds |
| Dramas, movies | OK | pause / play |
| Anywhere | Back | previous screen (press twice on the home screen to exit) |

**Automatic line switching.** If a live channel won't start within 20 seconds, freezes for 7
seconds, or freezes 3 times in a minute, the app first drops the picture quality, then moves to
the channel's next line. If every line fails, it says so and he can change channel.

## Installing it on the TV box

The Unblock Tech box runs Android, so it installs like any Android app.

1. **Get the APK.** It's on the GitHub release page as `dads-tv.apk` (see "Publishing" below).
2. **Get it onto the box.** Either way works:
   - **USB stick:** copy `dads-tv.apk` onto a USB stick, plug it into the box, and open it with
     the box's file manager.
   - **Downloader app:** install **Downloader** (by AFTVnews) from the box's app store, then type
     the release download link.
3. **Allow the install.** Android will ask to allow installs from that app ("unknown sources").
   Say yes for the file manager or Downloader only.
   This is needed once. After that the app updates itself (see "Updates" below).
4. **Open 区tv** from the home screen. To make it easy to find, move it to the first
   position, or set it as the box's startup app if the launcher allows that.

**Tips for less lag**
- Use an **ethernet cable** instead of Wi-Fi if at all possible.
- A few direct streams are 4K (Hebei, Hunan). If they stutter on his line, the app lowers the quality
  automatically. If they still stutter, move them lower in `channels.js`.

## Updates

- **Channels, the page, the drama list:** the app loads the published page, so these change on
  Dad's TV by themselves.
- **The app itself:** the app looks at `version.json` (published next to the page) shortly after
  it starts and every 6 hours. When the version there is higher than its own, it downloads the
  new APK in the background (slowly, so it doesn't disturb what's playing; a dropped download
  carries on where it stopped), checks it, and the next time he is on the home screen shows
  "区tv 有新版本". OK opens Android's installer, where he chooses Install and then Open. Back
  puts it off until the next day or the next time the app starts. Android always asks for that
  confirmation; an app can't install silently.
- **To send out a new app version:** raise `appVersionCode` in `android/app/build.gradle` by one
  and push. Without that, a changed app is built but boxes keep the one they have (the build
  prints a warning).
- **The signing key.** Android only lets an app be replaced by one signed with the same key. The
  key is in `signing/` on Andy's PC (not in this repository) and in the repository's secrets
  `SIGNING_KEYSTORE_B64` and `SIGNING_PASSWORD`, which the build uses. Keep a copy of the
  `signing` folder somewhere safe: if the key is lost, every box needs one more uninstall and
  reinstall. Set the secrets again with `gh secret set -f signing/secrets.env`.
- Self-updating needs Android 7 or newer. On Android 8 and newer the box asks once to allow
  区tv to install apps.

## Publishing (one-time)

`.github/workflows/build.yml` runs on GitHub's servers. It:
- publishes the page to GitHub Pages,
- builds `dads-tv.apk` (the app loads the published page, so channel and catalog changes reach
  Dad's TV without reinstalling), signed with the permanent key, and publishes `version.json`,
- refreshes the drama/movie catalog every Monday.

## Maintenance

- `python tools/check_streams.py`: check which direct streams still work.
- `python tools/build_catalog.py`: rebuild the drama/movie list and episode lists now (the workflow does it weekly).
- `channels.js`: add, remove or reorder channels. Each channel can have several lines; add more
  to make auto-switching more useful.
- To try it on a PC: `python -m http.server 8765` in this folder, then open http://localhost:8765.
  CCTV channels leave the page when opened in a normal browser; inside the TV app they show
  full screen.

## Known limits

- **Ads:** the TV app has [uBlock Origin](https://github.com/gorhill/uBlock) built in (fetched
  at build time, see `android/app/build.gradle`), limited to the lists YouTube needs, so YouTube's
  adverts are skipped on the YouTube channels and dramas. Using an ad blocker is against YouTube's
  terms of service, and it can stop working when YouTube changes its player; raising the uBlock
  Origin version in `build.gradle` is the fix. CCTV's and TVB's own pages are left untouched, and
  commercial breaks that are part of a TV channel can't be removed. The page opened in an ordinary
  browser still shows YouTube's adverts.
- **TVB Jade/Pearl live** have no free official stream outside Hong Kong, so they aren't included.
  TVB's full dramas are in the 点播 section.
- **CCTV pages** are CCTV's own site. If CCTV redesigns it, the full-screen tweak in
  `MainActivity.java` (`CCTV_FULLSCREEN_JS`) may need updating.

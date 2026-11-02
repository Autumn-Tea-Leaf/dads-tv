// Built-in extension script (see manifest.json) run on the broadcasters' official live pages
// (CCTV, TVB News).
// Hides everything except the video, stretches it over the whole screen, and makes sure it
// plays with sound. These sites build their pages with JavaScript after loading and may
// rebuild them, so the style is re-applied every half second for the first minute.
(function () {
  if (window.__dadtvFullscreen) return;
  window.__dadtvFullscreen = true;

  // Keep streams at 720p or below. TVB offers 1080p (H.264 High profile, level 4.2) and 720p
  // (Main profile); old TV-box video chips can lose the picture on the 1080p one while the sound
  // carries on. This runs in the page itself (a <script>), removes variants taller than 720 from
  // any HLS master playlist the site's player downloads (fetch or XMLHttpRequest), and only if
  // a 720p-or-lower variant remains.
  function capStreams() {
    function cap(text) {
      if (text.indexOf('#EXT-X-STREAM-INF') < 0) return text;
      var lines = text.split('\n'), out = [], kept = 0, dropped = 0;
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        if (line.indexOf('#EXT-X-STREAM-INF') === 0) {
          var m = /RESOLUTION=\d+x(\d+)/.exec(line);
          if (m && +m[1] > 720) {
            dropped++;
            while (i + 1 < lines.length && (lines[i + 1].trim() === '' || lines[i + 1].charAt(0) === '#')) i++;
            i++;  // the variant's playlist address
            continue;
          }
          kept++;
        }
        out.push(line);
      }
      return kept && dropped ? out.join('\n') : text;
    }
    var isPlaylist = function (url) { return /\.m3u8(\?|$)/.test(url || ''); };

    var realFetch = window.fetch;
    if (realFetch) {
      window.fetch = function (input) {
        return realFetch.apply(this, arguments).then(function (r) {
          var url = r.url || (typeof input === 'string' ? input : input && input.url);
          if (!r.ok || !isPlaylist(url)) return r;
          return r.clone().text().then(function (t) {
            var c = cap(t);
            if (c === t) return r;
            var copy = new Response(c, { status: r.status, statusText: r.statusText, headers: r.headers });
            try { Object.defineProperty(copy, 'url', { value: r.url }); } catch (e) { /* keep going */ }
            return copy;
          });
        });
      };
    }

    var P = XMLHttpRequest.prototype, realOpen = P.open;
    P.open = function (method, url) {
      this.__dadtvUrl = String(url);
      return realOpen.apply(this, arguments);
    };
    ['responseText', 'response'].forEach(function (name) {
      var d = Object.getOwnPropertyDescriptor(P, name);
      if (!d || !d.get) return;
      Object.defineProperty(P, name, {
        configurable: true,
        get: function () {
          var v = d.get.call(this);
          return this.readyState === 4 && typeof v === 'string' && isPlaylist(this.__dadtvUrl || this.responseURL)
            ? cap(v) : v;
        }
      });
    });
  }
  var pageScript = document.createElement('script');
  pageScript.textContent = '(' + capStreams.toString() + ')();';
  (document.head || document.documentElement).appendChild(pageScript);
  pageScript.parentNode.removeChild(pageScript);

  // The element to enlarge: CCTV's whole player box, or just the video elsewhere.
  var p = /(^|\.)cctv\.com$/.test(location.hostname) ? '#player' : 'video';
  var css =
    'html,body{overflow:hidden!important;background:#000!important}' +
    'body *{visibility:hidden!important}' +
    p + ',' + p + ' *{visibility:visible!important}' +
    p + '{position:fixed!important;left:0!important;top:0!important;width:100vw!important;' +
    'height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;' +
    'z-index:2147483646!important;background:#000!important;transform:none!important}' +
    p + ' video{width:100%!important;height:100%!important}' +
    'video{object-fit:contain!important}';

  var ticks = 0;
  function apply() {
    var root = document.head || document.documentElement;
    if (root && !document.getElementById('dadtv-fullscreen')) {
      var s = document.createElement('style');
      s.id = 'dadtv-fullscreen';
      s.textContent = css;
      root.appendChild(s);
    }
    var v = document.querySelector('video');
    if (v && ticks < 30) {  // first 15 seconds: start playback and turn the sound on
      if (v.paused) v.play().catch(function () {});
      if (v.muted && !v.paused) { v.muted = false; v.volume = 1; }
    }
  }

  // If nothing is playing after 12 s, say why on screen, so a photo of the TV shows the cause.
  function explain() {
    var v = document.querySelector('video');
    if (v && !v.paused && v.currentTime > 0) return;
    var chrome = (/Chrome\/(\d+)/.exec(navigator.userAgent) || [])[1] || '?';
    var why = !v ? 'no video element (the site\'s player did not load)'
      : v.error ? 'video error ' + v.error.code + (v.error.message ? ': ' + v.error.message : '')
      : 'video ' + (v.paused ? 'paused' : 'stalled') + ', readyState ' + v.readyState + ', networkState ' + v.networkState;
    var note = document.createElement('div');
    note.style.cssText = 'position:fixed;left:3%;bottom:6%;z-index:2147483647;visibility:visible!important;' +
      'background:rgba(0,0,0,.85);color:#fff;border-left:6px solid #ff7b5c;padding:14px 22px;' +
      'font:22px/1.5 sans-serif;border-radius:10px;max-width:80%';
    note.textContent = '视频没有开始播放 / Video did not start — ' + why + ' — engine Chrome ' + chrome;
    document.documentElement.appendChild(note);
    if (window.console) console.warn('dadtv: ' + note.textContent);
  }

  // Video status in the app log every 3 s, read by the automated device test.
  function logState() {
    var v = document.querySelector('video');
    console.log('DADTV_VIDEO ' + JSON.stringify(v ? {
      video: true, playing: !v.paused && v.currentTime > 0, t: Math.round(v.currentTime),
      w: v.videoWidth, readyState: v.readyState, error: v.error && v.error.code,
      fullscreen: Math.round(v.getBoundingClientRect().width) >= window.innerWidth - 2
    } : { video: false }));
  }

  // Every half second for the first minute, then every 2 s for as long as the page is open: a
  // site may rebuild its page or swap its player at any time, and full screen must survive it.
  apply();
  var timer = setInterval(function () {
    ticks++;
    apply();
    if (ticks === 24) explain();
    if (ticks % 6 === 0) logState();
    if (ticks >= 120) {
      clearInterval(timer);
      setInterval(function () { ticks++; apply(); if (ticks % 5 === 0) logState(); }, 2000);
    }
  }, 500);
})();

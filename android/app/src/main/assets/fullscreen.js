// Injected by the TV app into the broadcasters' official live pages (CCTV, TVB News).
// Hides everything except the video, stretches it over the whole screen, and makes sure it
// plays with sound. These sites build their pages with JavaScript after loading and may
// rebuild them, so the style is re-applied every half second for the first minute.
(function () {
  if (window.__dadtvFullscreen) return;
  window.__dadtvFullscreen = true;

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

  apply();
  var timer = setInterval(function () {
    ticks++;
    apply();
    if (ticks > 120) clearInterval(timer);
  }, 500);
})();

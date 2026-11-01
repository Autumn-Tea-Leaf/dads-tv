(() => {
  'use strict';

  // How patient the live player is before it gives up on a line and tries the next one.
  const START_TIMEOUT_MS = 20000;  // no picture at all after this long
  const STALL_LIMIT_MS = 7000;     // a single freeze longer than this
  const STALL_COUNT = 3;           // or this many freezes...
  const STALL_WINDOW_MS = 60000;   // ...within one minute
  const RETRY_ROUNDS = 2;          // passes through every line before showing "can't play"

  const SEEK_SECONDS = 30;
  const HISTORY_MAX = 12;

  const channels = window.CHANNEL_GROUPS.flatMap((g) => g.channels);
  let catalog = null;

  const $ = (id) => document.getElementById(id);
  const screens = { home: $('home'), browse: $('browse'), player: $('player') };
  const video = $('video'), ytBox = $('yt'), webBox = $('web');
  const osd = $('osd'), statusEl = $('status'), bar = $('bar'), hint = $('hint');
  const searchBox = $('searchBox');

  const store = {
    get(k, d) { try { const v = localStorage.getItem('dadtv.' + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem('dadtv.' + k, JSON.stringify(v)); } catch { /* private mode */ } },
  };

  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const clock = (sec) => {
    sec = Math.max(0, Math.floor(sec || 0));
    const h = Math.floor(sec / 3600), m = Math.floor(sec / 60) % 60, s = sec % 60;
    return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(s).padStart(2, '0');
  };

  // ---------------------------------------------------------------- screens

  // A stack of screens mirrored into browser history, so the remote's Back button works.
  const stack = ['home'];
  const focusMemory = {};

  const current = () => stack[stack.length - 1];

  function show(name) {
    for (const [k, el] of Object.entries(screens)) el.classList.toggle('on', k === name);
  }

  function pushScreen(name) {
    if (current() === name) return;
    stack.push(name);
    history.pushState({ depth: stack.length }, '');
    show(name);
  }

  function goBack() {
    if (stack.length > 1) history.back();
  }

  window.addEventListener('popstate', () => {
    if (stack.length < 2) return;
    const leaving = stack.pop();
    if (leaving === 'player') stopPlayback();
    show(current());
    if (current() === 'home') renderContinue();
    restoreFocus(current());
  });

  document.addEventListener('focusin', (e) => {
    const scr = current();
    if (scr !== 'player' && screens[scr].contains(e.target)) focusMemory[scr] = e.target;
  });

  function restoreFocus(scr) {
    const el = focusMemory[scr];
    if (el && el.isConnected && !el.closest('.hidden')) focusEl(el);
    else focusEl(focusables(scr)[0]);
  }

  const focusables = (scr) => [...screens[scr].querySelectorAll('.tile, .card, input')].filter((el) => !el.closest('.hidden'));

  function focusEl(el) {
    if (!el) return;
    el.focus({ preventScroll: true });
    el.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }

  // Remote-control navigation: jump to the nearest item in the pressed direction.
  function moveFocus(dir) {
    const items = focusables(current());
    const cur = items.includes(document.activeElement) ? document.activeElement : null;
    if (!cur) return focusEl(items[0]);
    const r = cur.getBoundingClientRect();
    const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    let best = null, bestScore = Infinity;
    for (const t of items) {
      if (t === cur) continue;
      const q = t.getBoundingClientRect();
      const dx = q.left + q.width / 2 - cx, dy = q.top + q.height / 2 - cy;
      const [along, across] = { right: [dx, dy], left: [-dx, dy], down: [dy, dx], up: [-dy, dx] }[dir];
      if (along < 10) continue;
      const score = along + Math.abs(across) * 2;
      if (score < bestScore) { bestScore = score; best = t; }
    }
    focusEl(best);
  }

  // ---------------------------------------------------------------- home screen

  function buildHome() {
    const root = $('groups');
    let index = 0;
    for (const g of window.CHANNEL_GROUPS) {
      const sec = document.createElement('section');
      if (g.color) sec.style.setProperty('--c', g.color);
      sec.innerHTML = `<h2><span class="icon">${g.icon || ''}</span>${esc(g.name)}</h2><div class="row"></div>`;
      const row = sec.querySelector('.row');
      for (const ch of g.channels) {
        const i = index++;
        const t = makeTile(ch.name, ch.sub, () => openLive(i), { index: i });
        t.insertAdjacentHTML('beforeend', `<span class="num">${String(i + 1).padStart(2, '0')}</span>`);
        row.appendChild(t);
      }
      root.appendChild(sec);
    }
  }

  function makeTile(name, sub, onClick, data = {}) {
    const b = document.createElement('button');
    b.className = 'tile';
    Object.assign(b.dataset, data);
    b.innerHTML = `<span class="name">${esc(name)}</span><span class="sub">${esc(sub || '')}</span>`;
    b.addEventListener('click', onClick);
    return b;
  }

  // "上次看" pill on the channel he watched last, in place of its number.
  function markLastWatched(idx) {
    document.querySelectorAll('.tile .badge').forEach((b) => b.remove());
    const t = tileFor(idx);
    if (t) t.insertAdjacentHTML('beforeend', '<span class="badge">上次看</span>');
  }

  function buildVodTiles() {
    const row = document.querySelector('#vodGroup .row');
    row.innerHTML = '';
    if (!catalog) {
      row.appendChild(makeTile('片库', '加载失败，请稍后再试', loadCatalog));
      return;
    }
    row.appendChild(makeTile('🔍 搜索', '按片名找电视剧、电影', openSearch));
    for (const p of catalog.providers) {
      if (!p.items.length) continue;
      row.appendChild(makeTile(p.name, `${p.sub} · ${p.items.length} 部`, () => openProvider(p)));
    }
  }

  function renderContinue() {
    const sec = $('continue');
    const hist = store.get('history', []);
    sec.classList.toggle('hidden', !hist.length);
    renderCards(sec.querySelector('.cards'), hist);
  }

  function showHomeHint() {
    const last = channels.findIndex((c) => c.id === store.get('last', null));
    if (last >= 0) {
      hint.innerHTML = `上次看的是 <b>${esc(channels[last].name)}</b>，按 <b>OK</b> 接着看`;
      markLastWatched(last);
      focusEl(tileFor(last));
    } else {
      hint.innerHTML = '用遥控器 <b>↑ ↓ ← →</b> 选台，按 <b>OK</b> 观看';
      focusEl(focusables('home')[0]);
    }
  }

  const tileFor = (idx) => document.querySelector(`.tile[data-index="${idx}"]`);

  function greeting(h) {
    if (h < 5) return '夜深了';
    if (h < 11) return '早上好';
    if (h < 13) return '中午好';
    if (h < 18) return '下午好';
    return '晚上好';
  }

  function tickClock() {
    const d = new Date();
    $('greet').innerHTML = `${greeting(d.getHours())}，爸爸<small>今天想看点什么？</small>`;
    $('time').textContent = d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false });
    $('date').textContent = d.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' });
  }

  // ---------------------------------------------------------------- dramas & movies

  function loadCatalog() {
    return fetch('catalog.json')
      .then((r) => { if (!r.ok) throw new Error(r.status); return r.json(); })
      .then((c) => {
        catalog = c;
        for (const p of c.providers) for (const it of p.items) it.provider = p.name;
      })
      .catch(() => { catalog = null; })
      .then(buildVodTiles);
  }

  function renderCards(container, items) {
    container.innerHTML = '';
    const hist = new Map(store.get('history', []).map((h) => [h.id, h]));
    for (const it of items) {
      const h = hist.get(it.id);
      let progress = '', done = 0;
      if (h && h.type === 'playlist') {
        progress = `<em>看到第 ${h.index + 1} 集</em> · `;
        if (h.n > 1) done = (h.index + (h.d ? h.t / h.d : 0)) / h.n;
      } else if (h && h.t > 30) {
        progress = `<em>看到 ${clock(h.t)}</em> · `;
        if (h.d) done = h.t / h.d;
      }
      const b = document.createElement('button');
      b.className = 'card';
      b.innerHTML = `<img loading="lazy" alt="" src="${esc(it.thumb)}">
        ${done > 0 ? `<div class="bar"><i style="width:${Math.min(100, done * 100).toFixed(1)}%"></i></div>` : ''}
        <div class="t">${esc(it.title)}</div>
        <div class="i">${progress}${esc(it.info || '')}${it.provider ? ' · ' + esc(it.provider) : ''}</div>`;
      b.addEventListener('click', () => playVod(it));
      container.appendChild(b);
    }
  }

  function openProvider(p) {
    $('browseTitle').textContent = p.name;
    searchBox.classList.add('hidden');
    $('browseMsg').textContent = '';
    renderCards($('cards'), p.items);
    pushScreen('browse');
    screens.browse.scrollTop = 0;
    focusEl(focusables('browse')[0]);
  }

  function openSearch() {
    $('browseTitle').textContent = '搜索';
    searchBox.classList.remove('hidden');
    searchBox.value = '';
    $('browseMsg').textContent = '可以用简体或繁体，例如：神雕侠侣、康熙王朝、学警';
    $('cards').innerHTML = '';
    pushScreen('browse');
    screens.browse.scrollTop = 0;
    searchBox.focus();
  }

  // Titles carry a simplified-Chinese key; the query is converted with the catalog's map.
  function toSimple(s) {
    const map = (catalog && catalog.t2s) || {};
    return [...s].map((c) => map[c] || c).join('').toLowerCase();
  }

  function runSearch() {
    const terms = toSimple(searchBox.value.trim()).split(/\s+/).filter(Boolean);
    if (!terms.length || !catalog) { $('cards').innerHTML = ''; return; }
    const results = [];
    for (const p of catalog.providers) {
      for (const it of p.items) {
        const key = it.key || it.title.toLowerCase();
        if (terms.every((t) => key.includes(t))) results.push(it);
      }
    }
    $('browseMsg').textContent = results.length ? `找到 ${results.length} 部` : '没有找到，换个词试试';
    renderCards($('cards'), results.slice(0, 150));
  }

  let searchTimer;
  searchBox.addEventListener('input', () => { clearTimeout(searchTimer); searchTimer = setTimeout(runSearch, 300); });

  function remember(entry) {
    const hist = store.get('history', []).filter((h) => h.id !== entry.id);
    hist.unshift(entry);
    store.set('history', hist.slice(0, HISTORY_MAX));
  }

  // ---------------------------------------------------------------- on-screen messages

  let osdTimer, statusTimer, barTimer;

  function flashOsd(title, sub) {
    osd.innerHTML = `${esc(title)}<small>${esc(sub)}</small>`;
    osd.classList.remove('fade');
    clearTimeout(osdTimer);
    osdTimer = setTimeout(() => osd.classList.add('fade'), 4000);
  }

  // loading: big centred card with a spinner (connecting); spin: small pill with a spinner (buffering)
  function setStatus(text, { warn = false, ms = 0, loading = false, spin = loading } = {}) {
    clearTimeout(statusTimer);
    if (!text) { statusEl.classList.add('hidden'); return; }
    statusEl.innerHTML = (spin ? '<span class="spin"></span>' : '') + `<span>${esc(text)}</span>`;
    statusEl.classList.toggle('warn', warn);
    statusEl.classList.toggle('loading', loading);
    statusEl.classList.remove('hidden');
    if (ms) statusTimer = setTimeout(() => statusEl.classList.add('hidden'), ms);
  }

  const BAR = {
    live: [['back', '◀ 返回'], ['prevCh', '▲ 上一台'], ['nextCh', '▼ 下一台'], ['line', '⇄ 换线路']],
    vod: [['back', '◀ 返回'], ['prevEp', '▲ 上一集'], ['rew', '⏪ 30秒'], ['pause', '⏯ 暂停'], ['fwd', '30秒 ⏩'], ['nextEp', '▼ 下一集']],
  };

  function setBar(mode) {
    bar.innerHTML = BAR[mode].map(([act, label]) => `<button data-act="${act}">${label}</button>`).join('');
  }

  function showBar() {
    bar.classList.remove('fade');
    clearTimeout(barTimer);
    barTimer = setTimeout(() => bar.classList.add('fade'), 4000);
  }

  function showLayer(which) {
    video.classList.toggle('hidden', which !== 'video');
    ytBox.classList.toggle('hidden', which !== 'yt');
    webBox.classList.toggle('hidden', which !== 'web');
  }

  function clearMedia() {
    video.pause();
    video.removeAttribute('src');
    video.load();
    ytBox.innerHTML = '';
    webBox.innerHTML = '';
  }

  function stopPlayback() {
    stopLive();
    stopVod();
    clearMedia();
    setStatus('');
  }

  // ---------------------------------------------------------------- live channels

  // One session per channel being watched. `attempt` increments every time a line is
  // (re)started, so callbacks from an abandoned line can tell they are stale.
  let session = null;

  function openLive(idx) {
    const ch = channels[idx];
    if (ch.sources[0].type === 'web') {
      // Goes straight to CCTV's page, so Back from there lands on this list, not an empty player.
      store.set('last', ch.id);
      openPage(ch.sources[0].url);
      return;
    }
    pushScreen('player');
    setBar('live');
    playChannel(idx);
  }

  function playChannel(idx) {
    stopPlayback();
    const ch = channels[idx];
    store.set('last', ch.id);
    const good = store.get('good.' + ch.id, 0);
    const first = good < ch.sources.length ? good : 0;
    const order = [first, ...ch.sources.map((_, i) => i).filter((i) => i !== first)];
    session = { ch, idx, order, pos: 0, round: 0, attempt: 0 };
    startLine();
  }

  function stopLive() {
    if (!session) return;
    teardownLine(session);
    session = null;
  }

  function teardownLine(s) {
    clearInterval(s.timer);
    clearTimeout(s.retryTimer);
    if (s.hls) { s.hls.destroy(); s.hls = null; }
    if (s.yt) { try { s.yt.destroy(); } catch { /* already gone */ } s.yt = null; }
    clearMedia();
  }

  function showLiveOsd() {
    const { ch, idx, order, pos } = session;
    const n = ch.sources.length;
    flashOsd(`${idx + 1}. ${ch.name}`, `${ch.sub || ''}${n > 1 ? ` · 线路 ${order[pos] + 1}/${n}` : ''}`);
  }

  function startLine() {
    const s = session;
    teardownLine(s);
    const attempt = ++s.attempt;
    Object.assign(s, { startedAt: Date.now(), played: false, stallStart: 0, stalls: [], recovered: false, mutedTry: false });
    const src = s.ch.sources[s.order[s.pos]];
    s.kind = src.type;
    showLiveOsd();
    setStatus(`正在打开 ${s.ch.name}…`, { loading: true });
    s.timer = setInterval(() => watchdog(s, attempt), 1000);
    if (src.type === 'hls') startHls(s, attempt, src.url);
    else if (src.type === 'web') startWeb(s, attempt, src.url);
    else startYt(s, attempt, src);
  }

  const isCurrent = (s, attempt) => session === s && s.attempt === attempt;

  function watchdog(s, attempt) {
    if (!isCurrent(s, attempt)) return;
    const now = Date.now();
    if (!s.played) {
      if (s.yt) tryMutedYt(s, now - s.startedAt);
      if (now - s.startedAt > START_TIMEOUT_MS) nextLine('连接不上');
      return;
    }
    if (s.stallStart && now - s.stallStart > STALL_LIMIT_MS) handleLag();
  }

  // YouTube may refuse to autoplay with sound; retry muted once and ask for OK to unmute.
  function tryMutedYt(state, waited) {
    if (state.mutedTry || waited < 4000 || !state.yt.getPlayerState) return;
    const st = state.yt.getPlayerState();
    if (st === -1 || st === 2 || st === 5) {
      state.mutedTry = true;
      state.needsUnmute = true;
      state.yt.mute();
      state.yt.playVideo();
    }
  }

  function onWaiting() {
    const s = session;
    if (!s || !s.played) return;  // buffering before the first frame is covered by START_TIMEOUT_MS
    const now = Date.now();
    s.stallStart = now;
    s.stalls = s.stalls.filter((t) => now - t < STALL_WINDOW_MS).concat(now);
    setStatus('缓冲中…', { spin: true });
    if (s.stalls.length >= STALL_COUNT) handleLag();
  }

  function onPlaying() {
    const s = session;
    if (!s) return;
    s.stallStart = 0;
    if (!s.played) {
      s.played = true;
      s.round = 0;
      store.set('good.' + s.ch.id, s.order[s.pos]);
      const t = tileFor(s.idx);
      if (t) t.classList.remove('down');
    }
    if (s.needsUnmute) setStatus('按 OK 键打开声音', { ms: 6000 });
    else setStatus('');
  }

  // Lagging: first try a lower picture quality on the same line, then move to the next line.
  function handleLag() {
    const s = session;
    if (s.hls && lowerQuality(s.hls)) {
      s.stalls = [];
      s.stallStart = 0;
      setStatus('网络慢，已自动降低画质', { ms: 4000 });
      return;
    }
    nextLine('太卡了');
  }

  function lowerQuality(h) {
    if (!h.levels || h.levels.length < 2) return false;
    const now = h.currentLevel >= 0 ? h.currentLevel : h.loadLevel;
    if (now <= 0) return false;
    h.autoLevelCapping = now - 1;
    h.currentLevel = now - 1;
    return true;
  }

  function nextLine(reason) {
    const s = session;
    if (!s) return;
    const n = s.order.length;
    s.pos += 1;
    if (s.pos >= n) {
      s.pos = 0;
      s.round += 1;
      if (s.round >= RETRY_ROUNDS) return giveUp(s);
    }
    teardownLine(s);
    const attempt = ++s.attempt;  // invalidate callbacks from the line we just left
    setStatus(n > 1 ? `${reason}，正在换线路 ${s.order[s.pos] + 1}/${n}…` : `${reason}，正在重新连接…`, { loading: true });
    s.retryTimer = setTimeout(() => { if (isCurrent(s, attempt)) startLine(); }, 1500);
  }

  function giveUp(s) {
    teardownLine(s);
    const t = tileFor(s.idx);
    if (t) t.classList.add('down');
    setStatus('这个频道现在看不了，请按 ▲▼ 换台', { warn: true });
  }

  function switchLineManually(step) {
    const s = session;
    if (!s) return;
    const n = s.order.length;
    if (n < 2) { setStatus('这个频道只有一条线路', { ms: 2500 }); return; }
    s.pos = (s.pos + step + n) % n;
    s.round = 0;
    startLine();
  }

  function changeChannel(step) {
    const cur = session ? session.idx : 0;
    playChannel((cur + step + channels.length) % channels.length);
  }

  // ---- HLS

  function startHls(s, attempt, url) {
    showLayer('video');
    if (window.Hls && Hls.isSupported()) {
      const h = new Hls({
        liveSyncDurationCount: 3,
        maxBufferLength: 30,
        capLevelToPlayerSize: true,
        manifestLoadingMaxRetry: 2,
        levelLoadingMaxRetry: 3,
        fragLoadingMaxRetry: 3,
      });
      s.hls = h;
      h.on(Hls.Events.ERROR, (_, d) => {
        if (!isCurrent(s, attempt) || !d.fatal) return;
        if (d.type === Hls.ErrorTypes.MEDIA_ERROR && !s.recovered) {
          s.recovered = true;
          h.recoverMediaError();
          return;
        }
        nextLine('信号中断');
      });
      h.on(Hls.Events.MANIFEST_PARSED, () => { if (isCurrent(s, attempt)) tryPlayVideo(s); });
      h.loadSource(url);
      h.attachMedia(video);
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = url;
      tryPlayVideo(s);
    } else {
      nextLine('这台设备不支持');
    }
  }

  function tryPlayVideo(s) {
    video.muted = false;
    video.play().catch(() => {
      video.muted = true;
      s.needsUnmute = true;
      video.play().catch(() => {});
    });
  }

  video.addEventListener('waiting', () => { if (session && session.kind === 'hls') onWaiting(); });
  video.addEventListener('playing', () => { if (session && session.kind === 'hls') onPlaying(); });
  video.addEventListener('error', () => {
    if (session && session.kind === 'hls' && !session.hls && video.getAttribute('src')) nextLine('信号中断');
  });

  // ---- CCTV's own live page
  // CCTV refuses to play inside another site's page, so we open its page directly.
  // Inside the TV app (window.DadTV) the app shows it full-screen; Back returns here.

  function startWeb(s, attempt, url) {
    clearInterval(s.timer);  // nothing to watch: playback happens on CCTV's page
    setStatus('正在打开官网直播…', { loading: true });
    setTimeout(() => { if (isCurrent(s, attempt)) openPage(url); }, 600);
  }

  function openPage(url) {
    if (window.DadTV && window.DadTV.openPage) window.DadTV.openPage(url);
    else location.assign(url);
  }

  // ---- YouTube (shared by live channels and dramas/movies)

  let ytApi;
  function loadYtApi() {
    if (!ytApi) {
      ytApi = new Promise((resolve, reject) => {
        window.onYouTubeIframeAPIReady = resolve;
        const tag = document.createElement('script');
        tag.src = 'https://www.youtube.com/iframe_api';
        tag.onerror = () => { ytApi = null; reject(new Error('yt api')); };
        document.head.appendChild(tag);
      });
    }
    return ytApi;
  }

  const YT_VARS = { autoplay: 1, controls: 0, rel: 0, playsinline: 1, iv_load_policy: 3, disablekb: 1, fs: 0 };

  function createYt(opts) {
    showLayer('yt');
    const el = document.createElement('div');
    ytBox.appendChild(el);
    return new YT.Player(el, { width: '100%', height: '100%', ...opts });
  }

  function startYt(s, attempt, src) {
    showLayer('yt');
    loadYtApi().then(() => {
      if (!isCurrent(s, attempt)) return;
      const playerVars = { ...YT_VARS };
      if (src.channel) playerVars.channel = src.channel;
      s.yt = createYt({
        videoId: src.video || 'live_stream',
        playerVars,
        events: {
          onReady: (e) => {
            if (!isCurrent(s, attempt)) return;
            e.target.getIframe().tabIndex = -1;
            e.target.playVideo();
          },
          onStateChange: (e) => {
            if (!isCurrent(s, attempt)) return;
            if (e.data === YT.PlayerState.PLAYING) onPlaying();
            else if (e.data === YT.PlayerState.BUFFERING) onWaiting();
            else if (e.data === YT.PlayerState.ENDED) nextLine('直播结束');
          },
          onError: () => { if (isCurrent(s, attempt)) nextLine('线路不可用'); },
        },
      });
    }).catch(() => { if (isCurrent(s, attempt)) nextLine('YouTube 打不开'); });
  }

  // ---------------------------------------------------------------- dramas & movies playback

  let vod = null;

  function playVod(item) {
    pushScreen('player');
    setBar('vod');
    stopPlayback();
    const h = store.get('history', []).find((x) => x.id === item.id);
    const state = vod = { item, errors: 0, startedAt: Date.now(), resumed: false, resume: h };
    flashOsd(item.title, item.provider || '');
    setStatus('正在加载…', { loading: true });
    loadYtApi().then(() => {
      if (vod !== state) return;
      const playerVars = { ...YT_VARS };
      const opts = { playerVars };
      if (item.type === 'playlist') {
        playerVars.listType = 'playlist';
        playerVars.list = item.id;
        if (h) playerVars.index = h.index || 0;
      } else {
        opts.videoId = item.id;
        if (h && h.t > 30) playerVars.start = Math.floor(h.t - 5);
      }
      state.yt = createYt({
        ...opts,
        events: {
          onReady: (e) => { if (vod === state) { e.target.getIframe().tabIndex = -1; e.target.playVideo(); } },
          onStateChange: (e) => { if (vod === state) onVodState(state, e.data); },
          onError: () => { if (vod === state) onVodError(state); },
        },
      });
      state.timer = setInterval(() => vodTick(state), 1000);
    }).catch(() => setStatus('YouTube 打不开，请检查网络', { warn: true }));
  }

  function onVodState(state, st) {
    const p = state.yt;
    if (st === YT.PlayerState.PLAYING) {
      state.errors = 0;
      state.played = true;
      // A playlist resumes at the right episode via `index`; seek within it once.
      const r = state.resume;
      if (!state.resumed && r && state.item.type === 'playlist' && p.getPlaylistIndex() === r.index && r.t > 30) {
        p.seekTo(r.t - 5, true);
      }
      state.resumed = true;
      setStatus(state.needsUnmute ? '按 OK 键打开声音' : '', { ms: state.needsUnmute ? 6000 : 0 });
      showVodOsd();
    } else if (st === YT.PlayerState.BUFFERING) {
      setStatus('缓冲中…', { spin: true });
    } else if (st === YT.PlayerState.ENDED) {
      const list = p.getPlaylist && p.getPlaylist();
      if (!list || p.getPlaylistIndex() >= list.length - 1) setStatus('全部播完了，按返回选别的节目', { ms: 8000 });
    }
  }

  function onVodError(state) {
    state.errors += 1;
    const list = state.yt.getPlaylist && state.yt.getPlaylist();
    if (list && list.length > 1 && state.errors < 4) {
      setStatus('这一集不能在这里播放，跳到下一集…');
      setTimeout(() => { if (vod === state) state.yt.nextVideo(); }, 1500);
    } else {
      setStatus('这个节目不能在这里播放，请按返回选别的', { warn: true });
    }
  }

  function vodTick(state) {
    if (vod !== state || !state.yt.getPlayerState) return;
    if (!state.played) tryMutedYt(state, Date.now() - state.startedAt);
    if (state.yt.getPlayerState() === YT.PlayerState.PLAYING && Date.now() % 10000 < 1000) saveVod(state);
  }

  function saveVod(state) {
    const p = state.yt;
    if (!p || !p.getCurrentTime || !state.played) return;
    const it = state.item;
    remember({
      id: it.id, type: it.type, title: it.title, thumb: it.thumb, info: it.info, provider: it.provider,
      index: it.type === 'playlist' ? Math.max(0, p.getPlaylistIndex()) : 0,
      n: (p.getPlaylist && p.getPlaylist() || []).length,  // episodes, for the progress bar
      t: p.getCurrentTime(),
      d: p.getDuration(),
    });
  }

  function stopVod() {
    if (!vod) return;
    saveVod(vod);
    clearInterval(vod.timer);
    try { vod.yt && vod.yt.destroy(); } catch { /* already gone */ }
    vod = null;
  }

  function showVodOsd() {
    if (!vod || !vod.yt || !vod.yt.getCurrentTime) return;
    const p = vod.yt;
    const list = p.getPlaylist && p.getPlaylist();
    const ep = list && list.length > 1 ? `第 ${p.getPlaylistIndex() + 1} / ${list.length} 集 · ` : '';
    flashOsd(vod.item.title, `${ep}${clock(p.getCurrentTime())} / ${clock(p.getDuration())}`);
  }

  function vodSeek(delta) {
    const p = vod && vod.yt;
    if (!p || !p.getCurrentTime) return;
    p.seekTo(Math.max(0, p.getCurrentTime() + delta), true);
    setTimeout(showVodOsd, 300);
  }

  function vodEpisode(step) {
    const p = vod && vod.yt;
    if (!p || !p.getPlaylist || !p.getPlaylist()) { setStatus('这个节目只有一集', { ms: 2500 }); return; }
    if (step > 0) p.nextVideo(); else p.previousVideo();
    setStatus(step > 0 ? '下一集…' : '上一集…', { ms: 2000 });
  }

  function vodTogglePause() {
    const p = vod && vod.yt;
    if (!p || !p.getPlayerState) return;
    if (p.getPlayerState() === YT.PlayerState.PLAYING) { p.pauseVideo(); setStatus('已暂停，按 OK 继续'); }
    else { p.playVideo(); setStatus(''); }
    showVodOsd();
  }

  function unmute() {
    const st = vod || session;
    if (!st || !st.needsUnmute) return false;
    st.needsUnmute = false;
    if (st.yt && st.yt.unMute) st.yt.unMute();
    video.muted = false;
    setStatus('');
    return true;
  }

  // ---------------------------------------------------------------- keys & buttons

  const BACK_KEYS = ['Escape', 'Backspace', 'GoBack', 'BrowserBack'];

  const ACTIONS = {
    back: goBack,
    prevCh: () => changeChannel(-1),
    nextCh: () => changeChannel(1),
    line: () => switchLineManually(1),
    prevEp: () => vodEpisode(-1),
    nextEp: () => vodEpisode(1),
    rew: () => vodSeek(-SEEK_SECONDS),
    fwd: () => vodSeek(SEEK_SECONDS),
    pause: vodTogglePause,
  };

  function playerKey(k) {
    if (BACK_KEYS.includes(k)) return goBack(), true;
    if (vod) {
      const map = { ArrowLeft: 'rew', ArrowRight: 'fwd', ArrowUp: 'prevEp', ArrowDown: 'nextEp',
        MediaRewind: 'rew', MediaFastForward: 'fwd', MediaTrackPrevious: 'prevEp', MediaTrackNext: 'nextEp',
        MediaPlayPause: 'pause' };
      if (map[k]) return ACTIONS[map[k]](), true;
      if (k === 'Enter' || k === ' ') { if (!unmute()) vodTogglePause(); return true; }
      return false;
    }
    if (k === 'ArrowUp' || k === 'PageUp' || k === 'ChannelUp') changeChannel(-1);
    else if (k === 'ArrowDown' || k === 'PageDown' || k === 'ChannelDown') changeChannel(1);
    else if (k === 'ArrowRight') switchLineManually(1);
    else if (k === 'ArrowLeft') switchLineManually(-1);
    else if (k === 'Enter' || k === ' ') { unmute(); if (session) showLiveOsd(); }
    else return false;
    return true;
  }

  document.addEventListener('keydown', (e) => {
    const k = e.key;
    if (current() === 'player') {
      showBar();
      if (playerKey(k)) e.preventDefault();
      return;
    }
    if (document.activeElement === searchBox) {
      // Let the box keep ←/→/Backspace for editing; ↓ or OK jumps to the results.
      if (k === 'ArrowDown' || k === 'Enter') {
        e.preventDefault();
        clearTimeout(searchTimer);
        runSearch();
        focusEl($('cards').querySelector('.card'));
      } else if (k === 'Escape' || k === 'GoBack' || k === 'BrowserBack') {
        e.preventDefault();
        goBack();
      }
      return;
    }
    const dirs = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
    if (dirs[k]) { e.preventDefault(); moveFocus(dirs[k]); }
    else if (BACK_KEYS.includes(k)) { e.preventDefault(); goBack(); }
  });

  bar.addEventListener('click', (e) => {
    const act = e.target.dataset && e.target.dataset.act;
    if (ACTIONS[act]) ACTIONS[act]();
  });
  screens.player.addEventListener('mousemove', () => { showBar(); if (vod) showVodOsd(); else if (session) showLiveOsd(); });
  screens.player.addEventListener('touchstart', () => showBar(), { passive: true });

  buildHome();
  buildVodTiles();
  renderContinue();
  tickClock();
  setInterval(tickClock, 10000);
  showHomeHint();
  loadCatalog();
})();

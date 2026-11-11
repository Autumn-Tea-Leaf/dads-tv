// Added to the bundled uBlock Origin by app/build.gradle (it is not part of uBlock Origin).
// Writes the blocker's state to the app log every 10 s ("DADTV_UBO {...}"), so the device test
// (tools/device_test.py) can tell whether it started and is filtering.
import ub from './background.js';
import netFilters from './static-net-filtering.js';
import scriptlets from './scriptlet-filtering.js';

const AD_URL = 'https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js';
let startup = 'pending', watching = false;
const problems = [];
self.addEventListener('error', (e) => problems.push(e.message + ' @' + e.filename + ':' + e.lineno));
self.addEventListener('unhandledrejection', (e) =>
  problems.push('rejection: ' + e.reason + ' ' + ((e.reason && e.reason.stack) || '')));

// What the engine itself sees of requests to the advert address the app's test page fetches.
const seen = { before: 0, completed: 0, failed: 0, last: null };
const adHost = { urls: ['*://pagead2.googlesyndication.com/*'] };
try {
  browser.webRequest.onBeforeRequest.addListener((d) => {
    seen.before++;
    seen.last = { tabId: d.tabId, type: d.type, from: (d.documentUrl || d.originUrl || '').slice(0, 50) };
  }, adHost);
  browser.webRequest.onCompleted.addListener(() => { seen.completed++; }, adHost);
  browser.webRequest.onErrorOccurred.addListener((d) => { seen.failed++; seen.error = d.error; }, adHost);
} catch (e) {
  problems.push('webRequest: ' + e);
}

const attempt = (fn) => { try { return fn(); } catch (e) { return 'error: ' + e; } };

const report = async () => {
  if (!watching && ub.isReadyPromise) {
    watching = true;
    ub.isReadyPromise.then(() => { startup = 'done'; },
      (e) => { startup = 'failed: ' + e + ' ' + ((e && e.stack) || ''); });
  }
  let tabs;
  try {
    tabs = (await browser.tabs.query({})).map((t) => {
      const store = attempt(() => ub.pageStoreFromTabId(t.id));
      return {
        id: t.id, url: (t.url || '').slice(0, 50),
        store: store && typeof store === 'object',
        filtering: store && typeof store === 'object' ? attempt(() => store.getNetFilteringSwitch()) : null,
      };
    });
  } catch (e) {
    tabs = 'error: ' + e;
  }
  console.log('DADTV_UBO ' + JSON.stringify({
    startup: startup,
    readyToFilter: ub.readyToFilter === true,
    filters: netFilters.getFilterCount(),
    lists: (ub.selectedFilterLists || []).join(' '),
    stats: ub.supportStats,
    requests: ub.requestStats,
    // 1 = the rules say "block" for the test advert address requested from an ordinary page
    ruleSaysBlock: attempt(() => netFilters.matchRequest(ub.filteringContext.duplicate().fromDetails(
      { originURL: 'https://example.github.io/', url: AD_URL, type: 'xmlhttprequest' }))),
    youtubeScripts: attempt(() => JSON.stringify(
      scriptlets.getContentScripts('https://www.youtube.com/embed/x') || null).length),
    seen: seen,
    problems: problems.slice(0, 5),
    tabs: tabs,
  }));
};
setTimeout(report, 3000);
setInterval(report, 10000);

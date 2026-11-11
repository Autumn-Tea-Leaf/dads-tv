// Added to the bundled uBlock Origin by app/build.gradle (it is not part of uBlock Origin).
// Writes the blocker's state to the app log every 10 s ("DADTV_UBO {...}"), so the device test
// (tools/device_test.py) can tell whether it started and is filtering.
import ub from './background.js';
import netFilters from './static-net-filtering.js';

let startup = 'pending', watching = false;
const problems = [];
self.addEventListener('error', (e) => problems.push(e.message + ' @' + e.filename + ':' + e.lineno));
self.addEventListener('unhandledrejection', (e) =>
  problems.push('rejection: ' + e.reason + ' ' + ((e.reason && e.reason.stack) || '')));

const report = async () => {
  if (!watching && ub.isReadyPromise) {
    watching = true;
    ub.isReadyPromise.then(() => { startup = 'done'; },
      (e) => { startup = 'failed: ' + e + ' ' + ((e && e.stack) || ''); });
  }
  let tabs;
  try {
    tabs = (await browser.tabs.query({})).map((t) => t.id + ' ' + t.status + ' ' + (t.url || '').slice(0, 60));
  } catch (e) {
    tabs = 'error: ' + e;
  }
  const nav = browser.webNavigation || {};
  console.log('DADTV_UBO ' + JSON.stringify({
    startup: startup,
    readyToFilter: ub.readyToFilter === true,
    filters: netFilters.getFilterCount(),
    lists: (ub.selectedFilterLists || []).length,
    stats: ub.supportStats,
    problems: problems.slice(0, 5),
    tabs: tabs,
    api: [typeof nav.onCreatedNavigationTarget, typeof nav.onBeforeNavigate, typeof nav.onCommitted,
      typeof (browser.contentScripts || {}).register, typeof (browser.tabs || {}).onRemoved],
  }));
};
setTimeout(report, 3000);
setInterval(report, 10000);

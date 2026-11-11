package tv.dad.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

/**
 * A full-screen browser locked to the TV page, running on GeckoView (Mozilla's engine) instead of
 * the box's own Android System WebView, which on old boxes is years out of date. The page does
 * all the work; this class only hosts it, lets the built-in extension make CCTV/TVB pages full
 * screen, and maps the remote's Back key.
 */
public class MainActivity extends Activity {

    private static final String TAG = "DadTV";
    private static final String BUNDLED_URL = "resource://android/assets/www/index.html";

    // Official live sites the page opens directly (CCTV won't play inside another site); the
    // built-in extension (assets/extension) makes their video fill the screen.
    private static final String[] LIVE_SITES = { "cctv.com", "news.tvb.com" };

    // Desktop Firefox, so CCTV shows its web player rather than pushing its phone app. The
    // "DadTV/2" token tells our page it's running inside the TV app.
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:143.0) Gecko/20100101 Firefox/143.0 DadTV/2";

    private static GeckoRuntime runtime;  // one per process

    private GeckoView view;
    private GeckoSession session;
    private View cover;              // black screen over CCTV/TVB pages until they're full screen
    private String currentUrl = "";  // page now showing (from onPageStart)
    private boolean canGoBack;       // the page has its own history to step back through
    private boolean usingBundled;    // the online page failed, so we're showing the built-in copy
    private boolean showingError;    // our error page is up; OK retries
    private boolean purgeOnHome;     // forget history once home has loaded (after a live site)
    private int onlineRetries;       // retries of the online page before using the built-in copy
    private boolean backDown;        // saw a fresh Back press; act on its release
    private boolean testMode;        // started by the device test (tools/device_test.py)
    private long lastBackAt;

    private final Runnable hideCover = () -> cover.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (runtime == null) {
            runtime = GeckoRuntime.create(getApplicationContext(), new GeckoRuntimeSettings.Builder()
                    .consoleOutput(true)  // page console -> logcat ("GeckoConsole"), for the device test
                    .build());
        }
        runtime.getWebExtensionController()
                .ensureBuiltIn("resource://android/assets/extension/", "fullscreen@dad.tv")
                .accept(ext -> Log.i(TAG, "full-screen extension ready"),
                        e -> Log.e(TAG, "full-screen extension failed", e));
        // uBlock Origin (see app/build.gradle): removes YouTube's adverts from the YouTube
        // channels and dramas. If it fails to load, everything still plays, with adverts.
        runtime.getWebExtensionController()
                .ensureBuiltIn("resource://android/assets/ublock/", "uBlock0@raymondhill.net")
                .accept(ext -> Log.i(TAG, "ad blocker ready"),
                        e -> Log.e(TAG, "ad blocker failed", e));

        session = new GeckoSession(new GeckoSessionSettings.Builder()
                .userAgentOverride(USER_AGENT)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)  // honour the page's width=1600
                .build());
        session.setNavigationDelegate(new Navigation());
        session.setProgressDelegate(new Progress());
        session.setPermissionDelegate(new Permissions());
        session.setContentDelegate(new Content());
        session.open(runtime);

        view = new GeckoView(this);
        view.setSession(session);
        cover = new View(this);
        cover.setBackgroundColor(Color.BLACK);
        cover.setVisibility(View.GONE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(18, 20, 26));
        root.addView(view);
        root.addView(cover);
        setContentView(root);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        view.setFocusable(true);
        view.requestFocus();  // without focus, the remote's keys never reach the page

        loadHome(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        loadHome(intent);
    }

    private String homeUrl() {
        return BuildConfig.APP_URL.isEmpty() || usingBundled ? BUNDLED_URL : BuildConfig.APP_URL;
    }

    private void loadHome() {
        loadHome(null);
    }

    /**
     * Load our page. An intent may carry a #fragment for the device test (e.g. "#test&focus=cgtn");
     * nothing else from outside is accepted.
     */
    private void loadHome(Intent intent) {
        showingError = false;
        String url = homeUrl();
        Uri data = intent == null ? null : intent.getData();
        String fragment = data == null ? null : data.getFragment();
        if (fragment != null && fragment.startsWith("test")) {
            testMode = true;
            // A changed #fragment alone wouldn't reload the page, so make the address unique.
            url += "?t=" + System.currentTimeMillis() + "#" + fragment;
        } else if (testMode) {
            url += "#test";  // keep the device test's state logging on after Back from CCTV/TVB
        }
        session.loadUri(url);
    }

    /** Our own page (online or built-in), as opposed to CCTV's or TVB's site. */
    private static boolean isAppPage(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.startsWith("resource://android/")
                || (!BuildConfig.APP_URL.isEmpty() && u.startsWith(BuildConfig.APP_URL.toLowerCase()));
    }

    private static boolean isLiveSite(String url) {
        String host = url == null ? null : Uri.parse(url).getHost();
        if (host == null) return false;
        for (String domain : LIVE_SITES) {
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    private class Navigation implements GeckoSession.NavigationDelegate {
        @Override
        public void onCanGoBack(GeckoSession s, boolean can) {
            canGoBack = can;
        }

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
            String uri = request.uri;
            // Sites sometimes try to open their phone app (cctv://, intent://...): refuse.
            if (!uri.startsWith("http") && !uri.startsWith("resource:") && !uri.startsWith("data:")
                    && !uri.startsWith("about:")) {
                Log.i(TAG, "blocked " + uri);
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
            if (isLiveSite(uri)) {
                cover.removeCallbacks(hideCover);
                cover.setVisibility(View.VISIBLE);  // hide the site until it's been made full screen
            }
            return GeckoResult.fromValue(AllowOrDeny.ALLOW);
        }

        @Override
        public GeckoResult<String> onLoadError(GeckoSession s, String uri, WebRequestError error) {
            String reason = "error " + error.code + " (category " + error.category + ")";
            Log.w(TAG, "page failed: " + uri + " -> " + reason);
            cover.setVisibility(View.GONE);
            if (!usingBundled && !BuildConfig.APP_URL.isEmpty() && isAppPage(uri) && !uri.startsWith("resource:")) {
                // Often the box's network just isn't up yet: retry the online page a few times
                // before using the built-in copy (YouTube doesn't play from the built-in copy).
                if (onlineRetries < 4) {
                    onlineRetries++;
                    view.postDelayed(MainActivity.this::loadHome, 3000);
                    return GeckoResult.fromValue(blankPage());
                }
                usingBundled = true;
                Toast.makeText(MainActivity.this, "网络版打不开，改用本机版本", Toast.LENGTH_LONG).show();
                view.post(MainActivity.this::loadHome);
                return GeckoResult.fromValue(blankPage());
            }
            showingError = true;
            return GeckoResult.fromValue(errorPage(uri, reason));
        }
    }

    private class Progress implements GeckoSession.ProgressDelegate {
        @Override
        public void onPageStart(GeckoSession s, String url) {
            currentUrl = url == null ? "" : url;
            if (!currentUrl.startsWith("data:")) showingError = false;
        }

        @Override
        public void onPageStop(GeckoSession s, boolean success) {
            if (isLiveSite(currentUrl)) {
                cover.removeCallbacks(hideCover);
                cover.postDelayed(hideCover, 1500);  // by then the page is full screen
            } else {
                cover.setVisibility(View.GONE);
            }
            if (isAppPage(currentUrl)) {
                onlineRetries = 0;
                if (purgeOnHome) {
                    purgeOnHome = false;
                    session.purgeHistory();  // so Back from home doesn't return to CCTV/TVB
                }
            }
        }
    }

    private class Permissions implements GeckoSession.PermissionDelegate {
        @Override
        public GeckoResult<Integer> onContentPermissionRequest(GeckoSession s, ContentPermission perm) {
            // Let videos start with sound, and let the broadcasters' players use protected
            // playback if they ask. Nothing else (camera, location, ...) is ever granted.
            boolean allow = perm.permission == PERMISSION_AUTOPLAY_AUDIBLE
                    || perm.permission == PERMISSION_AUTOPLAY_INAUDIBLE
                    || (perm.permission == PERMISSION_MEDIA_KEY_SYSTEM_ACCESS && isLiveSite(perm.uri));
            return GeckoResult.fromValue(allow ? ContentPermission.VALUE_ALLOW : ContentPermission.VALUE_DENY);
        }
    }

    private class Content implements GeckoSession.ContentDelegate {
        @Override
        public void onCrash(GeckoSession s) {
            Log.e(TAG, "page process crashed; reloading home");
            recover();
        }

        @Override
        public void onKill(GeckoSession s) {
            Log.e(TAG, "page process was killed; reloading home");
            recover();
        }
    }

    /** The engine's page process died (e.g. low memory): start a fresh session on our home page. */
    private void recover() {
        view.post(() -> {
            if (session.isOpen()) session.close();
            session.open(runtime);
            view.setSession(session);
            loadHome();
        });
    }

    private static String blankPage() {
        return "data:text/html;charset=utf-8," + Uri.encode("<html><body style='background:#12141a'></body></html>");
    }

    private String errorPage(String url, String reason) {
        String html = "<html><body style='background:#12141a;color:#f4f1ea;font:24px/1.6 sans-serif;padding:48px'>"
                + "<h1 style='color:#f5b942'>打不开 / Couldn't load</h1>"
                + "<p>请检查网络，然后按 <b>OK</b> 重试。<br>Check the internet connection, then press <b>OK</b> to retry.</p>"
                + "<p style='color:#a7adba;font-size:20px'>Page: " + TextUtils.htmlEncode(String.valueOf(url))
                + "<br>Reason: " + TextUtils.htmlEncode(reason)
                + "<br>Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), GeckoView 143</p>"
                + "</body></html>";
        return "data:text/html;charset=utf-8," + Uri.encode(html);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (showingError && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                usingBundled = false;  // try the online copy again first
                onlineRetries = 0;
                loadHome();
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER) {
            // Hand the remote's OK to the page as Enter, which is what the page listens for.
            return super.dispatchKeyEvent(new KeyEvent(event.getDownTime(), event.getEventTime(),
                    event.getAction(), KeyEvent.KEYCODE_ENTER, event.getRepeatCount(), event.getMetaState()));
        }
        boolean back = key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE
                || key == KeyEvent.KEYCODE_BUTTON_B;  // some remotes/gamepads send these for Back
        if (back) {
            // Act once per press: on release of a fresh press, ignoring held-down repeats and a
            // stray release from a press that started somewhere else.
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                backDown = true;
            } else if (event.getAction() == KeyEvent.ACTION_UP && backDown && !event.isCanceled()) {
                backDown = false;
                onBackKey();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Back: on CCTV/TVB pages, return to our home screen. On our page, step back one screen
     * (the page records each screen in its history); with nothing left, offer to exit.
     */
    private void onBackKey() {
        if (showingError) {
            confirmExit();
        } else if (!isAppPage(currentUrl)) {
            purgeOnHome = true;
            loadHome();
        } else if (canGoBack) {
            session.goBack();
        } else {
            confirmExit();
        }
    }

    private void confirmExit() {
        if (System.currentTimeMillis() - lastBackAt < 2500) {
            finish();
        } else {
            lastBackAt = System.currentTimeMillis();
            Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.requestFocus();
        if (usingBundled) {  // came back to the app: try the online page again
            usingBundled = false;
            onlineRetries = 0;
            loadHome();
        }
    }
}

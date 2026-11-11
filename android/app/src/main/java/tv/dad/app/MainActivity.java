package tv.dad.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
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
 * screen, maps the remote's Back key, and offers a downloaded update of the app (see Updater).
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
    private View updatePrompt;       // "a new version is ready": OK installs it, Back leaves it
    private long updateAskedAt;      // when we last asked (not again for a while if he says no)
    private int promptKey;           // key pressed while the prompt was up; acted on at release

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
        updatePrompt = buildUpdatePrompt();
        root.addView(updatePrompt);
        setContentView(root);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        view.setFocusable(true);
        view.requestFocus();  // without focus, the remote's keys never reach the page

        loadHome(getIntent());

        Updater.setListener(this::maybeOfferUpdate);
        Updater.start(this, runtime);
    }

    @Override
    protected void onDestroy() {
        Updater.setListener(null);
        super.onDestroy();
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
            maybeOfferUpdate();  // he may just have come back to the home screen
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
                maybeOfferUpdate();
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

    /** The full-screen "a new version is ready" message, hidden until an update has been downloaded. */
    private View buildUpdatePrompt() {
        float unit = getResources().getDisplayMetrics().heightPixels / 100f;  // 1% of the screen height
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.rgb(18, 20, 26));
        box.setClickable(true);  // nothing underneath reacts while it's up
        box.setVisibility(View.GONE);
        String[] lines = {
            "区tv 有新版本",
            "按遥控器的 OK 键开始更新",
            "下一个画面请选“安装”，装好以后选“打开”",
            "现在不想更新，就按返回键，下次再提醒",
            "A new version is ready.  OK: install it.  Back: later.",
        };
        float[] sizes = { 9f, 6.5f, 4.5f, 4.5f, 3f };
        int[] colors = { Color.rgb(245, 185, 66), Color.rgb(244, 241, 234), Color.rgb(244, 241, 234),
                Color.rgb(167, 173, 186), Color.rgb(167, 173, 186) };
        for (int i = 0; i < lines.length; i++) {
            TextView text = new TextView(this);
            text.setText(lines[i]);
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizes[i] * unit);
            text.setTextColor(colors[i]);
            text.setGravity(Gravity.CENTER);
            text.setPadding(0, (int) (2.5f * unit), 0, 0);
            box.addView(text);
        }
        return box;
    }

    /**
     * Show the update message if an update has been downloaded, he is on our home screen (never
     * in the middle of a programme), and we haven't asked in the last 20 hours.
     */
    private void maybeOfferUpdate() {
        if (updatePrompt == null || updatePrompt.getVisibility() == View.VISIBLE) return;
        if (Updater.readyVersion() <= BuildConfig.VERSION_CODE) return;
        if (!isAppPage(currentUrl) || canGoBack || showingError) return;
        long now = SystemClock.elapsedRealtime();
        if (updateAskedAt != 0 && now - updateAskedAt < 20 * 3600_000L) return;
        updateAskedAt = now;
        promptKey = 0;
        updatePrompt.setVisibility(View.VISIBLE);
        Updater.report("prompt", "to", Updater.readyVersion());
    }

    /** Hand the downloaded APK to Android's installer, which asks him to confirm. */
    private void installUpdate() {
        updatePrompt.setVisibility(View.GONE);
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(UpdateProvider.uri(this), UpdateProvider.APK_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            try {
                startActivity(install);
            } catch (android.content.ActivityNotFoundException e) {
                startActivity(install.setAction(Intent.ACTION_VIEW));
            }
            Updater.report("installer opened", "to", Updater.readyVersion());
        } catch (Exception e) {
            Log.e(TAG, "couldn't open the installer", e);
            Updater.report("installer failed", "why", String.valueOf(e));
            Toast.makeText(this, "打不开安装程序，暂时不更新", Toast.LENGTH_LONG).show();
        }
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
        if (updatePrompt.getVisibility() == View.VISIBLE && key != KeyEvent.KEYCODE_VOLUME_UP
                && key != KeyEvent.KEYCODE_VOLUME_DOWN && key != KeyEvent.KEYCODE_VOLUME_MUTE) {
            // Only a key that was pressed AND released while the message was showing counts, so
            // the tail end of an earlier press can't start the update by accident.
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                promptKey = key;
            } else if (event.getAction() == KeyEvent.ACTION_UP && promptKey == key) {
                promptKey = 0;
                if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                        || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    installUpdate();
                } else if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE
                        || key == KeyEvent.KEYCODE_BUTTON_B) {
                    updatePrompt.setVisibility(View.GONE);
                    Updater.report("later", "to", Updater.readyVersion());
                }
            }
            return true;
        }
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

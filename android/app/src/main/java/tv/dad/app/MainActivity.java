package tv.dad.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A full-screen browser locked to Dad's TV page. The page does all the work; this class
 * only hosts it, opens CCTV's official live pages full-screen, and maps the remote's Back key.
 */
public class MainActivity extends Activity {

    private static final String BUNDLED_URL = "https://appassets.androidplatform.net/assets/www/index.html";

    // Official live sites whose pages the app shows full screen (see assets/fullscreen.js).
    private static final String[] LIVE_SITES = { "cctv.com", "news.tvb.com" };
    private static final Set<String> LIVE_ORIGINS =
            new HashSet<>(Arrays.asList("https://tv.cctv.com", "https://news.tvb.com"));

    private WebView web;
    private String fullscreenJs = "";
    private long lastBackAt;
    private boolean usingBundled;  // the online page failed, so we're showing the built-in copy
    private boolean showingError;  // the error page is up; OK retries
    private String mainUrl = "";   // the top-level page we last asked WebView to load
    private View cover;            // black screen over CCTV/TVB pages until they're full screen
    private boolean backDown;      // saw a fresh Back press; act on its release

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final WebViewAssetLoader assets = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        try {
            web = new WebView(this);
        } catch (RuntimeException e) {
            // Box has no working "Android System WebView": say so instead of crashing.
            TextView msg = new TextView(this);
            msg.setText("需要安装或启用 Android System WebView 才能使用。\n"
                    + "This box needs \"Android System WebView\" installed/enabled.\n\n" + e);
            msg.setTextSize(24);
            msg.setPadding(60, 60, 60, 60);
            setContentView(msg);
            return;
        }
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setLoadWithOverviewMode(true);  // with the page's width=1600 viewport: fit the TV exactly
        ws.setUseWideViewPort(true);
        ws.setTextZoom(100);               // ignore the box's font-size setting; the page sizes itself
        // Present as desktop Chrome (same engine version), like "request desktop site": CCTV's
        // mobile site keeps pushing its phone app, and the desktop players are what we tested.
        Matcher chrome = Pattern.compile("Chrome/[\\d.]+").matcher(ws.getUserAgentString());
        ws.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + (chrome.find() ? chrome.group() : "Chrome/120.0.0.0") + " Safari/537.36");

        // Things a normal browser allows but an in-app WebView doesn't by default, any of which
        // can stop a site's player from starting: cookies set by the video servers, protected
        // (DRM) playback, and plain-http media on an https page.
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        ws.setDatabaseEnabled(true);
        // Lets the automated device test (tools/device_test.py) inspect pages over adb.
        WebView.setWebContentsDebuggingEnabled(true);

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i("DadTV", "console " + m.messageLevel() + ": " + m.message()
                        + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Only the broadcasters' own pages, and only protected-media playback.
                boolean live = isLiveSite(request.getOrigin().toString());
                for (String r : request.getResources()) {
                    if (!live || !PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) {
                        request.deny();
                        return;
                    }
                }
                request.grant(request.getResources());
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assets.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                // Any real page (not our error page, which shows as about:blank) clears the error.
                if (url != null && !url.startsWith("about:")) showingError = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Backup for WebViews without document-start scripts; the script ignores a second run.
                if (isLiveSite(url)) {
                    view.evaluateJavascript(fullscreenJs, null);
                    cover.removeCallbacks(hideCover);
                    cover.postDelayed(hideCover, 1500);  // by then the page is full screen
                } else {
                    cover.setVisibility(View.GONE);
                }
                // After switching to the built-in copy or showing the error page, don't let Back
                // return to the page that failed.
                if ((usingBundled && sameUrl(url, BUNDLED_URL)) || showingError) view.clearHistory();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    pageFailed(request.getUrl().toString(), error.getErrorCode() + " " + error.getDescription());
                }
            }

            @SuppressWarnings("deprecation")  // the only error callback on Android 5
            @Override
            public void onReceivedError(WebView view, int code, String description, String failingUrl) {
                if (Build.VERSION.SDK_INT < 23) pageFailed(failingUrl, code + " " + description);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) {
                    pageFailed(request.getUrl().toString(), "HTTP " + response.getStatusCode());
                }
            }

            // An old box may not trust a site's certificate. Never ignore that. If it's the page
            // itself, report it (or fall back to the built-in copy); a single image or stream
            // failing just fails on its own.
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                if (sameUrl(error.getUrl(), mainUrl)) {
                    pageFailed(error.getUrl(), "SSL error " + error.getPrimaryError()
                            + " (the box doesn't trust the site's certificate)");
                }
            }
        });
        web.setBackgroundColor(Color.rgb(18, 20, 26));
        web.addJavascriptInterface(new Bridge(), "DadTV");

        fullscreenJs = readAsset("fullscreen.js");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Runs as each live page starts loading, before the site builds its player.
            WebViewCompat.addDocumentStartJavaScript(web, fullscreenJs, LIVE_ORIGINS);
        }
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        cover = new View(this);
        cover.setBackgroundColor(Color.BLACK);
        cover.setVisibility(View.GONE);
        FrameLayout root = new FrameLayout(this);
        root.addView(web);
        root.addView(cover);
        setContentView(root);
        // Without focus, the remote's arrow keys never reach the page.
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();
        loadHome();
    }

    private final Runnable hideCover = () -> cover.setVisibility(View.GONE);

    /** Our own page (online or built-in), as opposed to CCTV's or TVB's site. */
    private static boolean isAppPage(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.startsWith("https://appassets.androidplatform.net/")
                || (!BuildConfig.APP_URL.isEmpty() && u.startsWith(BuildConfig.APP_URL.toLowerCase()));
    }

    private String homeUrl() {
        return BuildConfig.APP_URL.isEmpty() || usingBundled ? BUNDLED_URL : BuildConfig.APP_URL;
    }

    private void loadHome() {
        showingError = false;
        loadMain(homeUrl());
    }

    /** Every top-level page goes through here, so certificate errors can be matched to it. */
    private void loadMain(String url) {
        mainUrl = url;
        web.loadUrl(url);
    }

    /** WebView reports hosts in lower case and may add or drop a trailing slash. */
    private static boolean sameUrl(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.endsWith("/") ? a.substring(0, a.length() - 1) : a;
        String y = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        return x.equalsIgnoreCase(y);
    }

    /**
     * The main page didn't load. If it was the online copy, switch to the copy inside the app;
     * otherwise show what went wrong, so the person helping can see it instead of a blank screen.
     */
    private void pageFailed(String url, String reason) {
        Log.w("DadTV", "page failed: " + url + " -> " + reason);
        if (showingError) return;
        if (!usingBundled && !BuildConfig.APP_URL.isEmpty() && sameUrl(url, BuildConfig.APP_URL)) {
            usingBundled = true;
            Toast.makeText(this, "网络版打不开，改用本机版本", Toast.LENGTH_LONG).show();
            loadMain(BUNDLED_URL);
            return;
        }
        showingError = true;
        String webview = "?";
        PackageInfo pkg = WebViewCompat.getCurrentWebViewPackage(this);
        if (pkg != null) webview = pkg.packageName + " " + pkg.versionName;
        String html = "<html><body style='background:#12141a;color:#f4f1ea;font:24px/1.6 sans-serif;padding:48px'>"
                + "<h1 style='color:#f5b942'>打不开 / Couldn't load</h1>"
                + "<p>请检查网络，然后按 <b>OK</b> 重试。<br>Check the internet connection, then press <b>OK</b> to retry.</p>"
                + "<p style='color:#a7adba;font-size:20px'>Page: " + TextUtils.htmlEncode(String.valueOf(url))
                + "<br>Reason: " + TextUtils.htmlEncode(reason)
                + "<br>Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "<br>WebView: " + TextUtils.htmlEncode(webview) + "</p></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private static boolean isLiveSite(String url) {
        String host = url == null ? null : Uri.parse(url).getHost();
        if (host == null) return false;
        for (String domain : LIVE_SITES) {
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    /** Called from the page's JavaScript as window.DadTV. */
    private class Bridge {
        /** Opens the Play Store page to update the box's web engine (Android System WebView). */
        @JavascriptInterface
        public void openWebViewUpdate() {
            runOnUiThread(() -> {
                String id = "com.google.android.webview";
                for (String u : new String[] {
                        "market://details?id=" + id, "https://play.google.com/store/apps/details?id=" + id }) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return;
                    } catch (ActivityNotFoundException e) {
                        // no Play Store / browser for that link; try the next one
                    }
                }
                Toast.makeText(MainActivity.this,
                        "找不到应用商店：请在设置 > 应用里更新 Android System WebView", Toast.LENGTH_LONG).show();
            });
        }

        @JavascriptInterface
        public void openPage(final String url) {
            Log.i("DadTV", "openPage " + url);
            if (!isLiveSite(url)) return;  // only the broadcasters' own live sites
            runOnUiThread(() -> {
                cover.removeCallbacks(hideCover);
                cover.setVisibility(View.VISIBLE);  // hide the site until it's been made full screen
                loadMain(url);
            });
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (web == null) return super.dispatchKeyEvent(event);  // the "no WebView" message screen
        int key = event.getKeyCode();
        if (showingError && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                usingBundled = false;  // try the online copy again first
                loadHome();
            }
            return true;
        }
        boolean back = key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE
                || key == KeyEvent.KEYCODE_BUTTON_B;  // some remotes/gamepads send these for Back
        if (back) {
            // Act once per press: on release of a fresh press, ignoring held-down repeats and
            // a stray release from a press that started somewhere else.
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
     * Back: on CCTV/TVB pages, return to our home screen. On our page, ask it to go back one
     * screen (it answers false when it's already on the home screen). Only then offer to exit.
     */
    private void onBackKey() {
        if (showingError) {
            confirmExit();
        } else if (!isAppPage(web.getUrl())) {
            // On CCTV/TVB (or anywhere they redirected to): straight back to our home screen,
            // not through the site's own history.
            loadHome();
        } else {
            web.evaluateJavascript("(window.dadtvBack ? window.dadtvBack() : false)", handled -> {
                if (!"true".equals(handled)) confirmExit();
            });
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
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web == null) return;  // no WebView on this box (see onCreate)
        web.onResume();
        web.requestFocus();
    }
}

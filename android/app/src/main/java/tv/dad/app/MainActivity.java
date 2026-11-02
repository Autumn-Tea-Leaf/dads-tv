package tv.dad.app;

import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);

        web.setWebChromeClient(new WebChromeClient());
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
                if (isLiveSite(url)) view.evaluateJavascript(fullscreenJs, null);
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

        setContentView(web);
        loadHome();
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
        @JavascriptInterface
        public void openPage(final String url) {
            if (!isLiveSite(url)) return;  // only the broadcasters' own live sites
            runOnUiThread(() -> loadMain(url));
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (showingError && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                usingBundled = false;  // try the online copy again first
                loadHome();
            }
            return true;
        }
        boolean back = key == KeyEvent.KEYCODE_BACK;
        if (back && event.getAction() == KeyEvent.ACTION_UP) {
            if (web.canGoBack() && !showingError) {
                web.goBack();  // the page turns this into "close player" / "previous screen"
            } else if (System.currentTimeMillis() - lastBackAt < 2500) {
                finish();
            } else {
                lastBackAt = System.currentTimeMillis();
                Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if (back) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }
}

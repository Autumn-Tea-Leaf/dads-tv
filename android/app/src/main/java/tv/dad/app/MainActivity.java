package tv.dad.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final WebViewAssetLoader assets = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
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

            // Backup for WebViews without document-start scripts; the script ignores a second run.
            @Override
            public void onPageFinished(WebView view, String url) {
                if (isLiveSite(url)) view.evaluateJavascript(fullscreenJs, null);
            }
        });
        web.addJavascriptInterface(new Bridge(), "DadTV");

        fullscreenJs = readAsset("fullscreen.js");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Runs as each live page starts loading, before the site builds its player.
            WebViewCompat.addDocumentStartJavaScript(web, fullscreenJs, LIVE_ORIGINS);
        }
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(web);
        web.loadUrl(BuildConfig.APP_URL.isEmpty() ? BUNDLED_URL : BuildConfig.APP_URL);
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
            runOnUiThread(() -> web.loadUrl(url));
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean back = event.getKeyCode() == KeyEvent.KEYCODE_BACK;
        if (back && event.getAction() == KeyEvent.ACTION_UP) {
            if (web.canGoBack()) {
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

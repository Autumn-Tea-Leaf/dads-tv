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

/**
 * A full-screen browser locked to Dad's TV page. The page does all the work; this class
 * only hosts it, opens CCTV's official live pages full-screen, and maps the remote's Back key.
 */
public class MainActivity extends Activity {

    private static final String BUNDLED_URL = "https://appassets.androidplatform.net/assets/www/index.html";

    // Once an official live page loads: hide everything except its player, stretch the player
    // over the whole screen, then keep nudging the video for ~10s until it plays with sound
    // (pages sometimes start paused or muted). `player` is the element to enlarge on that site.
    private static String fullscreenJs(String player) {
        return "(function(){var s=document.createElement('style');s.textContent="
                + "'html,body{overflow:hidden!important;background:#000!important}"
                + "body *{visibility:hidden!important}" + player + "," + player + " *{visibility:visible!important}"
                + player + "{position:fixed!important;left:0!important;top:0!important;width:100vw!important;"
                + "height:100vh!important;z-index:2147483646!important;background:#000!important;transform:none!important}"
                + player + " video{width:100%!important;height:100%!important}"
                + "video{object-fit:contain!important}';"
                + "document.head.appendChild(s);"
                + "var tries=0,t=setInterval(function(){var v=document.querySelector('video');"
                + "if(v){if(v.paused)v.play().catch(function(){});if(v.muted&&!v.paused){v.muted=false;v.volume=1;}}"
                + "if(++tries>10)clearInterval(t);},1000);"
                + "})();";
    }

    private static final String CCTV_FULLSCREEN_JS = fullscreenJs("#player");
    private static final String TVB_FULLSCREEN_JS = fullscreenJs("video");

    private WebView web;
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

            @Override
            public void onPageFinished(WebView view, String url) {
                if (isHost(url, "cctv.com")) view.evaluateJavascript(CCTV_FULLSCREEN_JS, null);
                else if (isHost(url, "news.tvb.com")) view.evaluateJavascript(TVB_FULLSCREEN_JS, null);
            }
        });
        web.addJavascriptInterface(new Bridge(), "DadTV");
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(web);
        web.loadUrl(BuildConfig.APP_URL.isEmpty() ? BUNDLED_URL : BuildConfig.APP_URL);
    }

    private static boolean isHost(String url, String domain) {
        String host = url == null ? null : Uri.parse(url).getHost();
        return host != null && (host.equals(domain) || host.endsWith("." + domain));
    }

    /** Called from the page's JavaScript as window.DadTV. */
    private class Bridge {
        @JavascriptInterface
        public void openPage(final String url) {
            // only the broadcasters' own live sites
            if (!isHost(url, "cctv.com") && !isHost(url, "news.tvb.com")) return;
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

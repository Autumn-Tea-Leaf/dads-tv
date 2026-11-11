package tv.dad.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoWebExecutor;
import org.mozilla.geckoview.WebRequest;
import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Keeps the app itself up to date, so a fix doesn't need someone to uninstall and reinstall it.
 *
 * The Build workflow publishes version.json next to the page: the newest version number and,
 * for each processor type, where its APK is, how big it is and its SHA-256. In the background
 * this class reads that file; when it names a newer version it downloads the APK (slowly and in
 * pieces, so it doesn't disturb what's playing and can carry on after a dropped connection),
 * checks it, and tells MainActivity, which asks the viewer and hands the file to Android's own
 * installer. Android only installs it if it is signed with the same key as this app.
 *
 * Everything goes through the engine's network code (GeckoWebExecutor) rather than Android's,
 * because an old box's Android no longer trusts many of today's web certificates.
 * Each step is logged as "DADTV_UPDATE {...}" for the device test (tools/device_test.py).
 */
final class Updater {

    private static final String TAG = "DadTV";
    private static final int PIECE = 4 * 1024 * 1024;       // bytes fetched per request
    private static final long PIECE_MILLIS = 7000;          // at most one piece per 7 s: about 600 KB/s
    private static final long FIRST_CHECK_MILLIS = 20_000;  // let the app start up first
    private static final long RECHECK_MILLIS = 6 * 3600_000L;
    private static final long RETRY_MILLIS = 30 * 60_000L;
    private static final long TIMEOUT_MILLIS = 60_000;

    private static boolean started;
    private static volatile int readyVersion;   // version of the checked APK waiting to be installed
    private static volatile Runnable listener;  // MainActivity's "an update is ready" (main thread)

    private Updater() {}

    /** The downloaded, checked APK; served to Android's installer by UpdateProvider. */
    static File readyFile(Context context) {
        return new File(context.getFilesDir(), "update.apk");
    }

    private static File partFile(Context context) {
        return new File(context.getFilesDir(), "update.part");
    }

    /** Version of the update that is ready to install, or 0. */
    static int readyVersion() {
        return readyVersion;
    }

    static void setListener(Runnable onReady) {
        listener = onReady;
    }

    static synchronized void start(Context context, GeckoRuntime runtime) {
        // Android 7+ only: older versions want the file handed over in a different way.
        if (started || BuildConfig.APP_URL.isEmpty() || Build.VERSION.SDK_INT < 24) return;
        started = true;
        Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> run(app, runtime), "updater");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void run(Context app, GeckoRuntime runtime) {
        try {
            if (useDownloaded(app)) return;
            SystemClock.sleep(FIRST_CHECK_MILLIS);
            GeckoWebExecutor web = new GeckoWebExecutor(runtime);
            while (true) {
                long wait = RECHECK_MILLIS;
                try {
                    if (check(app, web)) return;
                } catch (Throwable t) {
                    report("failed", "why", String.valueOf(t));
                    wait = RETRY_MILLIS;
                }
                SystemClock.sleep(wait);
            }
        } catch (Throwable t) {  // never let the updater take the app down
            Log.e(TAG, "updater stopped", t);
        }
    }

    /** An update downloaded earlier: offer it if it is still newer than this app, else remove it. */
    private static boolean useDownloaded(Context app) {
        File ready = readyFile(app);
        if (!ready.exists()) return false;
        int version = inspect(app, ready);
        if (version > BuildConfig.VERSION_CODE) {
            announce(version);
            return true;
        }
        ready.delete();  // already installed (this is the first start after the update), or unusable
        report("cleaned");
        return false;
    }

    /** One look at version.json; downloads the update if there is one. True once one is ready. */
    private static boolean check(Context app, GeckoWebExecutor web) throws Throwable {
        JSONObject info = new JSONObject(new String(readAll(fetch(web, BuildConfig.APP_URL + "version.json", null)), "UTF-8"));
        int version = info.getInt("versionCode");
        if (version <= BuildConfig.VERSION_CODE) {
            partFile(app).delete();
            report("current", "latest", version);
            return false;
        }
        String abi = installedAbi(app);
        JSONObject apk = abi == null ? null : info.getJSONObject("apks").optJSONObject(abi);
        if (apk == null) throw new IOException("no APK listed for " + abi);
        String url = apk.getString("url");
        String sha256 = apk.getString("sha256");
        long size = apk.getLong("size");
        if (!url.startsWith("https://") || size <= 0) throw new IOException("bad version.json entry");

        SharedPreferences prefs = app.getSharedPreferences("update", Context.MODE_PRIVATE);
        if (sha256.equals(prefs.getString("rejected", ""))) {
            report("rejected", "to", version, "why", "this APK was already checked and can't be installed here");
            return false;
        }
        File part = partFile(app);
        if (!sha256.equals(prefs.getString("part", "")) || part.length() > size) {
            part.delete();  // a different APK than the one we had started on
            prefs.edit().putString("part", sha256).apply();
        }
        // Room for the rest of the download plus the copy Android's installer makes.
        long need = (size - part.length()) + size + 50L * 1024 * 1024;
        if (app.getFilesDir().getUsableSpace() < need) {
            throw new IOException("not enough free space: need " + need + " bytes");
        }

        while (part.length() < size) {
            long began = SystemClock.elapsedRealtime();
            long from = part.length();
            long to = Math.min(from + PIECE, size) - 1;
            WebResponse response = fetch(web, url, "bytes=" + from + "-" + to);
            boolean whole = response.statusCode == 200;  // the server ignored the range: all of it
            if (response.statusCode != 206 && !whole) {
                closeQuietly(response.body);
                throw new IOException("HTTP " + response.statusCode + " for the APK");
            }
            try (InputStream in = response.body; OutputStream out = new FileOutputStream(part, !whole)) {
                copy(in, out, whole ? size : to - from + 1);
            }
            report("downloading", "to", version, "have", part.length(), "size", size);
            long spare = PIECE_MILLIS - (SystemClock.elapsedRealtime() - began);
            if (spare > 0 && part.length() < size) SystemClock.sleep(spare);
        }

        String actual = sha256(part);
        if (part.length() != size || !actual.equalsIgnoreCase(sha256)) {
            part.delete();
            throw new IOException("download doesn't match version.json (SHA-256 " + actual + ")");
        }
        int found = inspect(app, part);
        if (found <= BuildConfig.VERSION_CODE) {
            part.delete();
            prefs.edit().putString("rejected", sha256).apply();
            report("rejected", "to", version, "found", found);
            return false;
        }
        File ready = readyFile(app);
        ready.delete();
        if (!part.renameTo(ready)) throw new IOException("couldn't keep the downloaded APK");
        announce(found);
        return true;
    }

    private static void announce(int version) {
        readyVersion = version;
        report("ready", "to", version);
        new Handler(Looper.getMainLooper()).post(() -> {
            Runnable l = listener;
            if (l != null) l.run();
        });
    }

    /**
     * The APK's version, or 0 if it isn't this app, or is signed with a different key (Android
     * would refuse to install that over this app, so there's no point asking the viewer).
     */
    @SuppressWarnings("deprecation")
    private static int inspect(Context app, File apk) {
        try {
            PackageManager pm = app.getPackageManager();
            PackageInfo theirs = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
            if (theirs == null || !app.getPackageName().equals(theirs.packageName)) {
                report("unusable", "why", "not this app's APK");
                return 0;
            }
            Signature[] a = theirs.signatures;
            Signature[] b = pm.getPackageInfo(app.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            boolean known = a != null && a.length > 0 && b != null && b.length > 0;
            if (known && !a[0].equals(b[0])) {
                report("unusable", "why", "signed with a different key than the installed app");
                return 0;
            }
            report("inspected", "found", theirs.versionCode, "sameKey", known);
            return theirs.versionCode;
        } catch (Throwable t) {
            report("unusable", "why", String.valueOf(t));
            return 0;
        }
    }

    /** Which of the per-processor APKs is installed here ("armeabi-v7a", "arm64-v8a", ...). */
    private static String installedAbi(Context app) throws IOException {
        try (ZipFile zip = new ZipFile(app.getPackageCodePath())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                String name = e.nextElement().getName();
                int end = name.indexOf('/', 4);
                if (name.startsWith("lib/") && end > 4) return name.substring(4, end);
            }
        }
        return null;
    }

    private static WebResponse fetch(GeckoWebExecutor web, String url, String range) throws Throwable {
        WebRequest.Builder request = new WebRequest.Builder(url).cacheMode(WebRequest.CACHE_MODE_NO_STORE);
        if (range != null) request.addHeader("Range", range);
        WebResponse response = web.fetch(request.build(), GeckoWebExecutor.FETCH_FLAGS_ANONYMOUS).poll(TIMEOUT_MILLIS);
        if (response == null || response.body == null) throw new IOException("no answer from " + url);
        response.setReadTimeoutMillis(TIMEOUT_MILLIS);
        if (range == null && response.statusCode != 200) {
            closeQuietly(response.body);
            throw new IOException("HTTP " + response.statusCode + " for " + url);
        }
        return response;
    }

    private static byte[] readAll(WebResponse response) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (InputStream in = response.body) {
            copy(in, out, 1024 * 1024);
        }
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer, 0, buffer.length)) != -1) {
            total += n;
            if (total > limit) throw new IOException("more data than expected");
            out.write(buffer, 0, n);
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private static void closeQuietly(InputStream in) {
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {
        }
    }

    /** Log one step as "DADTV_UPDATE {...}": state, this app's version, then the given pairs. */
    static void report(String state, Object... pairs) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("version", BuildConfig.VERSION_CODE);
            for (int i = 0; i + 1 < pairs.length; i += 2) o.put(String.valueOf(pairs[i]), pairs[i + 1]);
            Log.i(TAG, "DADTV_UPDATE " + o);
        } catch (Exception e) {
            Log.i(TAG, "DADTV_UPDATE " + state);
        }
    }
}

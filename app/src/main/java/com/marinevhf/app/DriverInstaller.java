package com.marinevhf.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Установка драйвера «Rtl-sdr driver». Источники по порядку:
 * 1) APK, вшитый в assets (кладёт сборка, scripts/fetch_driver.sh);
 * 2) загрузка с F-Droid (нужен интернет).
 * Установка идёт через PackageInstaller: Android сам покажет окно подтверждения.
 */
public final class DriverInstaller {
    public static final String ASSET = "rtl_tcp_andro.apk";
    private static final String FDROID_API = "https://f-droid.org/api/v1/packages/" + DriverManager.PACKAGE;
    private static final String FDROID_REPO = "https://f-droid.org/repo/" + DriverManager.PACKAGE + "_";

    public interface Progress { void onStatus(String text); }

    private DriverInstaller() { }

    public static boolean hasBundled(Context c) {
        try (InputStream in = c.getAssets().open(ASSET)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Блокирующий вызов — запускать в фоновом потоке. Итог придёт в InstallReceiver. */
    public static void install(Context c, Progress p) throws IOException {
        File apk = new File(c.getCacheDir(), ASSET);
        if (hasBundled(c)) {
            p.onStatus("Подготовка драйвера…");
            try (InputStream in = c.getAssets().open(ASSET); OutputStream out = new FileOutputStream(apk)) {
                copy(in, out);
            }
        } else {
            p.onStatus("Загрузка драйвера с F-Droid…");
            download(apk);
        }
        p.onStatus("Установка…");
        try {
            session(c, apk);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();                       // копия уже передана установщику
        }
    }

    private static void download(File dst) throws IOException {
        String json = new String(read(open(FDROID_API)), "UTF-8");
        try {
            long code = new JSONObject(json).getLong("suggestedVersionCode");
            try (InputStream in = open(FDROID_REPO + code + ".apk").getInputStream(); OutputStream out = new FileOutputStream(dst)) {
                copy(in, out);
            }
        } catch (org.json.JSONException e) {
            throw new IOException("неожиданный ответ F-Droid");
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(30000);
        if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode() + " " + url);
        return h;
    }

    private static byte[] read(HttpURLConnection h) throws IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        try (InputStream in = h.getInputStream()) {
            copy(in, o);
        }
        return o.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[32768];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }

    private static void session(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(DriverManager.PACKAGE);
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id);
             java.io.FileInputStream in = new java.io.FileInputStream(apk);
             OutputStream out = s.openWrite("driver", 0, apk.length())) {
            copy(in, out);
            s.fsync(out);
            out.close();
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            Intent i = new Intent(InstallReceiver.ACTION).setPackage(c.getPackageName())
                    .setClass(c, InstallReceiver.class);
            s.commit(PendingIntent.getBroadcast(c, id, i, flags).getIntentSender());
        }
    }
}

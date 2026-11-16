package com.marinevhf.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

/** Работа с приложением «Rtl-sdr driver» (marto.rtl_tcp_andro): проверка, запуск rtl_tcp, разбор ошибок. */
public final class DriverManager {
    public static final String PACKAGE = "marto.rtl_tcp_andro";
    public static final int REQ_START = 1001;
    public static final int PORT = 1234;
    public static final int SAMPLE_RATE = 1_920_000;

    private DriverManager() { }

    public static boolean isInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Просит драйвер открыть USB-устройство и поднять rtl_tcp на 127.0.0.1:1234. Результат — в onActivityResult. */
    public static boolean start(Activity a) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setPackage(PACKAGE);
        i.setData(Uri.parse("iqsrc://-a 127.0.0.1 -p " + PORT + " -s " + SAMPLE_RATE));
        try {
            a.startActivityForResult(i, REQ_START);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Текст ошибки из ответа драйвера (если он что-то положил в extras). */
    public static String errorText(Intent data) {
        if (data == null) return null;
        Bundle b = data.getExtras();
        if (b == null) return null;
        StringBuilder sb = new StringBuilder();
        for (String k : b.keySet()) {
            Object v = b.get(k);
            if (v != null) sb.append(k).append(": ").append(v).append('\n');
        }
        return sb.length() == 0 ? null : sb.toString().trim();
    }
}

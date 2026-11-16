package com.marinevhf.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Настройки приложения. */
public final class Prefs {
    private final SharedPreferences p;

    public Prefs(Context c) {
        p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    public int tcpPort() { return p.getInt("tcp", 10110); }
    public String udpHost() { return p.getString("udpHost", "127.0.0.1"); }
    public int udpPort() { return p.getInt("udp", 10110); }
    public int sql() { return p.getInt("sql", 3); }
    public int ppm() { return p.getInt("ppm", 0); }
    /** -1 — автоматическая регулировка. */
    public double gain() { return p.getFloat("gain", -1f); }
    public double threshold() { return p.getFloat("thr", 8f); }
    public String recFormat() { return p.getString("fmt", AudioRecorder.AAC); }
    public boolean recOnlyOpen() { return p.getBoolean("onlyOpen", true); }
    public float volume() { return p.getFloat("vol", 0.7f); }
    public int channel() { return p.getInt("ch", 16); }
    public boolean listenShip() { return p.getBoolean("ship", false); }

    public boolean keepOn() { return p.getBoolean("keepOn", false); }

    public SharedPreferences.Editor edit() { return p.edit(); }
}

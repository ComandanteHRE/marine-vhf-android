package com.marinevhf.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.marinevhf.core.PanelLogic;
import com.marinevhf.core.RadioEngine;
import com.marinevhf.core.RtlTcpClient;
import com.marinevhf.dsp.Ais;
import com.marinevhf.dsp.Channels;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Держит приёмник, звук, запись и NMEA, пока приложение свёрнуто (foreground-сервис). */
public class RadioService extends Service {
    private static final String CH_ID = "radio";
    private boolean channelMade;

    public interface UiListener {
        void onChanged();
        void onAis(String line);
        void onError(String msg);
    }

    public class LocalBinder extends Binder {
        public RadioService get() { return RadioService.this; }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final IBinder binder = new LocalBinder();
    private UiListener listener;
    private Prefs prefs;
    private PowerManager.WakeLock wake;

    public PanelLogic panel;
    private RadioEngine engine;
    private AudioPlayer player;
    private AudioRecorder recorder;
    private NmeaServer nmea;

    // состояние для экрана (пишется в главном потоке)
    public volatile boolean connected, connecting;
    public volatile String mode = "IDLE", info = "", recName = "";
    public volatile boolean open;
    public volatile double rssi, levelA, levelB;
    public volatile int aisCount;
    public volatile int scanHop, scanHops, scanK, scanTotal;
    public volatile List<RadioEngine.ScanResult> scanCur = new ArrayList<>(), scanFinal = new ArrayList<>();
    public final List<String> aisLog = new ArrayList<>();

    @Override public IBinder onBind(Intent i) { return binder; }

    public void setListener(UiListener l) { listener = l; }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        panel = new PanelLogic(prefs.channel(), prefs.listenShip());
        recorder = new AudioRecorder(this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "marinevhf:radio");
        wake.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        foreground("Остановлено");
        return START_NOT_STICKY;
    }

    // ---------------------------------------------------------------- подключение
    public void connect() {
        if (engine != null) return;
        connecting = true;
        foreground("Подключение к драйверу…");
        player = new AudioPlayer();
        player.setVolume(prefs.volume());
        try {
            nmea = new NmeaServer(prefs.tcpPort(), prefs.udpHost(), prefs.udpPort());
            nmea.start();
        } catch (Exception e) {
            nmea = null;
            post(() -> { if (listener != null) listener.onError("NMEA-порт не открыт: " + e.getMessage()); });
        }
        engine = new RadioEngine(
                () -> RtlTcpClient.connect("127.0.0.1", DriverManager.PORT, 20, 500),
                new EngineEvents(), player, recorder, nmea);
        engine.sql = prefs.sql();
        engine.ppm = prefs.ppm();
        engine.recordOnlyOpen = prefs.recOnlyOpen();
        engine.scanThresholdDb = prefs.threshold();
        double g = prefs.gain();
        engine.setGain(g < 0 ? null : g);
        engine.tune(panel.freqHz);
        engine.setMode("VOICE");
        wake.acquire();
        engine.start();
        changed();
    }

    /** Отключение по команде пользователя или после ошибки: снимает уведомление и останавливает сервис. */
    public void disconnect() {
        release();
        stopSelf();
    }

    private void release() {
        stopRecording();
        if (engine != null) { engine.shutdown(); engine = null; }
        if (player != null) { player.release(); player = null; }
        if (nmea != null) { nmea.stop(); nmea = null; }
        if (wake.isHeld()) wake.release();
        connected = connecting = false;
        mode = "IDLE";
        stopForeground(true);
        changed();
    }

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- события приёмника
    private final class EngineEvents implements RadioEngine.Listener {
        public void onReady(String s) {
            post(() -> { connecting = false; connected = true; info = s; foreground(s); changed(); });
        }

        public void onError(String m) {
            post(() -> {
                boolean was = connected || connecting;
                disconnect();
                if (was && listener != null) listener.onError(m);
            });
        }

        public void onMode(String m) { post(() -> { mode = m; changed(); }); }

        public void onVoice(long f, boolean o, double r) {
            boolean ch = o != open;
            open = o;
            rssi = r;
            if (listener == null && !ch) return;                 // экран закрыт — не будим главный поток
            if (ch) post(RadioService.this::changed);
            else post(RadioService.this::changedLight);
        }

        public void onAis(char c, String[] s, Ais.Msg m, String text, int n) {
            String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + c + " " + text;
            post(() -> {
                aisCount = n;
                aisLog.add(0, line);
                while (aisLog.size() > 200) aisLog.remove(aisLog.size() - 1);
                if (listener != null) listener.onAis(line);
                changed();
            });
        }

        public void onAisLevel(double a, double b) {
            levelA = a; levelB = b;
            if (listener != null) post(RadioService.this::changedLight);
        }

        public void onScanProgress(int hop, int hops, int k, int total, List<RadioEngine.ScanResult> res) {
            scanHop = hop; scanHops = hops; scanK = k; scanTotal = total; scanCur = res;
            if (listener != null) post(RadioService.this::changedLight);
        }

        public void onScanDone(List<RadioEngine.ScanResult> all) {
            post(() -> {
                scanFinal = all;
                if (engine != null) engine.setMode("VOICE");     // обзор закончен — возвращаемся к каналу
                changed();
            });
        }
    }

    private void post(Runnable r) { ui.post(r); }

    private long lastLight;

    private void changedLight() {
        long t = System.currentTimeMillis();
        if (t - lastLight < 150) return;
        lastLight = t;
        changed();
    }

    private void changed() { if (listener != null) listener.onChanged(); }

    // ---------------------------------------------------------------- команды панели
    private void retune() {
        if (engine == null) return;
        engine.tune(panel.freqHz);
        engine.setMode("VOICE");
        prefs.edit().putInt("ch", panel.channel == null ? 16 : panel.channel).putBoolean("ship", panel.listenShip).apply();
        changed();
    }

    public void key(char k) { panel.key(k); changed(); }
    public void clear() { panel.clear(); changed(); }
    public void enter() { if (panel.enter()) retune(); else changed(); }
    public void toggleInput() { panel.toggleInput(); changed(); }
    public void ch16() { if (panel.ch16()) retune(); }
    public void step(int d) { if (panel.step(d)) retune(); }
    public void toggleSide() { if (panel.toggleSide()) retune(); else changed(); }

    public void toggleAis() {
        if (engine == null) return;
        engine.setMode(mode.equals("AIS") ? "VOICE" : "AIS");
    }

    public void startScan() {
        if (engine == null) return;
        scanCur = new ArrayList<>();
        engine.scanBlocks = 6;
        engine.setMode("SCAN");
    }

    public void stopScan() { if (engine != null) retune(); }

    public void jumpTo(RadioEngine.ScanResult r) {
        if (r.ch > 0) panel.gotoChannel(r.ch, r.ship); else panel.gotoFreq(r.hz);
        retune();
    }

    public void setSql(int v) { if (engine != null) engine.sql = v; }

    public void setVolume(float v) {
        if (player != null) player.setVolume(v);
    }

    /** Применяет сохранённые настройки к работающему приёмнику. */
    public void applySettings() {
        if (engine == null) return;
        engine.ppm = prefs.ppm();
        engine.recordOnlyOpen = prefs.recOnlyOpen();
        engine.scanThresholdDb = prefs.threshold();
        double g = prefs.gain();
        engine.setGain(g < 0 ? null : g);
    }

    // ---------------------------------------------------------------- запись
    public boolean isRecording() { return recorder.isActive(); }

    public void toggleRecording() {
        if (recorder.isActive()) { stopRecording(); return; }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        try {
            recName = recorder.start(prefs.recFormat(), stamp);
            if (recorder.notice() != null && listener != null) listener.onError(recorder.notice());
        } catch (Exception e) {
            if (listener != null) listener.onError("Запись не начата: " + e.getMessage());
        }
        changed();
    }

    public void stopRecording() {
        if (recorder.isActive()) recorder.stop();
        String err = recorder.lastError();
        if (err != null && listener != null) listener.onError("Ошибка записи: " + err);
        changed();
    }

    // ---------------------------------------------------------------- уведомление
    private void foreground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (!channelMade) {
            nm.createNotificationChannel(new NotificationChannel(CH_ID, "Приём", NotificationManager.IMPORTANCE_LOW));
            channelMade = true;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setContentTitle("Морская УКВ")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);
    }

    // для экрана
    public String freqText() { return String.format(Locale.US, "%.4f", panel.freqHz / 1e6); }

    public String channelNote() { return panel.channel == null ? "" : Channels.note(panel.channel); }
}

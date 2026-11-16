package com.marinevhf.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.marinevhf.R;
import com.marinevhf.core.RadioEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements RadioService.UiListener {
    private RadioService svc;
    private Prefs prefs;
    private LcdView lcd;
    private ChannelChart chart;
    private SliderView slSql, slVol;
    private TextView tStatus, tScan, tAisHead;
    private Button bConn, bAis, bScan, bRec, bSide;
    private int lastAisCount = -1;
    private AlertDialog aisDialog;
    private boolean wantConnect;
    private boolean receiverOn;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((RadioService.LocalBinder) b).get();
            svc.setListener(MainActivity.this);
            refresh();
            if (wantConnect) { wantConnect = false; startRadio(); }
        }

        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    private final BroadcastReceiver installDone = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i.getBooleanExtra("ok", false)) {
                toast("Драйвер установлен");
                connectClicked();
            } else {
                new AlertDialog.Builder(MainActivity.this).setTitle("Драйвер не установлен")
                        .setMessage(String.valueOf(i.getStringExtra("msg")))
                        .setPositiveButton("OK", null).show();
            }
        }
    };

    // ---------------------------------------------------------------- жизненный цикл
    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        lcd = findViewById(R.id.lcd);
        chart = findViewById(R.id.chart);
        slSql = findViewById(R.id.slSql);
        slVol = findViewById(R.id.slVol);
        tStatus = findViewById(R.id.tStatus);
        tScan = findViewById(R.id.tScan);
        tAisHead = findViewById(R.id.tAisHead);
        bConn = findViewById(R.id.bConn);
        bAis = findViewById(R.id.bAis);
        bScan = findViewById(R.id.bScan);
        bRec = findViewById(R.id.bRec);
        bSide = findViewById(R.id.bSide);
        wireButtons();
        initSliders();
        applyKeepOn();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        if (s == null && !DriverManager.isInstalled(this)) askDriver();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, RadioService.class), conn, Context.BIND_AUTO_CREATE);
        IntentFilter f = new IntentFilter(InstallReceiver.ACTION_DONE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(installDone, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(installDone, f);
        receiverOn = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (svc != null) svc.setListener(null);
        unbindService(conn);
        svc = null;
        if (receiverOn) { unregisterReceiver(installDone); receiverOn = false; }
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Экран не гаснет только если это включено в настройках: иначе приём идёт в фоне и экономит батарею. */
    private void applyKeepOn() {
        if (prefs.keepOn()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void toast(String t) { Toast.makeText(this, t, Toast.LENGTH_LONG).show(); }

    // ---------------------------------------------------------------- кнопки
    private void keyBtn(int id, char k) {
        findViewById(id).setOnClickListener(v -> { if (svc != null) svc.key(k); });
    }

    private void wireButtons() {
        int[] ids = {R.id.k0, R.id.k1, R.id.k2, R.id.k3, R.id.k4, R.id.k5, R.id.k6, R.id.k7, R.id.k8, R.id.k9};
        for (int i = 0; i < 10; i++) keyBtn(ids[i], (char) ('0' + i));
        keyBtn(R.id.kDot, '.');
        findViewById(R.id.kClr).setOnClickListener(v -> { if (svc != null) svc.clear(); });
        findViewById(R.id.kEnt).setOnClickListener(v -> { if (svc != null) svc.enter(); });
        findViewById(R.id.bMode).setOnClickListener(v -> { if (svc != null) svc.toggleInput(); });
        findViewById(R.id.bUp).setOnClickListener(v -> { if (svc != null) svc.step(+1); });
        findViewById(R.id.bDown).setOnClickListener(v -> { if (svc != null) svc.step(-1); });
        findViewById(R.id.b16).setOnClickListener(v -> { if (svc != null) svc.ch16(); });
        bSide.setOnClickListener(v -> { if (svc != null) svc.toggleSide(); });
        bAis.setOnClickListener(v -> { if (svc != null) svc.toggleAis(); });
        bScan.setOnClickListener(v -> {
            if (svc == null) return;
            if (svc.mode.equals("SCAN")) svc.stopScan(); else svc.startScan();
        });
        bRec.setOnClickListener(v -> { if (svc != null) svc.toggleRecording(); });
        bConn.setOnClickListener(v -> connectClicked());
        findViewById(R.id.bSet).setOnClickListener(v -> settingsDialog());
        tAisHead.setOnClickListener(v -> showAisLog());
        chart.setOnPick(r -> { if (svc != null) svc.jumpTo(r); });
    }

    private void initSliders() {
        slVol.setValue(Math.round(prefs.volume() * 10));
        slSql.setValue(prefs.sql());
        slVol.setListener(new SliderView.Listener() {
            public void onChange(int v) { if (svc != null) svc.setVolume(v / 10f); }
            public void onCommit(int v) { prefs.edit().putFloat("vol", v / 10f).apply(); }
        });
        slSql.setListener(new SliderView.Listener() {
            public void onChange(int v) { if (svc != null) svc.setSql(v); }
            public void onCommit(int v) { prefs.edit().putInt("sql", v).apply(); }
        });
    }

    // ---------------------------------------------------------------- драйвер
    private void connectClicked() {
        if (svc != null && (svc.connected || svc.connecting)) {
            svc.disconnect();
            return;
        }
        if (!DriverManager.isInstalled(this)) { askDriver(); return; }
        if (!DriverManager.start(this)) toast("Не удалось запустить драйвер");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != DriverManager.REQ_START) return;
        if (res == RESULT_OK) {
            if (svc != null) startRadio(); else wantConnect = true;
        } else {
            String e = DriverManager.errorText(data);
            new AlertDialog.Builder(this).setTitle("Драйвер не запустился")
                    .setMessage("Проверьте, что RTL-SDR подключён через OTG-переходник, и разрешите доступ к USB."
                            + (e == null ? "" : "\n\n" + e))
                    .setPositiveButton("OK", null).show();
        }
    }

    private void startRadio() {
        startForegroundService(new Intent(this, RadioService.class));
        svc.connect();
        xiaomiHint();
    }

    private void askDriver() {
        String how = DriverInstaller.hasBundled(this) ? "Он входит в состав приложения."
                : "Он будет загружен с F-Droid (нужен интернет).";
        new AlertDialog.Builder(this).setTitle("Нужен драйвер RTL-SDR")
                .setMessage("Для работы нужно приложение «Rtl-sdr driver» (marto.rtl_tcp_andro). " + how
                        + "\n\nУстановить сейчас?")
                .setPositiveButton("Установить", (d, w) -> installDriver())
                .setNegativeButton("Позже", null).show();
    }

    private void installDriver() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            toast("Разрешите установку из этого приложения и нажмите «Установить» ещё раз");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        Context app = getApplicationContext();
        new Thread(() -> {
            try {
                DriverInstaller.install(app, t -> runOnUiThread(() -> tStatus.setText(t)));
            } catch (Exception e) {
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Не удалось получить драйвер")
                        .setMessage(e.getMessage() + "\n\nУстановите «Rtl-sdr driver» вручную из F-Droid или Google Play.")
                        .setPositiveButton("OK", null).show());
            }
        }, "driver-install").start();
    }

    private void xiaomiHint() {
        String m = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        if (!(m.contains("xiaomi") || m.contains("redmi") || m.contains("poco"))) return;
        if (getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("hint", false)) return;
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("hint", true).apply();
        new AlertDialog.Builder(this).setTitle("Работа в фоне на Xiaomi")
                .setMessage("Чтобы приём не прерывался при выключенном экране: в настройках приложения включите "
                        + "«Автозапуск» и выберите «Экономия заряда → Нет ограничений».")
                .setPositiveButton("Открыть настройки", (d, w) -> startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Позже", null).show();
    }

    // ---------------------------------------------------------------- настройки
    private EditText field(LinearLayout l, String label, String value, boolean number) {
        TextView t = new TextView(this);
        t.setText(label);
        l.addView(t);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine();
        if (number) e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        l.addView(e);
        return e;
    }

    private void settingsDialog() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(40, 20, 40, 0);
        EditText tcp = field(l, "NMEA TCP-порт (сервер на этом устройстве)", String.valueOf(prefs.tcpPort()), true);
        EditText uh = field(l, "NMEA UDP: адрес", prefs.udpHost(), false);
        EditText up = field(l, "NMEA UDP: порт", String.valueOf(prefs.udpPort()), true);
        EditText ppm = field(l, "Коррекция частоты, ppm", String.valueOf(prefs.ppm()), true);
        double g = prefs.gain();
        EditText gain = field(l, "Усиление, дБ (пусто — авто)", g < 0 ? "" : String.valueOf(g), true);
        EditText thr = field(l, "Порог обнаружения при сканировании, дБ", String.valueOf(prefs.threshold()), true);
        TextView ft = new TextView(this);
        ft.setText("Формат записи");
        l.addView(ft);
        List<String> names = new ArrayList<>(), keys = new ArrayList<>();
        names.add("AAC (M4A) — рекомендуется"); keys.add(AudioRecorder.AAC);
        if (Build.VERSION.SDK_INT >= 29) { names.add("Opus (Ogg) — компактнее"); keys.add(AudioRecorder.OPUS); }
        names.add("WAV — без сжатия"); keys.add(AudioRecorder.WAV);
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        sp.setSelection(Math.max(0, keys.indexOf(prefs.recFormat())));
        l.addView(sp);
        CheckBox only = new CheckBox(this);
        only.setText("Писать только при открытом шумоподавителе");
        only.setChecked(prefs.recOnlyOpen());
        l.addView(only);
        CheckBox keep = new CheckBox(this);
        keep.setText("Не гасить экран (больше расход батареи)");
        keep.setChecked(prefs.keepOn());
        l.addView(keep);
        TextView note = new TextView(this);
        note.setText("Порты NMEA применяются при следующем подключении. Записи — в Музыка/MarineVHF.");
        l.addView(note);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(l);
        new AlertDialog.Builder(this).setTitle("Настройки").setView(sv)
                .setPositiveButton("Сохранить", (d, w) -> {
                    try {
                        prefs.edit()
                                .putInt("tcp", Integer.parseInt(tcp.getText().toString().trim()))
                                .putString("udpHost", uh.getText().toString().trim())
                                .putInt("udp", Integer.parseInt(up.getText().toString().trim()))
                                .putInt("ppm", Integer.parseInt(ppm.getText().toString().trim()))
                                .putFloat("gain", gain.getText().toString().trim().isEmpty() ? -1f
                                        : Float.parseFloat(gain.getText().toString().trim()))
                                .putFloat("thr", Float.parseFloat(thr.getText().toString().trim()))
                                .putString("fmt", keys.get(sp.getSelectedItemPosition()))
                                .putBoolean("onlyOpen", only.isChecked())
                                .putBoolean("keepOn", keep.isChecked()).apply();
                        applyKeepOn();
                        if (svc != null) svc.applySettings();
                    } catch (NumberFormatException e) {
                        toast("Неверное число в настройках");
                    }
                }).setNegativeButton("Отмена", null).show();
    }

    // ---------------------------------------------------------------- обновление экрана
    @Override public void onChanged() { refresh(); }

    @Override public void onAis(String line) { }

    @Override public void onError(String msg) {
        new AlertDialog.Builder(this).setTitle("Ошибка").setMessage(msg).setPositiveButton("OK", null).show();
    }

    private void refresh() {
        if (svc == null) return;
        com.marinevhf.core.PanelLogic p = svc.panel;
        String mode = svc.mode;
        boolean scanning = mode.equals("SCAN"), aisMode = mode.equals("AIS"), voice = mode.equals("VOICE");
        lcd.channel = p.channel == null ? "--" : String.format(Locale.US, "%02d", p.channel);
        lcd.freq = (p.freqInput && !p.buf.isEmpty()) ? p.buf : svc.freqText();
        lcd.freqInput = p.freqInput;
        lcd.note = svc.channelNote();
        lcd.input = p.buf;
        lcd.message = p.message;
        lcd.ship = p.listenShip;
        lcd.duplex = p.isDuplex();
        lcd.connected = svc.connected;
        lcd.open = svc.open && voice;
        lcd.rec = svc.isRecording();
        lcd.ais = aisMode;
        lcd.mode = aisMode ? "АИС-МОНИТОР" : scanning ? "СКАНИРОВАНИЕ" : svc.connecting ? "ПОДКЛЮЧЕНИЕ…" : "";
        lcd.bars = voice ? Math.max(0, Math.min(8, (int) Math.round(svc.rssi / 4))) : 0;
        lcd.commit();

        bSide.setText(p.listenShip ? "СУДНО" : "БЕРЕГ");
        bConn.setText(svc.connected || svc.connecting ? "ОТКЛ" : "ПОДКЛ");
        bAis.setBackgroundResource(aisMode ? R.drawable.btn_on : R.drawable.btn_fn);
        bScan.setText(scanning ? "СТОП" : "СКАН");
        boolean rec = svc.isRecording();
        bRec.setText(rec ? "■ СТОП" : "● ЗАПИСЬ");
        bRec.setTextColor(rec ? 0xFFFFFFFF : 0xFFFF4B40);
        bRec.setBackgroundResource(rec ? R.drawable.btn_rec : R.drawable.btn_fn);
        String st = svc.connected ? svc.info : svc.connecting ? "Подключение к драйверу…" : "Не подключено";
        tStatus.setText(rec ? st + "  ● " + svc.recName : st);

        List<RadioEngine.ScanResult> data = scanning ? svc.scanCur : svc.scanFinal;
        float prog = 0;
        if (scanning) {
            prog = svc.scanHops == 0 ? 0 : (svc.scanHop * svc.scanTotal + svc.scanK) / (float) (svc.scanHops * svc.scanTotal);
            tScan.setText(String.format(Locale.US, "участок %d из %d, %d%%", svc.scanHop + 1, svc.scanHops,
                    Math.round(prog * 100)));
        } else if (!svc.scanFinal.isEmpty()) {
            int n = 0;
            for (RadioEngine.ScanResult r : svc.scanFinal) if (r.active) n++;
            tScan.setText("обзор завершён, активных: " + n + "; нажмите на столбик — слушать");
        } else {
            tScan.setText("синий — судовая сторона, бирюзовый — береговая; клик — слушать канал");
        }
        chart.set(data, prog, prefs.threshold(), p.channel == null ? -1 : p.channel, p.listenShip);

        if (svc.aisCount != lastAisCount || aisMode) {
            lastAisCount = svc.aisCount;
            tAisHead.setText("АИС: принято " + svc.aisCount + (aisMode
                    ? String.format(Locale.US, "   уровень A %.0f / B %.0f дБ", svc.levelA, svc.levelB) : "")
                    + "   (журнал — нажмите)");
        }
    }

    private void showAisLog() {
        if (svc == null) return;
        StringBuilder sb = new StringBuilder();
        for (String s : svc.aisLog) sb.append(s).append('\n');
        TextView t = new TextView(this);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextSize(11);
        t.setPadding(24, 16, 24, 16);
        t.setText(sb.length() == 0 ? "Пока ничего не принято. Включите АИС." : sb);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(t);
        if (aisDialog != null && aisDialog.isShowing()) aisDialog.dismiss();
        aisDialog = new AlertDialog.Builder(this).setTitle("Журнал АИС").setView(sv)
                .setPositiveButton("Закрыть", null).show();
    }
}

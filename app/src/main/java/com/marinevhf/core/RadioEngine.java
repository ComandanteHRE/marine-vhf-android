package com.marinevhf.core;

import static com.marinevhf.dsp.Dsp.*;

import com.marinevhf.dsp.Ais;
import com.marinevhf.dsp.AisDecoder;
import com.marinevhf.dsp.Channels;
import com.marinevhf.dsp.NfmVoice;
import com.marinevhf.dsp.Spectrum;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ядро приёма (порт radio.py): поток, владеющий источником IQ.
 * Режимы: VOICE (канал), SCAN (обзор диапазона), AIS, IDLE. Не зависит от Android.
 */
public final class RadioEngine extends Thread {
    public interface SourceFactory { IqSource open() throws IOException; }

    public interface AudioSink {
        void push(float[] block, int n);
        void clear();
    }

    public interface PcmSink {
        boolean isActive();
        void write(float[] block, int n);
    }

    public interface NmeaSink { void send(String sentence); }

    public static final class ScanResult {
        public int ch;
        public boolean ship, active;
        public long hz;
        public double db;
        public String label;
    }

    public interface Listener {
        void onReady(String info);
        void onError(String msg);
        void onMode(String mode);
        void onVoice(long freqHz, boolean open, double rssi);
        void onAis(char chan, String[] sentences, Ais.Msg msg, String text, int count);
        void onAisLevel(double a, double b);
        void onScanProgress(int hop, int hops, int k, int total, List<ScanResult> res);
        void onScanDone(List<ScanResult> all);
    }

    private boolean tail;
    private static final int SETTLE_BLOCKS = 5;       // отбросить после перестройки (буферы драйвера)

    // настройки (можно менять из UI-потока)
    public volatile int sql = 3;
    public volatile boolean recordOnlyOpen = true;
    public volatile int scanBlocks = 6;
    public volatile double scanThresholdDb = 8.0;
    public volatile int ppm = 0;

    private final SourceFactory factory;
    private final Listener listener;
    private final AudioSink audio;
    private final PcmSink pcm;
    private final NmeaSink nmea;
    private volatile boolean running = true;
    private volatile IqSource srcRef;

    private String mode = "IDLE";
    private long freqHz = 156_800_000L;
    private String pendMode;
    private long pendFreq = -1;
    private Double gainDb = null, pendGain;
    private boolean pendGainSet;

    private NfmVoice voice;
    private AisDecoder ais;
    private int aisCount = 0;
    private final Spectrum spectrum = new Spectrum();
    private final float[] re = new float[BLOCK], im = new float[BLOCK];
    private final float[] audioBuf = new float[AUDIO_BLOCK];
    // состояние обзора
    private int scHop, scK, scSegs;
    private double[] scAcc = new double[Spectrum.NFFT];
    private final List<ScanResult> scAll = new ArrayList<>();

    public RadioEngine(SourceFactory f, Listener l, AudioSink a, PcmSink p, NmeaSink n) {
        super("radio-engine");
        factory = f;
        listener = l;
        audio = a;
        pcm = p;
        nmea = n;
        setDaemon(true);
    }

    // ---------------------------------------------------------------- команды из UI
    public synchronized void tune(long hz) {
        pendFreq = hz;
    }

    public synchronized void setMode(String m) {
        pendMode = m;
    }

    /** null — автоматическая регулировка. */
    public synchronized void setGain(Double db) {
        pendGain = db;
        pendGainSet = true;
    }

    public void shutdown() {
        running = false;
        IqSource s = srcRef;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    // ---------------------------------------------------------------- цикл
    @Override
    public void run() {
        IqSource src;
        try {
            src = factory.open();
            srcRef = src;
            src.setSampleRate(FS);
            if (ppm != 0) src.setPpm(ppm);
            applyGain(src);
        } catch (IOException e) {
            if (running) listener.onError(e.getMessage());
            return;
        }
        listener.onReady(src.info());
        try {
            while (running) {
                applyPending(src);
                switch (mode) {
                    case "VOICE": voiceStep(src); break;
                    case "SCAN": scanStep(src); break;
                    case "AIS": aisStep(src); break;
                    default: Thread.sleep(50);
                }
            }
        } catch (IOException e) {
            if (running) listener.onError("Связь с драйвером потеряна: " + e.getMessage());
        } catch (InterruptedException ignored) {
        } finally {
            try { src.close(); } catch (IOException ignored) { }
        }
    }

    private void applyGain(IqSource s) throws IOException {
        if (gainDb == null) s.setGainAuto();
        else s.setGainDb(gainDb);
    }

    private void applyPending(IqSource src) throws IOException {
        String m;
        long f;
        boolean g;
        Double gv;
        synchronized (this) {
            m = pendMode; pendMode = null;
            f = pendFreq; pendFreq = -1;
            g = pendGainSet; gv = pendGain; pendGainSet = false;
        }
        if (g) {
            gainDb = gv;
            applyGain(src);
        }
        if (f >= 0) {
            freqHz = f;
            if (mode.equals("VOICE") && (m == null || m.equals("VOICE"))) enter(src, "VOICE");
        }
        if (m != null && (!m.equals(mode) || m.equals("SCAN"))) {
            mode = m;
            enter(src, m);
            listener.onMode(m);
        }
    }

    private void discard(IqSource src) throws IOException {
        src.flush();
        for (int i = 0; i < SETTLE_BLOCKS; i++) src.read(re, im);
    }

    private void enter(IqSource src, String m) throws IOException {
        switch (m) {
            case "VOICE":
                src.setCenterFreq(freqHz - VOICE_OFFSET_HZ);
                if (voice == null) voice = new NfmVoice();
                discard(src);
                if (audio != null) audio.clear();
                break;
            case "AIS":
                src.setCenterFreq(AisDecoder.CENTER_HZ);
                ais = new AisDecoder();
                discard(src);
                break;
            case "SCAN":
                scHop = 0;
                scK = 0;
                scAll.clear();
                break;
            default:
        }
    }

    // ---------------------------------------------------------------- голос
    private void voiceStep(IqSource src) throws IOException {
        src.read(re, im);
        NfmVoice.Info info = voice.process(re, im, sql, audioBuf);
        // при закрытом шумодаве звук не гоним (экономия CPU); один блок «хвоста» — чтобы не обрывать слово
        if (audio != null && (info.open || tail)) audio.push(audioBuf, AUDIO_BLOCK);
        tail = info.open;
        if (pcm != null && pcm.isActive() && (info.open || !recordOnlyOpen)) pcm.write(audioBuf, AUDIO_BLOCK);
        listener.onVoice(freqHz, info.open, info.rssi);
    }

    // ---------------------------------------------------------------- АИС
    private void aisStep(IqSource src) throws IOException {
        src.read(re, im);
        for (AisDecoder.Frame f : ais.process(re, im)) {
            String[] s = Ais.sentences(f.bits, f.chan);
            if (nmea != null) for (String line : s) nmea.send(line);
            aisCount++;
            Ais.Msg m = Ais.parse(f.bits);
            listener.onAis(f.chan, s, m, Ais.describe(m), aisCount);
        }
        listener.onAisLevel(ais.levelA(), ais.levelB());
    }

    // ---------------------------------------------------------------- обзор диапазона
    private void scanStep(IqSource src) throws IOException {
        int hops = Channels.SCAN_HOPS.length, total = Math.max(1, scanBlocks);
        long center = Channels.SCAN_HOPS[scHop];
        long[] grid = Channels.grid(scHop);
        if (scK == 0) {
            src.setCenterFreq(center);
            discard(src);
            java.util.Arrays.fill(scAcc, 0);
            scSegs = 0;
        }
        src.read(re, im);
        scSegs += spectrum.addPsd(re, im, BLOCK, scAcc);
        scK++;
        double[] avg = new double[scAcc.length];
        for (int i = 0; i < avg.length; i++) avg[i] = scAcc[i] / scSegs;
        double[] exc = Spectrum.excessOverFloor(Spectrum.channelPowerDb(avg, center, grid, 14_000));
        List<ScanResult> res = new ArrayList<>();
        for (int i = 0; i < grid.length; i++) {
            Channels.Slot sl = Channels.slot(grid[i]);
            if (sl == null) continue;
            ScanResult r = new ScanResult();
            r.ch = sl.ch;
            r.ship = sl.ship;
            r.hz = sl.hz;
            r.label = sl.label;
            r.db = exc[i];
            r.active = exc[i] >= scanThresholdDb && sl.ch != 0;
            res.add(r);
        }
        listener.onScanProgress(scHop, hops, scK, total, res);
        if (scK >= total) {
            scAll.addAll(res);
            scHop++;
            scK = 0;
            if (scHop >= hops) {
                listener.onScanDone(new ArrayList<>(scAll));
                scHop = 0;
                scAll.clear();
            }
        }
    }
}

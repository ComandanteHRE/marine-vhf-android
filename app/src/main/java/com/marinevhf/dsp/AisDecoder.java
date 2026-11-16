package com.marinevhf.dsp;

import static com.marinevhf.dsp.Dsp.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Приём АИС (порт ais.py): GMSK 9600 бод на 161.975 / 162.025 МГц, центр SDR 162.000 МГц.
 * Детектор: частота минус скользящее среднее за 25 символов -> знак -> NRZI -> HDLC; 5 фаз синхронизации.
 */
public final class AisDecoder {
    public static final long CENTER_HZ = 162_000_000L;
    public static final long AIS1_HZ = 161_975_000L, AIS2_HZ = 162_025_000L;
    private static final int SPS = 5;
    private static final byte[] FLAG = {0, 1, 1, 1, 1, 1, 1, 0};

    public static final class Frame {
        public final char chan;
        public final byte[] bits;

        Frame(char c, byte[] b) {
            chan = c;
            bits = b;
        }
    }

    // ---------------------------------------------------------------- HDLC
    private static boolean plausible(byte[] b) {
        if (b.length < 38) return false;
        int t = (int) Ais.uint(b, 0, 6);
        if (t < 1 || t > 27 || Ais.uint(b, 8, 38) == 0) return false;
        int lo = 40, hi = 1064;
        switch (t) {
            case 1: case 2: case 3: case 4: case 9: case 18: lo = hi = 168; break;
            case 5: lo = 420; hi = 424; break;
            case 19: lo = hi = 312; break;
            case 21: lo = 272; hi = 360; break;
            case 24: lo = 160; hi = 168; break;
            case 27: lo = 96; hi = 168; break;
            default:
        }
        return b.length >= lo && b.length <= hi;
    }

    /** Биты между флагами -> биты сообщения без FCS, либо null. */
    static byte[] decodeFrame(byte[] buf, int from, int to) {
        byte[] d = new byte[to - from];
        int n = 0, ones = 0;
        for (int i = from; i < to; i++) {
            if (buf[i] != 0) {
                if (++ones > 5) return null;
                d[n++] = 1;
            } else {
                if (ones == 5) {
                    ones = 0;
                    continue;
                }
                ones = 0;
                d[n++] = 0;
            }
        }
        if (n % 8 != 0 || n < 56) return null;
        byte[] bytes = new byte[n / 8];
        for (int i = 0; i < n; i++) bytes[i >> 3] |= (byte) (d[i] << (i & 7));     // первый бит — младший
        if (Ais.crc16(bytes, bytes.length) != Ais.CRC_GOOD) return null;
        byte[] out = new byte[n - 16];
        System.arraycopy(d, 0, out, 0, n - 16);
        return plausible(out) ? out : null;
    }

    private static final class HdlcPhase {
        byte[] buf = new byte[8192];
        int len = 0;

        List<byte[]> feed(byte[] dec, int n) {
            List<byte[]> frames = new ArrayList<>();
            if (len + n > buf.length) {
                byte[] nb = new byte[Math.max(buf.length * 2, len + n)];
                System.arraycopy(buf, 0, nb, 0, len);
                buf = nb;
            }
            System.arraycopy(dec, 0, buf, len, n);
            len += n;
            if (len < 16) return frames;
            int last = -1, prev = -1;
            for (int i = 0; i <= len - 8; i++) {
                if (buf[i] != 0 || buf[i + 7] != 0) continue;
                boolean ok = true;
                for (int k = 1; k <= 6; k++) if (buf[i + k] != 1) { ok = false; break; }
                if (!ok) continue;
                if (prev >= 0) {
                    int cnt = i - (prev + 8);
                    if (cnt >= 56 && cnt <= 1500) {
                        byte[] f = decodeFrame(buf, prev + 8, i);
                        if (f != null) frames.add(f);
                    }
                }
                prev = i;
                last = i;
            }
            if (last >= 0) {
                System.arraycopy(buf, last, buf, 0, len - last);
                len -= last;
            } else if (len > 1600) {
                System.arraycopy(buf, len - 1600, buf, 0, 1600);
                len = 1600;
            }
            if (len > 4000) {
                System.arraycopy(buf, len - 1600, buf, 0, 1600);
                len = 1600;
            }
            return frames;
        }
    }

    // ---------------------------------------------------------------- канал
    private static final class Channel {
        final char name;
        final Mixer mixer;
        final Decimator d1, d2;
        final Fir chan = new Fir(lowpass(101, 10e3, AUDIO_FS));
        final FirReal smooth = new FirReal(lowpass(11, 6.5e3, AUDIO_FS));
        final FirReal mean;
        final FmDemod demod = new FmDemod(2400);
        final HdlcPhase[] phases = new HdlcPhase[SPS];
        final byte[] prevSg = new byte[SPS];
        double rssiDb = -120;
        final float[] mRe = new float[BLOCK], mIm = new float[BLOCK];
        final float[] aRe = new float[BLOCK / 8], aIm = new float[BLOCK / 8];
        final float[] bRe = new float[AUDIO_BLOCK], bIm = new float[AUDIO_BLOCK];
        final float[] d = new float[AUDIO_BLOCK], mu = new float[AUDIO_BLOCK];
        final byte[] dec = new byte[AUDIO_BLOCK / SPS];

        Channel(char name, double offsetHz) {
            this.name = name;
            mixer = new Mixer(offsetHz, BLOCK);
            Decimator[] x = frontDecimators();
            d1 = x[0];
            d2 = x[1];
            double[] box = new double[125];
            java.util.Arrays.fill(box, 1.0 / 125);
            mean = new FirReal(box);
            for (int i = 0; i < SPS; i++) phases[i] = new HdlcPhase();
        }

        List<byte[]> process(float[] re, float[] im) {
            mixer.mix(re, im, mRe, mIm);
            d1.process(mRe, mIm, BLOCK, aRe, aIm);
            d2.process(aRe, aIm, BLOCK / 8, bRe, bIm);
            chan.process(bRe, bIm, AUDIO_BLOCK);
            double s = 0;
            for (int i = 0; i < AUDIO_BLOCK; i++) s += bRe[i] * bRe[i] + bIm[i] * bIm[i];
            rssiDb = 10 * Math.log10(s / AUDIO_BLOCK + 1e-12);
            demod.process(bRe, bIm, AUDIO_BLOCK, d);
            smooth.process(d, AUDIO_BLOCK);
            System.arraycopy(d, 0, mu, 0, AUDIO_BLOCK);
            mean.process(mu, AUDIO_BLOCK);
            List<byte[]> out = new ArrayList<>();
            int nsym = AUDIO_BLOCK / SPS;
            for (int p = 0; p < SPS; p++) {
                byte prev = prevSg[p];
                for (int k = 0; k < nsym; k++) {
                    int idx = k * SPS + p;
                    byte sg = (byte) (d[idx] - mu[idx] > 0 ? 1 : 0);
                    dec[k] = (byte) (sg == prev ? 1 : 0);                 // NRZI: 1 — нет перехода
                    prev = sg;
                }
                prevSg[p] = prev;
                out.addAll(phases[p].feed(dec, nsym));
            }
            return out;
        }
    }

    private final Channel[] ch = {new Channel('A', AIS1_HZ - CENTER_HZ), new Channel('B', AIS2_HZ - CENTER_HZ)};
    private final Map<String, Integer> recent = new HashMap<>();
    private int blk = 0;

    public double levelA() { return ch[0].rssiDb; }

    public double levelB() { return ch[1].rssiDb; }

    /** Один блок BLOCK комплексных отсчётов (центр 162.000 МГц). Дубликаты разных фаз отброшены. */
    public List<Frame> process(float[] re, float[] im) {
        blk++;
        for (Iterator<Map.Entry<String, Integer>> it = recent.entrySet().iterator(); it.hasNext(); )
            if (blk - it.next().getValue() > 3) it.remove();
        List<Frame> res = new ArrayList<>();
        for (Channel c : ch) {
            for (byte[] bits : c.process(re, im)) {
                String key = c.name + new String(bits, StandardCharsets.ISO_8859_1);
                if (recent.containsKey(key)) continue;
                recent.put(key, blk);
                res.add(new Frame(c.name, bits));
            }
        }
        return res;
    }
}

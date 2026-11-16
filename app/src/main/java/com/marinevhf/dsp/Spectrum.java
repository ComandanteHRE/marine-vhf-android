package com.marinevhf.dsp;

import java.util.Arrays;

/** Спектр для обзора диапазона: усреднённый PSD (FFT 8192), мощность в каналах, превышение над «полом». */
public final class Spectrum {
    public static final int NFFT = 8192;
    private static final double[] WIN = new double[NFFT];
    private static final float[] COS = new float[NFFT / 2], SIN = new float[NFFT / 2];
    private static final int[] REV = new int[NFFT];

    static {
        for (int i = 0; i < NFFT; i++) WIN[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (NFFT - 1));
        for (int i = 0; i < NFFT / 2; i++) {
            COS[i] = (float) Math.cos(2 * Math.PI * i / NFFT);
            SIN[i] = (float) -Math.sin(2 * Math.PI * i / NFFT);
        }
        int bits = Integer.numberOfTrailingZeros(NFFT);
        for (int i = 0; i < NFFT; i++) REV[i] = Integer.reverse(i) >>> (32 - bits);
    }

    private final float[] fr = new float[NFFT], fi = new float[NFFT];

    private void fft() {
        for (int i = 0; i < NFFT; i++) {
            int j = REV[i];
            if (j > i) {
                float t = fr[i]; fr[i] = fr[j]; fr[j] = t;
                t = fi[i]; fi[i] = fi[j]; fi[j] = t;
            }
        }
        for (int size = 2; size <= NFFT; size <<= 1) {
            int half = size >> 1, step = NFFT / size;
            for (int i = 0; i < NFFT; i += size) {
                for (int k = 0, t = 0; k < half; k++, t += step) {
                    int a = i + k, b = a + half;
                    float xr = fr[b] * COS[t] - fi[b] * SIN[t];
                    float xi = fr[b] * SIN[t] + fi[b] * COS[t];
                    fr[b] = fr[a] - xr;
                    fi[b] = fi[a] - xi;
                    fr[a] += xr;
                    fi[a] += xi;
                }
            }
        }
    }

    /** Добавляет в acc (длина NFFT) мощность после fftshift; возвращает число сегментов. */
    public int addPsd(float[] re, float[] im, int n, double[] acc) {
        int segs = n / NFFT;
        for (int s = 0; s < segs; s++) {
            int o = s * NFFT;
            for (int i = 0; i < NFFT; i++) {
                fr[i] = (float) (re[o + i] * WIN[i]);
                fi[i] = (float) (im[o + i] * WIN[i]);
            }
            fft();
            for (int i = 0; i < NFFT; i++) acc[(i + NFFT / 2) % NFFT] += (double) fr[i] * fr[i] + (double) fi[i] * fi[i];
        }
        return segs;
    }

    /** Мощность (дБ, отн.) в полосе bwHz вокруг каждой частоты. */
    public static double[] channelPowerDb(double[] psd, double centerHz, long[] freqs, double bwHz) {
        double[] cs = new double[NFFT + 1];
        for (int i = 0; i < NFFT; i++) cs[i + 1] = cs[i] + psd[i];
        double binHz = (double) Dsp.FS / NFFT;
        int half = (int) Math.rint(bwHz / 2 / binHz);
        double[] out = new double[freqs.length];
        for (int k = 0; k < freqs.length; k++) {
            int c = (int) Math.rint(NFFT / 2.0 + (freqs[k] - centerHz) / binHz);
            int lo = Math.max(0, c - half), hi = Math.min(NFFT, c + half + 1);
            out[k] = 10 * Math.log10(Math.max(cs[hi] - cs[lo], 1e-30));
        }
        return out;
    }

    /** Превышение над скользящей медианой (окно 15, края — нули, как scipy.signal.medfilt). */
    public static double[] excessOverFloor(double[] p) {
        int k = 15, h = k / 2, n = p.length;
        double[] out = new double[n], w = new double[k];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < k; j++) {
                int idx = i - h + j;
                w[j] = idx >= 0 && idx < n ? p[idx] : 0.0;
            }
            double[] s = w.clone();
            Arrays.sort(s);
            out[i] = p[i] - s[h];
        }
        return out;
    }
}

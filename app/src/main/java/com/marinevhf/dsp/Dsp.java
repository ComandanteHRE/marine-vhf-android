package com.marinevhf.dsp;

/** Базовые блоки ЦОС (порт dsp.py): расчёт КИХ как scipy.signal.firwin, смеситель, децимация, FM-демодулятор. */
public final class Dsp {
    private Dsp() {}

    public static final int FS = 1_920_000;          // частота дискретизации (rtl_tcp)
    public static final int AUDIO_FS = 48_000;       // FS / 40
    public static final int BLOCK = 128_000;         // комплексных отсчётов в блоке (66.7 мс)
    public static final int AUDIO_BLOCK = BLOCK / 40;
    public static final int VOICE_OFFSET_HZ = 250_000;
    public static final double NOISE_REF = 0.94;     // шумовая метрика пустого канала

    // ---------------------------------------------------------------- проектирование КИХ
    private static double sinc(double x) {
        return x == 0 ? 1.0 : Math.sin(Math.PI * x) / (Math.PI * x);
    }

    private static double[] design(int n, double lo, double hi, double scaleF) {
        double[] h = new double[n];
        double mid = (n - 1) / 2.0, s = 0;
        for (int i = 0; i < n; i++) {
            double m = i - mid, w = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (n - 1));
            h[i] = (hi * sinc(hi * m) - lo * sinc(lo * m)) * w;
            s += h[i] * Math.cos(Math.PI * scaleF * m);
        }
        for (int i = 0; i < n; i++) h[i] /= s;
        return h;
    }

    public static double[] lowpass(int n, double fc, double fs) {
        return design(n, 0.0, fc / (fs / 2), 0.0);
    }

    public static double[] highpass(int n, double fc, double fs) {      // n нечётное
        return design(n, fc / (fs / 2), 1.0, 1.0);
    }

    public static double[] bandpass(int n, double f1, double f2, double fs) {
        double a = f1 / (fs / 2), b = f2 / (fs / 2);
        return design(n, a, b, (a + b) / 2);
    }

    // ---------------------------------------------------------------- смеситель
    /** Перенос частоты freqHz в ноль; блок фиксированной длины, фаза непрерывна. */
    public static final class Mixer {
        private final float[] cs, sn;
        private final double stepRe, stepIm;
        private double phRe = 1, phIm = 0;

        public Mixer(double freqHz, int n) {
            double w = -2 * Math.PI * freqHz / FS;
            cs = new float[n];
            sn = new float[n];
            for (int i = 0; i < n; i++) {
                cs[i] = (float) Math.cos(w * i);
                sn[i] = (float) Math.sin(w * i);
            }
            stepRe = Math.cos(w * n);
            stepIm = Math.sin(w * n);
        }

        public void mix(float[] re, float[] im, float[] oRe, float[] oIm) {
            final float pr = (float) phRe, pi = (float) phIm;
            for (int i = 0; i < cs.length; i++) {
                float a = re[i] * cs[i] - im[i] * sn[i];
                float b = re[i] * sn[i] + im[i] * cs[i];
                oRe[i] = a * pr - b * pi;
                oIm[i] = a * pi + b * pr;
            }
            double nr = phRe * stepRe - phIm * stepIm, ni = phRe * stepIm + phIm * stepRe;
            double a = Math.hypot(nr, ni);
            phRe = nr / a;
            phIm = ni / a;
        }
    }

    // ---------------------------------------------------------------- прореживание
    /** КИХ-децимация комплексного потока с состоянием между блоками. */
    public static final class Decimator {
        private final float[] h;
        private final int m, L;
        private float[] er, ei;

        public Decimator(double[] taps, int m) {
            if (taps.length % m != 0) throw new IllegalArgumentException("taps % m");
            L = taps.length;
            this.m = m;
            h = new float[L];
            for (int i = 0; i < L; i++) h[i] = (float) taps[i];
        }

        /** n — число входных отсчётов (кратно m); на выходе n/m. */
        public void process(float[] re, float[] im, int n, float[] oRe, float[] oIm) {
            if (er == null || er.length < L + n) {
                float[] nr = new float[L + n], ni = new float[L + n];
                if (er != null) {
                    System.arraycopy(er, 0, nr, 0, Math.min(L, er.length));
                    System.arraycopy(ei, 0, ni, 0, Math.min(L, ei.length));
                }
                er = nr;
                ei = ni;
            }
            System.arraycopy(re, 0, er, L, n);
            System.arraycopy(im, 0, ei, L, n);
            int outN = n / m;
            for (int j = 0; j < outN; j++) {
                int base = L + j * m;
                double sr = 0, si = 0;
                for (int k = 0; k < L; k++) {
                    sr += h[k] * er[base - k];
                    si += h[k] * ei[base - k];
                }
                oRe[j] = (float) sr;
                oIm[j] = (float) si;
            }
            System.arraycopy(er, n, er, 0, L);        // хвост = последние L отсчётов
            System.arraycopy(ei, n, ei, 0, L);
        }
    }

    // ---------------------------------------------------------------- КИХ с состоянием
    /** КИХ-фильтр: комплексные данные (на месте). */
    public static final class Fir {
        private final float[] h;
        private final int L;
        private float[] er, ei;

        public Fir(double[] taps) {
            L = taps.length;
            h = new float[L];
            for (int i = 0; i < L; i++) h[i] = (float) taps[i];
            er = new float[L - 1];
            ei = new float[L - 1];
        }

        public void process(float[] re, float[] im, int n) {
            if (er.length < L - 1 + n) {
                float[] nr = new float[L - 1 + n], ni = new float[L - 1 + n];
                System.arraycopy(er, 0, nr, 0, L - 1);
                System.arraycopy(ei, 0, ni, 0, L - 1);
                er = nr;
                ei = ni;
            }
            System.arraycopy(re, 0, er, L - 1, n);
            System.arraycopy(im, 0, ei, L - 1, n);
            for (int i = 0; i < n; i++) {
                double sr = 0, si = 0;
                int base = L - 1 + i;
                for (int k = 0; k < L; k++) {
                    sr += h[k] * er[base - k];
                    si += h[k] * ei[base - k];
                }
                re[i] = (float) sr;
                im[i] = (float) si;
            }
            System.arraycopy(er, n, er, 0, L - 1);
            System.arraycopy(ei, n, ei, 0, L - 1);
        }
    }

    /** КИХ-фильтр: вещественные данные (на месте). */
    public static final class FirReal {
        private final float[] h;
        private final int L;
        private float[] e;

        public FirReal(double[] taps) {
            L = taps.length;
            h = new float[L];
            for (int i = 0; i < L; i++) h[i] = (float) taps[i];
            e = new float[L - 1];
        }

        public void process(float[] x, int n) {
            if (e.length < L - 1 + n) {
                float[] ne = new float[L - 1 + n];
                System.arraycopy(e, 0, ne, 0, L - 1);
                e = ne;
            }
            System.arraycopy(x, 0, e, L - 1, n);
            for (int i = 0; i < n; i++) {
                double s = 0;
                int base = L - 1 + i;
                for (int k = 0; k < L; k++) s += h[k] * e[base - k];
                x[i] = (float) s;
            }
            System.arraycopy(e, n, e, 0, L - 1);
        }
    }

    // ---------------------------------------------------------------- FM-демодулятор
    public static final class FmDemod {
        private double pr = 1, pi = 0;
        private final double scale;

        public FmDemod(double devHz) {
            scale = AUDIO_FS / (2 * Math.PI * devHz);
        }

        public void process(float[] re, float[] im, int n, float[] out) {
            for (int i = 0; i < n; i++) {
                double a = re[i], b = im[i];
                out[i] = (float) (Math.atan2(b * pr - a * pi, a * pr + b * pi) * scale);
                pr = a;
                pi = b;
            }
        }
    }

    /** Две ступени 1.92 МГц -> 240 кГц -> 48 кГц. */
    public static Decimator[] frontDecimators() {
        return new Decimator[]{new Decimator(lowpass(64, 80e3, FS), 8),
                new Decimator(lowpass(60, 14e3, FS / 8.0), 5)};
    }
}

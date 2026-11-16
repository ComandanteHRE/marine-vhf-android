package com.marinevhf.dsp;

import static com.marinevhf.dsp.Dsp.*;

/** Тракт приёма речи (порт NfmVoice): IQ 1.92 МГц -> звук 48 кГц, моно, с шумовым шумоподавителем. */
public final class NfmVoice {
    public static final class Info {
        public double rssi, noise;
        public boolean open;
    }

    private static final double DEEMPH_HZ = 700.0;
    private final Mixer mixer = new Mixer(VOICE_OFFSET_HZ, BLOCK);
    private final Decimator d1, d2;
    private final Fir chan = new Fir(lowpass(101, 8e3, AUDIO_FS));
    private final FirReal noiseHp = new FirReal(highpass(63, 5.5e3, AUDIO_FS));
    private final FirReal audioBp = new FirReal(bandpass(127, 300, 3400, AUDIO_FS));
    private final FmDemod demod = new FmDemod(5000);
    private final double deA = 1 - Math.exp(-2 * Math.PI * DEEMPH_HZ / AUDIO_FS);
    private double deState = 0, agc = 8.0, env = 0, gainPrev = 0;
    private boolean isOpen = false;
    private int hang = 0;

    private final float[] mRe = new float[BLOCK], mIm = new float[BLOCK];
    private final float[] aRe = new float[BLOCK / 8], aIm = new float[BLOCK / 8];
    private final float[] bRe = new float[AUDIO_BLOCK], bIm = new float[AUDIO_BLOCK];
    private final float[] dem = new float[AUDIO_BLOCK], nz = new float[AUDIO_BLOCK];

    public NfmVoice() {
        Decimator[] d = frontDecimators();
        d1 = d[0];
        d2 = d[1];
    }

    /** audioOut — AUDIO_BLOCK отсчётов float [-0.95..0.95]. */
    public Info process(float[] re, float[] im, int sql, float[] audioOut) {
        mixer.mix(re, im, mRe, mIm);
        d1.process(mRe, mIm, BLOCK, aRe, aIm);
        d2.process(aRe, aIm, BLOCK / 8, bRe, bIm);
        chan.process(bRe, bIm, AUDIO_BLOCK);
        demod.process(bRe, bIm, AUDIO_BLOCK, dem);

        System.arraycopy(dem, 0, nz, 0, AUDIO_BLOCK);
        noiseHp.process(nz, AUDIO_BLOCK);
        double s = 0;
        for (int i = 0; i < AUDIO_BLOCK; i++) s += nz[i] * nz[i];
        double noise = Math.sqrt(s / AUDIO_BLOCK);
        double rssi = Math.max(0, Math.min(45, -20 * Math.log10(Math.max(noise, 1e-6) / NOISE_REF)));

        boolean opened;
        if (sql <= 0) {
            opened = true;
        } else {
            double thr = NOISE_REF * (1.0 - 0.085 * sql);
            opened = noise < (isOpen ? thr * 1.2 : thr);
        }
        if (opened) hang = 4;
        else if (hang > 0) {
            hang--;
            opened = true;
        }
        isOpen = opened;

        audioBp.process(dem, AUDIO_BLOCK);
        double st = deState;
        for (int i = 0; i < AUDIO_BLOCK; i++) {
            st = deA * dem[i] + (1 - deA) * st;
            dem[i] = (float) st;
        }
        deState = st;
        if (opened) {
            double e = 0;
            for (int i = 0; i < AUDIO_BLOCK; i++) e += dem[i] * dem[i];
            env = Math.max(Math.sqrt(e / AUDIO_BLOCK), 0.9 * env);
            double want = Math.max(0.5, Math.min(40.0, 0.18 / Math.max(env, 1e-3)));
            agc += 0.4 * (want - agc);
        }
        double gEnd = opened ? 1.0 : 0.0;
        for (int i = 0; i < AUDIO_BLOCK; i++) {
            double g = i < 160 ? gainPrev + (gEnd - gainPrev) * i / 159.0 : gEnd;
            audioOut[i] = (float) Math.max(-0.95, Math.min(0.95, dem[i] * agc * g));
        }
        gainPrev = gEnd;
        Info info = new Info();
        info.rssi = rssi;
        info.noise = noise;
        info.open = isOpen;
        return info;
    }
}

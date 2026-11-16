package com.marinevhf.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.marinevhf.core.RadioEngine;
import com.marinevhf.dsp.Dsp;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/** Вывод звука приёмника (48 кГц моно). Отдельный поток, чтобы запись в AudioTrack не тормозила приём. */
public final class AudioPlayer implements RadioEngine.AudioSink {
    private final AudioTrack track;
    private final BlockingQueue<short[]> q = new ArrayBlockingQueue<>(24);
    private final Thread th;
    private volatile boolean run = true;
    private volatile float vol = 0.7f;

    public AudioPlayer() {
        int min = AudioTrack.getMinBufferSize(Dsp.AUDIO_FS, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(Dsp.AUDIO_FS)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min, Dsp.AUDIO_FS / 2))
                .build();
        track.play();
        th = new Thread(this::loop, "audio-out");
        th.setDaemon(true);
        th.start();
    }

    /** 0..1, квадратичная кривая: на малых значениях регулировка тоньше. */
    public void setVolume(float v) {
        vol = 2f * v * v;
    }

    @Override
    public void push(float[] b, int n) {
        float g = vol;
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            float x = b[i] * g;
            s[i] = (short) (x > 1 ? 32767 : x < -1 ? -32767 : x * 32767);
        }
        while (!q.offer(s)) q.poll();                       // переполнение — выбрасываем старое
    }

    @Override
    public void clear() {
        q.clear();
        track.pause();
        track.flush();
        track.play();
    }

    private void loop() {
        while (run) {
            try {
                short[] s = q.take();
                track.write(s, 0, s.length);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public void release() {
        run = false;
        th.interrupt();
        try { track.stop(); } catch (IllegalStateException ignored) { }
        track.release();
    }
}

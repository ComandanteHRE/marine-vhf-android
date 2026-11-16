package com.marinevhf.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import com.marinevhf.core.RadioEngine;
import com.marinevhf.dsp.Dsp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Запись звука: AAC (M4A), Opus (Ogg, Android 10+) или WAV. Файлы — в «Музыка/MarineVHF».
 * Кодирование в отдельном потоке; если выбранный кодек не запустился, пишем WAV.
 */
public final class AudioRecorder implements RadioEngine.PcmSink {
    public static final String AAC = "aac", OPUS = "opus", WAV = "wav";
    private static final short[] END = new short[0];

    private final Context ctx;
    private final BlockingQueue<short[]> q = new ArrayBlockingQueue<>(400);
    private volatile boolean active;
    private Thread th;
    private String name = "";
    private volatile String error;
    private volatile String notice;

    public AudioRecorder(Context c) {
        ctx = c.getApplicationContext();
    }

    @Override public boolean isActive() { return active; }

    public String fileName() { return name; }

    public String lastError() { return error; }

    /** Пояснение, если формат заменён (например, Opus недоступен на устройстве). */
    public String notice() { return notice; }

    private static boolean hasEncoder(String mime) {
        try {
            MediaFormat f = MediaFormat.createAudioFormat(mime, Dsp.AUDIO_FS, 1);
            return new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f) != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void write(float[] b, int n) {
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            float x = b[i];
            s[i] = (short) (x > 1 ? 32767 : x < -1 ? -32767 : x * 32767);
        }
        if (!q.offer(s)) { /* кодировщик отстал — теряем блок */ }
    }

    public synchronized String start(String format, String stamp) throws IOException {
        if (active) return name;
        q.clear();
        error = null;
        notice = null;
        if (format.equals(OPUS) && (Build.VERSION.SDK_INT < 29 || !hasEncoder("audio/opus"))) {
            format = AAC;
            notice = "Opus недоступен на этом устройстве — запись в AAC";
        }
        if (format.equals(AAC) && !hasEncoder(MediaFormat.MIMETYPE_AUDIO_AAC)) {
            format = WAV;
            notice = "AAC-кодек недоступен — запись в WAV";
        }
        Out out = openOut(format, stamp);
        name = out.name;
        active = true;
        th = new Thread(() -> run(out), "recorder");
        th.start();
        return name;
    }

    public synchronized void stop() {
        if (!active) return;
        active = false;
        try { q.put(END); } catch (InterruptedException ignored) { }
        try { th.join(4000); } catch (InterruptedException ignored) { }
    }

    // ---------------------------------------------------------------- файл
    private static final class Out {
        String name, mime, ext;
        ParcelFileDescriptor pfd;
        Uri uri;
        File file;
    }

    private Out openOut(String format, String stamp) throws IOException {
        String ext = format.equals(OPUS) ? "ogg" : format.equals(WAV) ? "wav" : "m4a";
        String mime = ext.equals("ogg") ? "audio/ogg" : ext.equals("wav") ? "audio/wav" : "audio/mp4";
        Out o = new Out();
        o.name = "marine_" + stamp + "." + ext;
        o.mime = mime;
        o.ext = ext;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, o.name);
            v.put(MediaStore.Audio.Media.MIME_TYPE, mime);
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/MarineVHF");
            v.put(MediaStore.Audio.Media.IS_PENDING, 1);
            o.uri = r.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
            if (o.uri == null) throw new IOException("MediaStore отказал в создании файла");
            o.pfd = r.openFileDescriptor(o.uri, "rw");
        } else {
            File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "MarineVHF");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("нет папки " + dir);
            o.file = new File(dir, o.name);
            o.pfd = ParcelFileDescriptor.open(o.file, ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        return o;
    }

    private void finish(Out o) {
        try { if (o.pfd != null) o.pfd.close(); } catch (IOException ignored) { }
        if (o.uri != null) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.IS_PENDING, 0);
            ctx.getContentResolver().update(o.uri, v, null, null);
        }
    }

    // ---------------------------------------------------------------- кодирование
    private void run(Out o) {
        try {
            if (o.ext.equals("wav")) runWav(o);
            else runCodec(o);
        } catch (Exception e) {
            error = e.toString();
            active = false;
        } finally {
            finish(o);
        }
    }

    private void runWav(Out o) throws Exception {
        FileOutputStream fo = new FileOutputStream(o.pfd.getFileDescriptor());
        WavWriter w = new WavWriter(fo.getChannel(), Dsp.AUDIO_FS);
        for (short[] s; (s = q.take()) != END; ) w.write(s, s.length);
        w.close(Dsp.AUDIO_FS);
    }

    private void runCodec(Out o) throws Exception {
        boolean opus = o.ext.equals("ogg");
        String mime = opus ? "audio/opus" : MediaFormat.MIMETYPE_AUDIO_AAC;
        MediaFormat f = MediaFormat.createAudioFormat(mime, Dsp.AUDIO_FS, 1);
        f.setInteger(MediaFormat.KEY_BIT_RATE, opus ? 24000 : 32000);
        if (!opus) f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec enc = MediaCodec.createEncoderByType(mime);
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        MediaMuxer mux = new MediaMuxer(o.pfd.getFileDescriptor(),
                opus ? MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int track = -1;
        boolean muxStarted = false, eos = false;
        long samples = 0;
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        short[] cur = null;
        int pos = 0;
        boolean ended = false, eosSent = false;
        try {
            while (!eos) {
                // подача входа
                int ii = eosSent ? -1 : enc.dequeueInputBuffer(10_000);
                if (ii >= 0) {
                    ByteBuffer ib = enc.getInputBuffer(ii);
                    ib.clear();
                    ib.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    int room = ib.capacity() / 2, put = 0;
                    while (put < room && !ended) {
                        if (cur == null) {
                            cur = put == 0 ? q.take() : q.poll();
                            if (cur == null) break;
                            if (cur == END) { ended = true; cur = null; break; }
                            pos = 0;
                        }
                        int k = Math.min(room - put, cur.length - pos);
                        for (int i = 0; i < k; i++) ib.putShort(cur[pos + i]);
                        pos += k;
                        put += k;
                        if (pos >= cur.length) cur = null;
                    }
                    eosSent = ended && cur == null;
                    long pts = samples * 1_000_000L / Dsp.AUDIO_FS;
                    samples += put;
                    enc.queueInputBuffer(ii, 0, put * 2, pts, eosSent ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                }
                // забор выхода
                for (;;) {
                    int oi = enc.dequeueOutputBuffer(bi, 0);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux.addTrack(enc.getOutputFormat());
                        mux.start();
                        muxStarted = true;
                    } else if (oi >= 0) {
                        ByteBuffer ob = enc.getOutputBuffer(oi);
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
                        if (bi.size > 0 && muxStarted) {
                            ob.position(bi.offset);
                            ob.limit(bi.offset + bi.size);
                            mux.writeSampleData(track, ob, bi);
                        }
                        enc.releaseOutputBuffer(oi, false);
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { eos = true; break; }
                    } else break;
                }
            }
        } finally {
            try { enc.stop(); } catch (Exception ignored) { }
            enc.release();
            try { if (muxStarted) mux.stop(); } catch (Exception ignored) { }
            mux.release();
        }
    }
}

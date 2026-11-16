import com.marinevhf.dsp.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

public class DspTest {
    static byte[] data;
    static void load(String p, int blk, float[] re, float[] im) {
        int o = blk * 2 * Dsp.BLOCK;
        for (int i = 0; i < Dsp.BLOCK; i++) {
            re[i] = ((data[o + 2 * i] & 0xFF) - 127.5f) / 127.5f;
            im[i] = ((data[o + 2 * i + 1] & 0xFF) - 127.5f) / 127.5f;
        }
    }
    public static void main(String[] a) throws Exception {
        float[] re = new float[Dsp.BLOCK], im = new float[Dsp.BLOCK];
        // --- АИС
        data = Files.readAllBytes(Paths.get("/tmp/jtest/ais.cu8"));
        int nb = data.length / (2 * Dsp.BLOCK);
        AisDecoder dec = new AisDecoder();
        PrintWriter w = new PrintWriter("/tmp/jtest/ais.java.out");
        long t0 = System.nanoTime();
        for (int b = 0; b < nb; b++) {
            load("", b, re, im);
            for (AisDecoder.Frame f : dec.process(re, im))
                for (String s : Ais.sentences(f.bits, f.chan)) w.println((b + 1) + " " + f.chan + " " + s);
        }
        w.close();
        System.out.printf("AIS: %d блоков за %.2f с (эфир %.1f с)%n", nb, (System.nanoTime() - t0) / 1e9, nb * Dsp.BLOCK / 1.92e6);
        // --- голос
        data = Files.readAllBytes(Paths.get("/tmp/jtest/voice.cu8"));
        nb = data.length / (2 * Dsp.BLOCK);
        NfmVoice v = new NfmVoice();
        float[] au = new float[Dsp.AUDIO_BLOCK];
        w = new PrintWriter("/tmp/jtest/voice.java.out");
        DataOutputStream ao = new DataOutputStream(new BufferedOutputStream(new FileOutputStream("/tmp/jtest/voice_audio.java")));
        t0 = System.nanoTime();
        for (int b = 0; b < nb; b++) {
            load("", b, re, im);
            NfmVoice.Info i = v.process(re, im, 3, au);
            w.printf(Locale.ROOT, "%d %.4f %d %.2f%n", b, i.noise, i.open ? 1 : 0, i.rssi);
            for (float x : au) ao.writeInt(Integer.reverseBytes(Float.floatToIntBits(x)));
        }
        w.close(); ao.close();
        System.out.printf("Голос: %d блоков за %.2f с%n", nb, (System.nanoTime() - t0) / 1e9);
    }
}

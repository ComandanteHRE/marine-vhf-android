package com.marinevhf.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/** WAV 16 бит моно. Размеры в заголовке дописываются при закрытии. Без зависимостей от Android. */
public final class WavWriter {
    private final FileChannel ch;
    private long data = 0;

    public WavWriter(FileChannel ch, int rate) throws IOException {
        this.ch = ch;
        ch.write(header(rate, 0));
    }

    private static ByteBuffer header(int rate, long dataLen) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt((int) (36 + dataLen)).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt((int) dataLen);
        b.flip();
        return b;
    }

    public void write(short[] pcm, int n) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) b.putShort(pcm[i]);
        b.flip();
        while (b.hasRemaining()) ch.write(b);
        data += n * 2L;
    }

    public void close(int rate) throws IOException {
        ch.position(0);
        ch.write(header(rate, data));
        ch.close();
    }
}

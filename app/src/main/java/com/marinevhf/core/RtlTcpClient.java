package com.marinevhf.core;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Клиент протокола rtl_tcp (его поднимает приложение «Rtl-sdr driver», marto.rtl_tcp_andro).
 * Заголовок: «RTL0» + тип тюнера (u32) + число значений усиления (u32); затем поток u8 IQ.
 * Команда: 1 байт кода + u32 (big-endian).
 */
public final class RtlTcpClient implements IqSource {
    private static final int CMD_FREQ = 0x01, CMD_RATE = 0x02, CMD_GAIN_MODE = 0x03, CMD_GAIN = 0x04,
            CMD_PPM = 0x05, CMD_AGC = 0x08;
    private static final int[] R820T_GAINS = {0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254,
            280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496};
    private static final String[] TUNERS = {"?", "E4000", "FC0012", "FC0013", "FC2580", "R820T", "R828D"};

    private final Socket sock;
    private final DataInputStream in;
    private final OutputStream out;
    public final int tunerType;
    private final String info;
    private byte[] raw = new byte[0];

    private RtlTcpClient(Socket s) throws IOException {
        sock = s;
        in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
        out = s.getOutputStream();
        byte[] hdr = new byte[12];
        in.readFully(hdr);
        if (hdr[0] != 'R' || hdr[1] != 'T' || hdr[2] != 'L' || hdr[3] != '0')
            throw new IOException("это не rtl_tcp (нет заголовка RTL0)");
        tunerType = ((hdr[4] & 0xFF) << 24) | ((hdr[5] & 0xFF) << 16) | ((hdr[6] & 0xFF) << 8) | (hdr[7] & 0xFF);
        info = "rtl_tcp " + s.getInetAddress().getHostAddress() + ":" + s.getPort() + ", тюнер "
                + (tunerType >= 0 && tunerType < TUNERS.length ? TUNERS[tunerType] : "тип " + tunerType);
    }

    /** Подключение с повторами: драйвер поднимает сервер не мгновенно. */
    public static RtlTcpClient connect(String host, int port, int attempts, int pauseMs) throws IOException {
        IOException last = null;
        for (int i = 0; i < attempts; i++) {
            Socket s = new Socket();
            try {
                s.setReceiveBufferSize(1 << 20);
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(host, port), 2000);
                s.setSoTimeout(6000);
                return new RtlTcpClient(s);
            } catch (IOException e) {
                last = e;
                try { s.close(); } catch (IOException ignored) { }
                try { Thread.sleep(pauseMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw new IOException("не удалось подключиться к драйверу " + host + ":" + port
                + (last != null ? " (" + last.getMessage() + ")" : ""));
    }

    private synchronized void cmd(int code, long value) throws IOException {
        byte[] b = {(byte) code, (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
        out.write(b);
        out.flush();
    }

    @Override public void setSampleRate(int hz) throws IOException { cmd(CMD_RATE, hz); }

    @Override public void setCenterFreq(long hz) throws IOException { cmd(CMD_FREQ, hz); }

    @Override public void setPpm(int ppm) throws IOException { cmd(CMD_PPM, ppm & 0xFFFFFFFFL); }

    @Override public void setGainAuto() throws IOException {
        cmd(CMD_GAIN_MODE, 0);
        cmd(CMD_AGC, 1);
    }

    @Override public void setGainDb(double db) throws IOException {
        int tenth = (int) Math.round(db * 10), best = R820T_GAINS[0];
        for (int g : R820T_GAINS) if (Math.abs(g - tenth) < Math.abs(best - tenth)) best = g;
        cmd(CMD_GAIN_MODE, 1);
        cmd(CMD_AGC, 0);
        cmd(CMD_GAIN, best);
    }

    @Override public void flush() throws IOException {
        int n;
        while ((n = in.available()) > 0) in.skipBytes(n);
    }

    @Override public void read(float[] re, float[] im) throws IOException {
        int n = re.length;
        if (raw.length != 2 * n) raw = new byte[2 * n];
        in.readFully(raw);
        for (int i = 0; i < n; i++) {
            re[i] = ((raw[2 * i] & 0xFF) - 127.5f) / 127.5f;
            im[i] = ((raw[2 * i + 1] & 0xFF) - 127.5f) / 127.5f;
        }
    }

    @Override public String info() { return info; }

    @Override public void close() throws IOException { sock.close(); }
}

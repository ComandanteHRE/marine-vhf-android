package com.marinevhf.dsp;

import java.util.Locale;

/** АИС: CRC, NMEA (!AIVDM), разбор сообщений. Биты — byte[] со значениями 0/1. */
public final class Ais {
    private Ais() {}

    private static final int[] CRC_TAB = new int[256];

    static {
        for (int b = 0; b < 256; b++) {
            int c = b;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? (c >>> 1) ^ 0x8408 : c >>> 1;
            CRC_TAB[b] = c;
        }
    }

    public static final int CRC_GOOD = 0xF0B8;

    public static int crc16(byte[] data, int n) {
        int crc = 0xFFFF;
        for (int i = 0; i < n; i++) crc = (crc >>> 8) ^ CRC_TAB[(crc ^ (data[i] & 0xFF)) & 0xFF];
        return crc;
    }

    // ---------------------------------------------------------------- биты
    public static long uint(byte[] b, int from, int to) {
        long v = 0;
        for (int i = from; i < to && i < b.length; i++) v = (v << 1) | b[i];
        return v;
    }

    public static long sint(byte[] b, int from, int to) {
        long v = uint(b, from, to);
        return v >= (1L << (to - from - 1)) ? v - (1L << (to - from)) : v;
    }

    public static String text6(byte[] b, int from, int to) {
        StringBuilder s = new StringBuilder();
        for (int i = from; i < to && i + 6 <= b.length; i += 6) {
            int v = (int) uint(b, i, i + 6);
            s.append((char) (v < 32 ? v + 64 : v));
        }
        return s.toString().replace('@', ' ').trim();
    }

    // ---------------------------------------------------------------- NMEA
    public static String armor(byte[] bits, int[] fillOut) {
        int pad = (6 - bits.length % 6) % 6;
        fillOut[0] = pad;
        int n = (bits.length + pad) / 6;
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int v = 0;
            for (int k = 0; k < 6; k++) {
                int idx = i * 6 + k;
                v = (v << 1) | (idx < bits.length ? bits[idx] : 0);
            }
            s.append((char) (v < 40 ? v + 48 : v + 56));
        }
        return s.toString();
    }

    public static byte[] dearmor(String text) {
        byte[] bits = new byte[text.length() * 6];
        for (int i = 0; i < text.length(); i++) {
            int v = text.charAt(i) - 48;
            if (v > 40) v -= 8;
            for (int k = 0; k < 6; k++) bits[i * 6 + k] = (byte) ((v >> (5 - k)) & 1);
        }
        return bits;
    }

    public static String checksum(String body) {
        int cs = 0;
        for (int i = 0; i < body.length(); i++) cs ^= body.charAt(i);
        return String.format(Locale.ROOT, "%02X", cs);
    }

    private static int seq = 0;

    /** Биты сообщения -> строки «!AIVDM,...*CS» без CRLF (фрагменты по 60 символов). */
    public static synchronized String[] sentences(byte[] bits, char chan) {
        int[] fill = new int[1];
        String pay = armor(bits, fill);
        int parts = (pay.length() + 59) / 60;
        String sid = "";
        if (parts > 1) {
            seq = (seq + 1) % 10;
            sid = String.valueOf(seq);
        }
        String[] out = new String[parts];
        for (int p = 0; p < parts; p++) {
            String chunk = pay.substring(p * 60, Math.min(pay.length(), p * 60 + 60));
            String body = "AIVDM," + parts + "," + (p + 1) + "," + sid + "," + chan + "," + chunk + ","
                    + (p == parts - 1 ? fill[0] : 0);
            out[p] = "!" + body + "*" + checksum(body);
        }
        return out;
    }

    // ---------------------------------------------------------------- разбор
    public static final class Msg {
        public int type;
        public long mmsi;
        public String status, name = "", callsign = "", dest = "";
        public Double lat, lon, sog, cog;
        public int shiptype;
    }

    private static final String[] NAV = {"ход под машиной", "на якоре", "не управляется", "ограничен в маневре",
            "ограничен осадкой", "ошвартован", "на мели", "рыбалка", "ход под парусом", "-", "-", "-", "-", "-",
            "АИС-SART", "не задан"};

    private static void pos(Msg m, byte[] b, int aLon, int aLat) {
        double lon = sint(b, aLon, aLon + 28) / 600000.0, lat = sint(b, aLat, aLat + 27) / 600000.0;
        if (Math.abs(lon) <= 180 && Math.abs(lat) <= 90) {
            m.lon = lon;
            m.lat = lat;
        }
    }

    public static Msg parse(byte[] b) {
        Msg m = new Msg();
        m.type = (int) uint(b, 0, 6);
        m.mmsi = uint(b, 8, 38);
        try {
            switch (m.type) {
                case 1: case 2: case 3:
                    m.status = NAV[(int) uint(b, 38, 42)];
                    m.sog = uint(b, 50, 60) / 10.0;
                    pos(m, b, 61, 89);
                    m.cog = uint(b, 116, 128) / 10.0;
                    break;
                case 5:
                    m.callsign = text6(b, 70, 112);
                    m.name = text6(b, 112, 232);
                    m.shiptype = (int) uint(b, 232, 240);
                    m.dest = text6(b, 302, 422);
                    break;
                case 18: case 19:
                    m.sog = uint(b, 46, 56) / 10.0;
                    pos(m, b, 57, 85);
                    m.cog = uint(b, 112, 124) / 10.0;
                    if (m.type == 19) m.name = text6(b, 143, 263);
                    break;
                case 21:
                    m.name = text6(b, 43, 163);
                    pos(m, b, 164, 192);
                    break;
                case 24:
                    if (uint(b, 38, 40) == 0) m.name = text6(b, 40, 160);
                    else {
                        m.shiptype = (int) uint(b, 40, 48);
                        m.callsign = text6(b, 90, 132);
                    }
                    break;
                default:
            }
        } catch (RuntimeException ignored) {
        }
        return m;
    }

    public static String describe(Msg m) {
        StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "тип %2d  MMSI %09d", m.type, m.mmsi));
        if (m.lat != null)
            s.append(String.format(Locale.ROOT, "  %.4f°%s %.4f°%s", Math.abs(m.lat), m.lat >= 0 ? "N" : "S",
                    Math.abs(m.lon), m.lon >= 0 ? "E" : "W"));
        if (m.sog != null) s.append(String.format(Locale.ROOT, "  %.1f уз  %.0f°", m.sog, m.cog == null ? 0 : m.cog));
        if (!m.name.isEmpty()) s.append("  «").append(m.name).append('»');
        if (!m.dest.isEmpty()) s.append("  → ").append(m.dest);
        if (m.status != null) s.append("  [").append(m.status).append(']');
        return s.toString();
    }
}

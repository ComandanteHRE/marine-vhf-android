package com.marinevhf.dsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Международная сетка морских УКВ-каналов (ITU-R M.1084): 1-28 и 60-88, шаг 25 кГц. */
public final class Channels {
    private Channels() {}

    public static final int[] DUPLEX_LIST = {1, 2, 3, 4, 5, 7, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28,
            60, 61, 62, 63, 64, 65, 66, 78, 79, 80, 81, 82, 83, 84, 85, 86};
    public static final int[] ALL;
    public static final long AIS1_HZ = 161_975_000L, AIS2_HZ = 162_025_000L;
    public static final long[] SCAN_HOPS = {156_712_500L, 161_312_500L};
    public static final long[] SCAN_FIRST = {156_025_000L, 160_625_000L};
    public static final int GRID_N = 57;

    static {
        ALL = new int[57];
        int n = 0;
        for (int c = 1; c <= 28; c++) ALL[n++] = c;
        for (int c = 60; c <= 88; c++) ALL[n++] = c;
    }

    public static boolean isDuplex(int ch) {
        return Arrays.binarySearch(DUPLEX_LIST, ch) >= 0;
    }

    public static boolean isValid(int ch) {
        return (ch >= 1 && ch <= 28) || (ch >= 60 && ch <= 88);
    }

    public static long shipHz(int ch) {
        if (ch >= 1 && ch <= 28) return 156_000_000L + 50_000L * ch;
        if (ch >= 60 && ch <= 88) return 156_025_000L + 50_000L * (ch - 60);
        throw new IllegalArgumentException("нет такого канала: " + ch);
    }

    public static long coastHz(int ch) {
        return shipHz(ch) + (isDuplex(ch) ? 4_600_000L : 0);
    }

    public static long rxHz(int ch, boolean listenShip) {
        return listenShip ? shipHz(ch) : coastHz(ch);
    }

    public static int next(int ch, int step) {
        int i = Arrays.binarySearch(ALL, ch);
        if (i < 0) i = 0;
        return ALL[((i + step) % ALL.length + ALL.length) % ALL.length];
    }

    /** {канал, 1 если судовая сторона} либо null. */
    public static int[] freqToChannel(double hz, double tol) {
        for (int ch : ALL) {
            if (Math.abs(hz - shipHz(ch)) <= tol) return new int[]{ch, 1};
            if (isDuplex(ch) && Math.abs(hz - coastHz(ch)) <= tol) return new int[]{ch, 0};
        }
        return null;
    }

    public static String note(int ch) {
        switch (ch) {
            case 16: return "Вызов/бедствие";
            case 70: return "DSC (цифровой вызов)";
            case 6: return "Связь с воздушными судами (SAR)";
            case 13: return "Навигационная безопасность мост-мост";
            case 9: return "Вызывной (яхты/порты)";
            case 67: return "Малые суда / SAR";
            case 72: return "Межсудовой";
            case 75: case 76: return "Защитная полоса";
            default: return "";
        }
    }

    /** Позиция сетки обзора: канал (0 — АИС), сторона, подпись. */
    public static final class Slot {
        public final long hz;
        public final int ch;
        public final boolean ship;
        public final String label;

        Slot(long hz, int ch, boolean ship, String label) {
            this.hz = hz;
            this.ch = ch;
            this.ship = ship;
            this.label = label;
        }
    }

    private static final Map<Long, Slot> SLOTS = new HashMap<>();

    static {
        for (int ch : ALL) {
            SLOTS.put(shipHz(ch), new Slot(shipHz(ch), ch, true, String.valueOf(ch)));
            if (isDuplex(ch)) SLOTS.put(coastHz(ch), new Slot(coastHz(ch), ch, false, String.valueOf(ch)));
        }
        SLOTS.put(AIS1_HZ, new Slot(AIS1_HZ, 0, false, "AIS1"));
        SLOTS.put(AIS2_HZ, new Slot(AIS2_HZ, 0, false, "AIS2"));
    }

    public static Slot slot(long hz) {
        return SLOTS.get(hz);
    }

    public static long[] grid(int hop) {
        long[] g = new long[GRID_N];
        for (int i = 0; i < GRID_N; i++) g[i] = SCAN_FIRST[hop] + 25_000L * i;
        return g;
    }

    public static List<Slot> allSlots() {
        return new ArrayList<>(SLOTS.values());
    }
}

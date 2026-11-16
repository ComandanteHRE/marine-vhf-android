package com.marinevhf.core;

import com.marinevhf.dsp.Channels;

/** Логика панели (порт panel_logic.py): ввод канала/частоты, ▲▼, канал 16, сторона дуплекса. */
public final class PanelLogic {
    public static final long MIN_HZ = 24_000_000L, MAX_HZ = 1_766_000_000L;

    public boolean freqInput = false;        // false — вводим канал, true — частоту (то же показывает LCD)
    public Integer channel;                  // null — вне сетки морских каналов
    public boolean listenShip;
    public long freqHz;
    public String buf = "", message = "";

    public PanelLogic(int channel, boolean listenShip) {
        this.channel = Channels.isValid(channel) ? channel : 16;
        this.listenShip = listenShip;
        this.freqHz = Channels.rxHz(this.channel, listenShip);
    }

    /** '156.8' -> МГц; только цифры -> кГц ('156800'). */
    public static long parseFreq(String t) {
        if (t.isEmpty()) throw new NumberFormatException();
        long hz = t.contains(".") ? Math.round(Double.parseDouble(t) * 1e6) : Long.parseLong(t) * 1000L;
        if (hz < MIN_HZ || hz > MAX_HZ) throw new NumberFormatException();
        return hz;
    }

    public String note() {
        return channel == null ? "" : Channels.note(channel);
    }

    public boolean isDuplex() {
        return channel != null && Channels.isDuplex(channel);
    }

    private boolean setChannel(int ch, Boolean ship) {
        channel = ch;
        if (ship != null) listenShip = ship;
        freqHz = Channels.rxHz(ch, listenShip);
        buf = message = "";
        return true;
    }

    public void toggleInput() {
        freqInput = !freqInput;
        buf = message = "";
    }

    public void key(char k) {
        int limit = freqInput ? 9 : 2;
        if (k == '.' && (!freqInput || buf.contains("."))) return;
        if (buf.length() < limit) buf += k;
        message = "";
    }

    public void clear() {
        if (!buf.isEmpty()) buf = buf.substring(0, buf.length() - 1);
        message = "";
    }

    /** true — нужно перестроить приёмник. */
    public boolean enter() {
        if (buf.isEmpty()) return false;
        try {
            if (!freqInput) {
                int ch = Integer.parseInt(buf);
                if (!Channels.isValid(ch)) throw new NumberFormatException();
                return setChannel(ch, null);
            }
            return gotoFreq(parseFreq(buf));
        } catch (NumberFormatException e) {
            buf = "";
            message = "ОШИБКА";
            return false;
        }
    }

    public boolean gotoFreq(long hz) {
        int[] f = Channels.freqToChannel(hz, 7000);
        if (f != null) return setChannel(f[0], f[1] == 1);
        channel = null;
        freqHz = hz;
        buf = message = "";
        return true;
    }

    public boolean gotoChannel(int ch, Boolean ship) {
        return setChannel(ch, ship);
    }

    public boolean ch16() {
        return setChannel(16, null);
    }

    public boolean step(int d) {
        Integer ch = channel;
        if (ch == null) {                                     // вне сетки: ближайший канал, затем шаг
            long best = Long.MAX_VALUE;
            ch = 16;
            for (int c : Channels.ALL) {
                long diff = Math.abs(Channels.rxHz(c, listenShip) - freqHz);
                if (diff < best) {
                    best = diff;
                    ch = c;
                }
            }
        }
        return setChannel(Channels.next(ch, d), null);
    }

    /** Дуплекс: слушать береговую (как судовая рация) или судовую сторону. */
    public boolean toggleSide() {
        listenShip = !listenShip;
        if (channel != null) {
            setChannel(channel, null);
            return isDuplex();
        }
        return false;
    }
}

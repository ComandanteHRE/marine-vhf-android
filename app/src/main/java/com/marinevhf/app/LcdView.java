package com.marinevhf.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.Objects;

/** Янтарный ЖК-дисплей панели: канал, частота, флаги, строка ввода/сообщения, S-метр. */
public class LcdView extends View {
    private static final int FG = 0xFF1A1100, DIM = 0x331A1100;
    private static final float RATIO = 0.30f;                  // высота/ширина, если высота не задана

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float dp;
    private Shader face;
    private int faceH;
    private int last;

    public String channel = "--", freq = "", note = "", input = "", message = "", mode = "";
    public boolean freqInput, ship, duplex, open, rec, ais, connected;
    public int bars;                                                 // 0..8

    public LcdView(Context c, AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    /** Перерисовывает только при изменении показаний. */
    public void commit() {
        int h = Objects.hash(channel, freq, note, input, message, mode, freqInput, ship, duplex, open, rec, ais,
                connected, bars);
        if (h == last) return;
        last = h;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        int height = MeasureSpec.getMode(h) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(h) : (int) (width * RATIO);
        setMeasuredDimension(width, height);
    }

    private void flag(Canvas c, String t, float x, float y, float size, boolean on) {
        p.setTextSize(size);
        p.setColor(on ? FG : DIM);
        c.drawText(t, x, y, p);
    }

    /** Подрезает строку по ширине. */
    private String fit(String s, float maxW) {
        int n = p.breakText(s, true, maxW, null);
        return n >= s.length() ? s : s.substring(0, Math.max(0, n - 1)) + "…";
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float fx = w / 100f, fy = h / 34f, u = Math.min(fx, fy);

        // корпус и стекло
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0D1014);
        r.set(0, 0, w, h);
        c.drawRoundRect(r, 12 * dp, 12 * dp, p);
        if (face == null || faceH != (int) h) {
            face = new LinearGradient(0, 0, 0, h, 0xFFFFB81F, 0xFFF59E00, Shader.TileMode.CLAMP);
            faceH = (int) h;
        }
        p.setShader(face);
        r.set(3 * dp, 3 * dp, w - 3 * dp, h - 3 * dp);
        c.drawRoundRect(r, 10 * dp, 10 * dp, p);
        p.setShader(null);

        // флаги
        p.setTextAlign(Paint.Align.LEFT);
        float fs = Math.max(11 * dp, 3.4f * u), y = 6.2f * fy;
        float x = 4 * fx;
        String[] names = {freqInput ? "FREQ" : "CH", connected ? "RX" : "OFF", "SQL", "SHIP", "DUP", "AIS", "● REC"};
        boolean[] on = {true, connected, open, ship, duplex, ais, rec};
        for (int i = 0; i < names.length; i++) {
            flag(c, names[i], x, y, fs, on[i]);
            x += p.measureText(names[i]) + 3.2f * fx;
        }

        // канал / частота
        p.setColor(FG);
        if (freqInput) {
            p.setTextAlign(Paint.Align.RIGHT);
            p.setTextSize(12.5f * u);
            c.drawText(freq, 96 * fx, 21 * fy, p);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(5.5f * u);
            if (!channel.equals("--")) c.drawText("CH " + channel, 4 * fx, 21 * fy, p);
        } else {
            p.setTextAlign(Paint.Align.LEFT);
            p.setTextSize(17 * u);
            c.drawText(channel, 4 * fx, 22 * fy, p);
            p.setTextAlign(Paint.Align.RIGHT);
            p.setTextSize(7.5f * u);
            c.drawText(freq, 96 * fx, 21 * fy, p);
        }

        // строка: ввод / сообщение / режим / примечание
        String line = !message.isEmpty() ? message
                : !input.isEmpty() ? (freqInput ? "FREQ> " : "CH> ") + input
                : !mode.isEmpty() ? mode : note;
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(Math.max(12 * dp, 3.8f * u));
        p.setColor(FG);
        c.drawText(fit(line, 66 * fx), 4 * fx, 30.5f * fy, p);

        // S-метр
        float bx = 72 * fx, bwid = 2.6f * fx, gap = 0.7f * fx, base = 31 * fy;
        for (int i = 0; i < 8; i++) {
            p.setColor(i < bars ? FG : DIM);
            float bh = (1.2f + i * 0.45f) * fy;
            c.drawRect(bx + i * (bwid + gap), base - bh, bx + i * (bwid + gap) + bwid, base, p);
        }
    }
}

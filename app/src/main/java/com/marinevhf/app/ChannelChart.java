package com.marinevhf.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.marinevhf.core.RadioEngine;
import com.marinevhf.dsp.Channels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * «Обзор каналов»: столбики превышения над шумом (дБ). Синий — судовая сторона, бирюзовый — береговая,
 * янтарный — активный (выше порога). Рамка — текущий канал. Нажатие на столбик — слушать этот канал.
 */
public class ChannelChart extends View {
    public interface OnPick { void pick(RadioEngine.ScanResult r); }

    private static final double TOP_DB = 40;
    private static final int SHIP = 0xFF3F83D8, COAST = 0xFF28B7B0, ACTIVE = 0xFFFFB81F;
    private static List<RadioEngine.ScanResult> skeleton;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final DashPathEffect dash;
    private final float dp;
    private List<RadioEngine.ScanResult> data = new ArrayList<>();
    private float progress;
    private double threshold = 8;
    private int selCh = -1;
    private boolean selShip;
    private OnPick onPick;
    private int lastSig;

    public ChannelChart(Context c, AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;
        dash = new DashPathEffect(new float[]{8 * dp, 5 * dp}, 0);
        p.setTypeface(Typeface.DEFAULT_BOLD);
    }

    public void setOnPick(OnPick o) { onPick = o; }

    /** Пустая сетка каналов — рисуется до первого обзора. */
    private static synchronized List<RadioEngine.ScanResult> skeleton() {
        if (skeleton == null) {
            skeleton = new ArrayList<>();
            for (int hop = 0; hop < Channels.SCAN_HOPS.length; hop++)
                for (long hz : Channels.grid(hop)) {
                    Channels.Slot s = Channels.slot(hz);
                    if (s == null) continue;
                    RadioEngine.ScanResult x = new RadioEngine.ScanResult();
                    x.ch = s.ch; x.ship = s.ship; x.hz = s.hz; x.label = s.label;
                    skeleton.add(x);
                }
            skeleton = Collections.unmodifiableList(skeleton);
        }
        return skeleton;
    }

    /** Перерисовывает только если что-то действительно изменилось. */
    public void set(List<RadioEngine.ScanResult> d, float prog, double thr, int ch, boolean ship) {
        List<RadioEngine.ScanResult> use = (d == null || d.isEmpty()) ? skeleton() : d;
        int sig = System.identityHashCode(use);
        sig = sig * 31 + Math.round(prog * 100);
        sig = sig * 31 + (int) Math.round(thr * 10);
        sig = sig * 31 + ch * 2 + (ship ? 1 : 0);
        for (RadioEngine.ScanResult x : use) sig = sig * 31 + (int) Math.round(x.db * 2);
        if (sig == lastSig && use == data) return;
        lastSig = sig;
        data = use;
        progress = prog;
        threshold = thr;
        selCh = ch;
        selShip = ship;
        invalidate();
    }

    private float left() { return 30 * dp; }
    private float bottom() { return getHeight() - 22 * dp; }
    private float top() { return 6 * dp; }
    private float yOf(double db) {
        double v = Math.max(0, Math.min(TOP_DB, db));
        return (float) (bottom() - (bottom() - top()) * v / TOP_DB);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), l = left(), rgt = w - 4 * dp, t = top(), b = bottom();
        int n = Math.max(1, data.size());
        float bw = (rgt - l) / n;

        // сетка и подписи оси Y
        p.setTextAlign(Paint.Align.RIGHT);
        p.setTextSize(13 * dp);
        p.setStrokeWidth(1f * dp);
        int[] marks = {1, 10, 20, 30, 40};
        for (int m : marks) {
            float y = yOf(m == 1 ? 0 : m);
            p.setColor(m == 1 ? 0xFF5A6572 : 0xFF2B323C);
            c.drawLine(l, y, rgt, y, p);
            p.setColor(0xFFC9D3DE);
            c.drawText(String.valueOf(m), l - 5 * dp, y + 4.5f * dp, p);
        }

        // столбики
        p.setStyle(Paint.Style.FILL);
        int selIdx = findSel();
        for (int i = 0; i < data.size(); i++) {
            RadioEngine.ScanResult x = data.get(i);
            if (x.db <= 0) continue;
            float h = (b - yOf(x.db));
            p.setColor(x.active ? ACTIVE : x.ship ? SHIP : COAST);
            float x0 = l + i * bw + bw * 0.15f;
            c.drawRect(x0, b - h, x0 + bw * 0.7f, b, p);
        }

        // порог
        p.setStyle(Paint.Style.STROKE);
        p.setPathEffect(dash);
        p.setColor(0xFFF2C21A);
        p.setStrokeWidth(1.6f * dp);
        float ty = yOf(threshold);
        c.drawLine(l, ty, rgt, ty, p);
        p.setPathEffect(null);

        // подписи каналов
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.CENTER);
        float ts = Math.min(12 * dp, bw * 1.25f);
        p.setTextSize(ts);
        float labelW = p.measureText("88") + 1.5f * dp;
        int step = Math.max(1, (int) Math.ceil(labelW / bw));
        p.setColor(0xFFA9B8C9);
        for (int i = 0; i < data.size(); i += step) {
            RadioEngine.ScanResult x = data.get(i);
            if (x.ch > 0) c.drawText(x.label, l + (i + 0.5f) * bw, getHeight() - 5 * dp, p);
        }

        // рамка текущего канала
        if (selIdx >= 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.2f * dp);
            p.setColor(0xFFFFB81F);
            float x0 = l + selIdx * bw;
            float ww = Math.max(bw, 6 * dp);
            r.set(x0 + bw / 2f - ww / 2f, t, x0 + bw / 2f + ww / 2f, b);
            c.drawRect(r, p);
            r.set(r.left, b + 1 * dp, r.right, getHeight() - 1 * dp);
            c.drawRect(r, p);
        }

        // прогресс обзора
        if (progress > 0 && progress < 1) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF4CAF50);
            c.drawRect(l, 0, l + (rgt - l) * progress, 3 * dp, p);
        }
    }

    private int findSel() {
        if (selCh <= 0) return -1;
        int any = -1;
        for (int i = 0; i < data.size(); i++) {
            RadioEngine.ScanResult x = data.get(i);
            if (x.ch != selCh) continue;
            if (x.ship == selShip) return i;
            if (any < 0) any = i;
        }
        return any;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP && onPick != null && !data.isEmpty()) {
            float l = left(), rgt = getWidth() - 4 * dp;
            int i = (int) ((e.getX() - l) / ((rgt - l) / data.size()));
            if (i >= 0 && i < data.size() && data.get(i).ch > 0) {
                performClick();
                onPick.pick(data.get(i));
            }
        }
        return e.getActionMasked() == MotionEvent.ACTION_DOWN || super.onTouchEvent(e);
    }

    @Override public boolean performClick() { return super.performClick(); }
}

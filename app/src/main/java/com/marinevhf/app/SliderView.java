package com.marinevhf.app;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.marinevhf.R;

/** Вертикальный ползунок панели (SQL, VOL): целые значения 0..max, янтарная ручка, риски по бокам. */
public class SliderView extends View {
    public interface Listener {
        void onChange(int value);     // во время перетаскивания
        void onCommit(int value);     // палец отпущен — можно сохранять
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float dp;
    private String label = "";
    private int max = 10, value;
    private Listener listener;

    public SliderView(Context c, AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;
        TypedArray t = c.obtainStyledAttributes(a, R.styleable.SliderView);
        String l = t.getString(R.styleable.SliderView_label);
        if (l != null) label = l;
        max = Math.max(1, t.getInt(R.styleable.SliderView_max, 10));
        t.recycle();
        p.setTypeface(Typeface.DEFAULT_BOLD);
    }

    public void setListener(Listener l) { listener = l; }

    public int getValue() { return value; }

    /** Программная установка (без вызова слушателя). */
    public void setValue(int v) {
        v = Math.max(0, Math.min(max, v));
        if (v != value) { value = v; invalidate(); }
    }

    private float top() { return 30 * dp; }
    private float bottom() { return getHeight() - 30 * dp; }

    private float yOf(int v) { return bottom() - (bottom() - top()) * v / max; }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), cx = w / 2f, t = top(), b = bottom();
        p.setTextAlign(Paint.Align.CENTER);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFDDE3EA);
        p.setTextSize(15 * dp);
        c.drawText(label, cx, 18 * dp, p);
        c.drawText(String.valueOf(value), cx, getHeight() - 8 * dp, p);

        // риски
        p.setColor(0xFF5A6572);
        p.setStrokeWidth(1.5f * dp);
        for (int i = 0; i <= max; i++) {
            float y = yOf(i);
            c.drawLine(cx - 20 * dp, y, cx - 11 * dp, y, p);
            c.drawLine(cx + 11 * dp, y, cx + 20 * dp, y, p);
        }
        // жёлоб
        p.setColor(0xFF07090C);
        r.set(cx - 5 * dp, t - 6 * dp, cx + 5 * dp, b + 6 * dp);
        c.drawRoundRect(r, 5 * dp, 5 * dp, p);
        p.setColor(0xFF2A313A);
        r.set(cx - 2.5f * dp, t - 4 * dp, cx + 2.5f * dp, b + 4 * dp);
        c.drawRoundRect(r, 3 * dp, 3 * dp, p);
        // ручка
        float y = yOf(value);
        r.set(cx - 14 * dp, y - 11 * dp, cx + 14 * dp, y + 11 * dp);
        p.setColor(0xFF8A5A00);
        c.drawRoundRect(r, 5 * dp, 5 * dp, p);
        r.set(cx - 14 * dp, y - 11 * dp, cx + 14 * dp, y + 9 * dp);
        p.setColor(0xFFFFB81F);
        c.drawRoundRect(r, 5 * dp, 5 * dp, p);
        p.setColor(0xFFB27800);
        p.setStrokeWidth(2 * dp);
        c.drawLine(cx - 9 * dp, y - 2 * dp, cx + 9 * dp, y - 2 * dp, p);
        c.drawLine(cx - 9 * dp, y + 3 * dp, cx + 9 * dp, y + 3 * dp, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE: {
                float t = top(), b = bottom();
                int v = Math.round((b - e.getY()) / (b - t) * max);
                v = Math.max(0, Math.min(max, v));
                if (v != value) {
                    value = v;
                    invalidate();
                    if (listener != null) listener.onChange(v);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (listener != null) listener.onCommit(value);
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}

package com.filminverter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Minimal themed slider: a track, a filled part and a round thumb, drawn in one onDraw (only redrawn when
 * the value changes). The track is inset from both ends so the extremes are easy to reach on the camera's
 * screen edge (owner request). Double-tap = reset.
 */
public class Slider extends View {
    public interface Listener {
        void onValue(int value, boolean fromUser);
        void onReset();
    }

    private int max = 100, value;
    private int trackColor = 0xff3a3a3a, fillColor = 0xfff2c14e, thumbColor = 0xfff2f2f2;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private Listener listener;
    private long lastUp;
    private static final int INSET = 22;   // px kept clear at each end of the track

    public Slider(Context c) { super(c); }

    public void setListener(Listener l) { listener = l; }

    public void setColors(int track, int fill, int thumb) {
        trackColor = track; fillColor = fill; thumbColor = thumb;
        invalidate();
    }

    public void setRange(int max, int value) {
        this.max = Math.max(1, max);
        this.value = Math.max(0, Math.min(this.max, value));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight(), w = getWidth(), cy = h / 2f;
        float x0 = INSET, x1 = w - INSET, x = x0 + (x1 - x0) * value / (float) max;
        paint.setColor(trackColor);
        r.set(x0, cy - 2, x1, cy + 2);
        c.drawRoundRect(r, 2, 2, paint);
        paint.setColor(fillColor);
        r.set(x0, cy - 2, x, cy + 2);
        c.drawRoundRect(r, 2, 2, paint);
        paint.setColor(thumbColor);
        c.drawCircle(x, cy, 12, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getAction() & MotionEvent.ACTION_MASK;
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            float x0 = INSET, x1 = getWidth() - INSET;
            int v = Math.round((e.getX() - x0) / Math.max(1f, x1 - x0) * max);
            v = Math.max(0, Math.min(max, v));
            if (v != value) {
                value = v;
                invalidate();
                if (listener != null) listener.onValue(v, true);
            }
            return true;
        }
        if (a == MotionEvent.ACTION_UP) {
            long t = System.currentTimeMillis();
            if (t - lastUp < 350) {
                lastUp = 0;
                if (listener != null) listener.onReset();
            } else {
                lastUp = t;
            }
            return true;
        }
        return super.onTouchEvent(e);
    }
}

package com.filminverter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Themed slider, drawn in one onDraw (only redrawn when the value changes). The track is inset from both ends
 * so the extremes are easy to reach on the camera's screen edge (owner request). Double-tap = reset.
 * Styles follow the UI concepts: LINE (thin track, round thumb), THICK (Material: tall track, bar thumb),
 * RULER (Glass: tick scale, fixed center needle, the scale moves), FLAT (Scope: hairline track, square thumb).
 */
public class Slider extends View {
    public interface Listener {
        void onValue(int value, boolean fromUser);
        void onReset();
    }

    public static final int LINE = 0, THICK = 1, RULER = 2, FLAT = 3;

    private int max = 100, value, style = LINE;
    private int trackColor = 0xff3a3a3a, fillColor = 0xfff2c14e, thumbColor = 0xfff2f2f2;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private Listener listener;
    private long lastUp;
    private float downX;
    private int downValue;
    private static final int INSET = 22;   // px kept clear at each end of the track

    public Slider(Context c) { super(c); }

    public void setListener(Listener l) { listener = l; }

    public void setStyle(int s) { style = s; invalidate(); }

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
        switch (style) {
            case THICK:
                paint.setColor(trackColor);
                r.set(x0, cy - 8, x1, cy + 8);
                c.drawRoundRect(r, 8, 8, paint);
                paint.setColor(fillColor);
                r.set(x0, cy - 8, Math.max(x0 + 16, x), cy + 8);
                c.drawRoundRect(r, 8, 8, paint);
                r.set(x + 4, cy - 14, x + 8, cy + 14);
                c.drawRoundRect(r, 2, 2, paint);
                break;
            case RULER: {
                // the scale slides under a fixed center needle: one tick per step, a long one every 5
                float step = Math.max(6f, (x1 - x0) / 24f), mid = w / 2f;
                paint.setColor(trackColor);
                paint.setStrokeWidth(1);
                for (int i = 0; i <= max; i++) {
                    float tx = mid + (i - value) * step;
                    if (tx < 0 || tx > w) continue;
                    float len = i % 5 == 0 ? h * 0.7f : h * 0.4f;
                    c.drawLine(tx, h - len, tx, h, paint);
                }
                paint.setColor(fillColor);
                paint.setStrokeWidth(3);
                c.drawLine(mid, 0, mid, h, paint);
                break;
            }
            case FLAT:
                paint.setColor(trackColor);
                r.set(x0, cy - 1, x1, cy + 1);
                c.drawRect(r, paint);
                paint.setColor(fillColor);
                r.set(x0, cy - 1, x, cy + 1);
                c.drawRect(r, paint);
                r.set(x - 5, cy - 9, x + 5, cy + 9);
                c.drawRect(r, paint);
                break;
            default:
                paint.setColor(trackColor);
                r.set(x0, cy - 2, x1, cy + 2);
                c.drawRoundRect(r, 2, 2, paint);
                paint.setColor(fillColor);
                r.set(x0, cy - 2, x, cy + 2);
                c.drawRoundRect(r, 2, 2, paint);
                paint.setColor(thumbColor);
                c.drawCircle(x, cy, 12, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getAction() & MotionEvent.ACTION_MASK;
        if (a == MotionEvent.ACTION_DOWN) { downX = e.getX(); downValue = value; }
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            int v;
            if (style == RULER) {                                   // drag the scale: left = higher, like a dial
                float step = Math.max(6f, (getWidth() - 2f * INSET) / 24f);
                v = downValue + Math.round((downX - e.getX()) / step);
            } else {
                float x0 = INSET, x1 = getWidth() - INSET;
                v = Math.round((e.getX() - x0) / Math.max(1f, x1 - x0) * max);
            }
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

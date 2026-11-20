package com.filminverter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Small custom views for the minimal UI; each draws once and only redraws when its state changes. */
final class OverlayViews {
    private OverlayViews() {}

    /** Round shutter button: a ring and a filled disc (the disc dims while a capture is saving). */
    static final class CaptureButton extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int ring = 0xfff2f2f2;
        private boolean busy;

        CaptureButton(Context c) { super(c); setClickable(true); setContentDescription("Capture"); }

        void setColor(int c) { ring = c; invalidate(); }
        void setBusy(boolean b) { busy = b; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - 2;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            p.setColor(ring);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(busy ? (ring & 0x00ffffff) | 0x55000000 : ring);
            c.drawCircle(cx, cy, r - 7, p);
        }
    }

    /**
     * Alignment guides over the live view: center cross, thirds, and a 3:2 frame box inset from the edges,
     * so the film edges can be lined up with straight lines. Static drawing, toggled from the menu.
     */
    static final class Guides extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color = 0xb3ffffff;

        Guides(Context c) { super(c); }

        void setColor(int c) { color = (c & 0x00ffffff) | 0xb3000000; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setColor(color);
            p.setStrokeWidth(1);
            for (int i = 1; i <= 2; i++) {
                c.drawLine(w * i / 3f, 0, w * i / 3f, h, p);
                c.drawLine(0, h * i / 3f, w, h * i / 3f, p);
            }
            p.setStrokeWidth(2);
            c.drawLine(w / 2f - 14, h / 2f, w / 2f + 14, h / 2f, p);
            c.drawLine(w / 2f, h / 2f - 14, w / 2f, h / 2f + 14, p);
            // 3:2 box at 92% of the width (or height), centered: line the film frame edges up with it
            float bw = w * 0.92f, bh = bw * 2f / 3f;
            if (bh > h * 0.92f) { bh = h * 0.92f; bw = bh * 1.5f; }
            float l = (w - bw) / 2f, t = (h - bh) / 2f;
            p.setStyle(Paint.Style.STROKE);
            c.drawRect(l, t, l + bw, t + bh, p);
            p.setStyle(Paint.Style.FILL);
        }
    }
}

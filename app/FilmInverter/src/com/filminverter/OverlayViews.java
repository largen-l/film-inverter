package com.filminverter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Small custom views for the layouts; each draws once and only redraws when its state changes. */
final class OverlayViews {
    private OverlayViews() {}

    /**
     * Shutter button. RING: a ring and a filled disc (Minimal, Glass, Camera). SQUARE: a rounded square in the
     * accent color with a camera outline (Material). LOUPE: a white disc in a thick dark ring with a magnifier
     * (Light table). The fill dims while a capture is saving.
     */
    static final class CaptureButton extends View {
        static final int RING = 0, SQUARE = 1, LOUPE = 2;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int ring = 0xfff2f2f2, fill = 0xfff2f2f2, ink = 0xff000000, style = RING, ringWidth = 3;
        private boolean busy;

        CaptureButton(Context c) { super(c); setClickable(true); setContentDescription("Capture"); }

        void setColor(int c) { ring = c; fill = c; invalidate(); }
        void setStyle(int s, int ring, int fill, int ink, int ringWidth) {
            style = s; this.ring = ring; this.fill = fill; this.ink = ink; this.ringWidth = ringWidth; invalidate();
        }
        void setBusy(boolean b) { busy = b; invalidate(); }

        private int dim(int c) { return busy ? (c & 0x00ffffff) | 0x55000000 : c; }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, rad = Math.min(cx, cy) - 2;
            p.setStyle(Paint.Style.FILL);
            if (style == SQUARE) {
                p.setColor(dim(fill));
                r.set(2, 2, w - 2, h - 2);
                c.drawRoundRect(r, w * 0.3f, h * 0.3f, p);
                p.setColor(ink);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.5f);
                float s = w * 0.2f;
                r.set(cx - s * 1.3f, cy - s * 0.75f, cx + s * 1.3f, cy + s);
                c.drawRoundRect(r, 3, 3, p);
                c.drawCircle(cx, cy + s * 0.12f, s * 0.5f, p);
                c.drawLine(cx - s * 0.5f, cy - s * 0.75f, cx - s * 0.3f, cy - s * 1.1f, p);
                c.drawLine(cx - s * 0.3f, cy - s * 1.1f, cx + s * 0.3f, cy - s * 1.1f, p);
                c.drawLine(cx + s * 0.3f, cy - s * 1.1f, cx + s * 0.5f, cy - s * 0.75f, p);
                return;
            }
            if (style == LOUPE) {
                p.setColor(ring);
                c.drawCircle(cx, cy, rad, p);
                p.setColor(dim(fill));
                c.drawCircle(cx, cy, rad - ringWidth, p);
                p.setColor(ink);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3);
                float s = rad * 0.32f;
                c.drawCircle(cx - s * 0.3f, cy - s * 0.3f, s, p);
                c.drawLine(cx + s * 0.4f, cy + s * 0.4f, cx + s * 1.3f, cy + s * 1.3f, p);
                return;
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(ringWidth);
            p.setColor(ring);
            c.drawCircle(cx, cy, rad - ringWidth / 2f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(dim(fill));
            c.drawCircle(cx, cy, rad - ringWidth - 5, p);
        }
    }

    /**
     * Opaque frame around the live view: fills everything except a (rounded) window. Optional bands of a second
     * color around the window (a film strip with sprocket holes, an easel with a tick ruler). Static drawing.
     */
    static final class Mask extends View {
        static final int PLAIN = 0, SPROCKETS = 1, RULER = 2;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF hole = new RectF(), band = new RectF(), tmp = new RectF();
        private int outer, bandColor, deco, decoColor;
        private float radius, bandL, bandT, bandR, bandB;
        private boolean hasBand;

        Mask(Context c) { super(c); }

        /** Window in view pixels (left, top, right, bottom) and corner radius. */
        void setWindow(int outer, float l, float t, float r, float b, float radius) {
            this.outer = outer; hole.set(l, t, r, b); this.radius = radius; invalidate();
        }

        /** A band of `color` extending the given amounts outside the window, with a decoration. */
        void setBand(int color, float l, float t, float r, float b, int deco, int decoColor) {
            hasBand = true; bandColor = color; bandL = l; bandT = t; bandR = r; bandB = b;
            this.deco = deco; this.decoColor = decoColor; invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, w, h, Path.Direction.CW);
            path.addRoundRect(hole, radius, radius, Path.Direction.CW);
            p.setColor(outer);
            c.drawPath(path, p);
            if (!hasBand) return;
            band.set(hole.left - bandL, hole.top - bandT, hole.right + bandR, hole.bottom + bandB);
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRoundRect(band, 3, 3, Path.Direction.CW);
            path.addRect(hole, Path.Direction.CW);
            p.setColor(bandColor);
            c.drawPath(path, p);
            p.setColor(decoColor);
            if (deco == SPROCKETS) {
                float hw = 12, gap = 26, sh = Math.min(12, bandT * 0.45f);
                for (float x = band.left + 10; x + hw <= band.right - 10; x += gap) {
                    tmp.set(x, band.top + (bandT - sh) / 2f, x + hw, band.top + (bandT + sh) / 2f);
                    c.drawRoundRect(tmp, 2, 2, p);
                    tmp.set(x, hole.bottom + (bandB - sh) / 2f, x + hw, hole.bottom + (bandB + sh) / 2f);
                    c.drawRoundRect(tmp, 2, 2, p);
                }
            } else if (deco == RULER) {
                p.setStrokeWidth(1);
                for (float x = hole.left; x <= hole.right; x += 12) c.drawLine(x, 6, x, 14, p);   // easel scale, screen top
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3);
                c.drawRect(hole.left - 1.5f, hole.top - 1.5f, hole.right + 1.5f, hole.bottom + 1.5f, p);
                p.setStyle(Paint.Style.FILL);
            }
        }
    }

    /** Enlarger filter dial: a colored knob with a pointer at `fraction` of its travel (-150°..+150°). */
    static final class Dial extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int light, mid, dark, pointer = 0xff2c2a26;
        private float fraction = 0.5f;
        private boolean selected;
        private int selColor = 0xffff3b2e;

        Dial(Context c, int light, int mid, int dark) {
            super(c); this.light = light; this.mid = mid; this.dark = dark; setClickable(true);
        }

        void setFraction(float f) { fraction = Math.max(0, Math.min(1, f)); invalidate(); }
        void setSelected(boolean s, int color) { selected = s; selColor = color; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, r = Math.min(cx, cy) - 4;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x55000000);
            c.drawCircle(cx, cy + 3, r, p);
            p.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.4f, r * 1.4f, new int[]{light, mid, dark},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            p.setShader(null);
            if (selected) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3);
                p.setColor(selColor);
                c.drawCircle(cx, cy, r + 1, p);
                p.setStyle(Paint.Style.FILL);
            }
            c.save();
            c.rotate(-150 + 300 * fraction, cx, cy);
            p.setColor(pointer);
            c.drawRect(cx - 2, cy - r + 6, cx + 2, cy - r + 26, p);
            c.restore();
        }
    }

    /** RGB histogram of the last capture (Scope layout), three translucent filled curves. */
    static final class Histogram extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int[][] bins;
        private int frame = 0xff222222, label = 0xff8a8a8a;
        private String caption = "LAST CAPTURE · RGB";

        Histogram(Context c) { super(c); }

        void setColors(int frame, int label) { this.frame = frame; this.label = label; invalidate(); }
        void setBins(int[][] b, String caption) { bins = b; if (caption != null) this.caption = caption; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(frame);
            c.drawRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(label);
            p.setTextSize(11);
            c.drawText(bins == null ? "NO CAPTURE YET" : caption, 6, 14, p);
            if (bins == null) return;
            int max = 1;
            for (int[] ch : bins) for (int i = 1; i < ch.length - 1; i++) max = Math.max(max, ch[i]);   // ignore clipped ends
            int[] colors = {0x73ff5050, 0x7350dc78, 0x735a96ff};
            for (int ch = 0; ch < 3; ch++) {
                int[] b = bins[ch];
                path.reset();
                path.moveTo(0, h);
                for (int i = 0; i < b.length; i++) {
                    float x = w * i / (float) (b.length - 1), y = h - (h - 18) * Math.min(1f, b[i] / (float) max);
                    path.lineTo(x, y);
                }
                path.lineTo(w, h);
                path.close();
                p.setColor(colors[ch]);
                c.drawPath(path, p);
            }
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

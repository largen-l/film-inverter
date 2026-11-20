package com.filminverter.engine;

/**
 * Colour-negative inversion for 8-bit (camera JPEG) input.
 *
 * Port of engine/negengine.py. For 8-bit sources every step of the Python pipeline
 * (sRGB decode, divide by base, -log10, levels, print curve, sRGB-ish output) is a
 * pointwise per-channel function, so the whole inversion collapses to three
 * 256-entry lookup tables. Building them is microseconds; applying them is one
 * array lookup per channel per pixel -- cheap enough for the camera's Cortex-A5.
 *
 * Roll-level black/white points come from per-channel 256-bin histograms, which can
 * be accumulated frame by frame (tile by tile) without holding images in memory.
 *
 * Pure Java 7, no Android dependencies, so it is unit-tested on the desktop JVM.
 * Pixels are packed ARGB ints (android.graphics.Bitmap#getPixels layout).
 */
public final class NegEngine {

    public static final int FILM_C41 = 0;
    public static final int FILM_BW = 1;
    public static final int FILM_SLIDE = 2;

    private static final double EPS = 1e-6;

    /** Roll profile; field meanings match RollProfile in negengine.py. */
    public static final class Profile {
        public double[] base = {1, 1, 1};   // linear RGB of clear film base
        public double[] dLo;                // density black points (null = from histogram)
        public double[] dHi;                // density white points
        public double loPct = 0.1, hiPct = 99.9;
        public double contrast = 1.0, gamma = 1.0, temp = 0.0, tint = 0.0;
        public int film = FILM_C41;
        public double baseExclude = 0.04;   // ignore pixels this close to the base when leveling
    }

    // ------------------------------------------------------------------ colour
    static double srgbToLinear(double x) {
        x = Math.max(0, Math.min(1, x));
        return x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
    }

    static double linearToSrgb(double x) {
        x = Math.max(0, Math.min(1, x));
        return x <= 0.0031308 ? x * 12.92 : 1.055 * Math.pow(x, 1 / 2.4) - 0.055;
    }

    private static final double[] LIN = new double[256];
    static { for (int i = 0; i < 256; i++) LIN[i] = srgbToLinear(i / 255.0); }

    /** Density of an 8-bit code value for one channel given the base (linear). */
    static double density(int code, double base) {
        double t = LIN[code] / Math.max(base, EPS);
        t = Math.max(EPS, Math.min(1.0, t));
        return -Math.log10(t);
    }

    // ------------------------------------------------------------------ base
    /** Median-ish base estimate from a rectangle of pixels (returns linear RGB). */
    public static double[] sampleBase(int[] px, int width, int x0, int y0, int x1, int y1) {
        int[][] h = new int[3][256];
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int c = px[y * width + x];
                h[0][(c >> 16) & 255]++; h[1][(c >> 8) & 255]++; h[2][c & 255]++;
                n++;
            }
        }
        double[] b = new double[3];
        for (int ch = 0; ch < 3; ch++) b[ch] = LIN[percentileCode(h[ch], n, 50.0)];
        return b;
    }

    // ------------------------------------------------------------------ frame analysis
    // Same algorithm and constants as analyse_frame() in negengine.py; keep them in sync.
    static final double FRAME_LINE_FRAC = 0.6, FRAME_MAX_BORDER = 0.25, FRAME_INSET = 0.015;
    static final double FRAME_BRIGHT_REL = 0.9, FRAME_DARK = 0.004, CLIP = 0.985, HOLE_RATIO = 1.25;
    static final double FRAME_LINE_REL = 0.85, FRAME_GAP = 0.03;
    static final int FRAME_MAX_SIDE = 600;   // analyse a subsampled copy (probe 0.5 took 11 s at 1500 px)
    private static final int LUM_BINS = 4096;

    /** Picture area (x1/y1 exclusive) and the film base found in the border around it (null if none). */
    public static final class Frame {
        public int x0, y0, x1, y1;
        public boolean borderFound;
        public double[] base;
        public String toString() {
            return "[" + x0 + "," + y0 + "," + x1 + "," + y1 + "] border=" + borderFound
                    + " base=" + (base == null ? "null" : arr(base));
        }
    }

    static double lum(int c) {
        return 0.2126 * LIN[(c >> 16) & 255] + 0.7152 * LIN[(c >> 8) & 255] + 0.0722 * LIN[c & 255];
    }

    static double maxLin(int c) {
        return Math.max(LIN[(c >> 16) & 255], Math.max(LIN[(c >> 8) & 255], LIN[c & 255]));
    }

    private static int lumBin(double l) { return Math.min(LUM_BINS - 1, (int) (l * LUM_BINS)); }

    private static double binValue(int bin) { return (bin + 0.5) / LUM_BINS; }

    private static double pctFromHist(int[] hist, long n, double pct) {
        long target = Math.max(1, (long) Math.ceil(n * pct / 100.0));
        long acc = 0;
        for (int i = 0; i < hist.length; i++) { acc += hist[i]; if (acc >= target) return binValue(i); }
        return binValue(hist.length - 1);
    }

    /**
     * Border extent along rows (rows=true: lines are rows, pixels taken from columns c0..c1) or columns.
     * Returns {a, b}: lines [0,a) and [b,n) are border. See _border_lines() in negengine.py.
     */
    private static int[] borderLines(int[] px, int w, int h, boolean rows, int c0, int c1, double brightThr) {
        int n = rows ? h : w;
        int zone = Math.max(1, (int) (n * FRAME_MAX_BORDER));
        int gap = Math.max(2, (int) (n * FRAME_GAP));
        double[] fAny = new double[n], fDark = new double[n], fBright = new double[n], med = new double[n];
        int[] hist = new int[LUM_BINS];
        for (int line = 0; line < n; line++) {
            boolean inZone = line < zone || line >= n - zone;
            java.util.Arrays.fill(hist, 0);
            int any = 0, dark = 0, bright = 0, cnt = 0;
            for (int k = c0; k < c1; k++) {
                int c = rows ? px[line * w + k] : px[k * w + line];
                double l = lum(c);
                boolean isBright = l >= brightThr, isDark = maxLin(c) < FRAME_DARK;
                if (isBright || isDark) any++;
                if (isDark) dark++;
                if (isBright) bright++;
                hist[lumBin(l)]++;
                cnt++;
            }
            if (cnt == 0) continue;
            fAny[line] = any / (double) cnt;
            fDark[line] = dark / (double) cnt;
            fBright[line] = bright / (double) cnt;
            if (inZone || fAny[line] >= FRAME_LINE_FRAC) med[line] = pctFromHist(hist, cnt, 50.0);
        }
        double lref = Double.POSITIVE_INFINITY;
        boolean haveRef = false;
        for (int line = 0; line < n; line++) {
            boolean inZone = line < zone || line >= n - zone;
            if (inZone && fBright[line] >= FRAME_LINE_FRAC) {
                lref = haveRef ? Math.max(lref, med[line]) : med[line];
                haveRef = true;
            }
        }
        boolean[] isBorder = new boolean[n];
        for (int line = 0; line < n; line++)
            isBorder[line] = fAny[line] >= FRAME_LINE_FRAC
                    && (fDark[line] >= fBright[line] || med[line] >= FRAME_LINE_REL * lref);
        int a = peel(isBorder, zone, gap, true, n);
        int b = n - peel(isBorder, zone, gap, false, n);
        return new int[]{a, b};
    }

    private static int peel(boolean[] isBorder, int zone, int gap, boolean fromStart, int n) {
        int last = -1, miss = 0;
        for (int k = 0; k < zone; k++) {
            int i = fromStart ? k : n - 1 - k;
            if (isBorder[i]) { last = k; miss = 0; }
            else if (++miss > gap) break;
        }
        return last + 1;
    }

    /**
     * Find the picture area of a scanned negative and the film base from the film border around it.
     * Levels must come from the picture area only (see analyse_frame() in negengine.py for the why).
     */
    public static Frame analyseFrame(int[] px, int w, int h) {
        int s = Math.max(1, (int) Math.ceil(Math.max(w, h) / (double) FRAME_MAX_SIDE));
        if (s == 1) return analyseSmall(px, w, h);
        int sw = (w + s - 1) / s, sh = (h + s - 1) / s;          // same pixels as numpy lin[::s, ::s]
        int[] small = new int[sw * sh];
        for (int y = 0; y < sh; y++)
            for (int x = 0; x < sw; x++) small[y * sw + x] = px[(y * s) * w + x * s];
        Frame f = analyseSmall(small, sw, sh);
        f.x0 *= s; f.y0 *= s;
        f.x1 = Math.min(w, f.x1 * s); f.y1 = Math.min(h, f.y1 * s);
        return f;
    }

    private static Frame analyseSmall(int[] px, int w, int h) {
        // reference brightness: 99th percentile luminance of the frame centre (always picture)
        int[] hist = new int[LUM_BINS];
        long n = 0;
        for (int y = h / 4; y < h - h / 4; y++)
            for (int x = w / 4; x < w - w / 4; x++) { hist[lumBin(lum(px[y * w + x]))]++; n++; }
        double brightThr = FRAME_BRIGHT_REL * pctFromHist(hist, n, 99.0);

        int[] ry = borderLines(px, w, h, true, 0, w, brightThr);
        int[] rx = borderLines(px, w, h, false, ry[0], ry[1], brightThr);
        ry = borderLines(px, w, h, true, rx[0], rx[1], brightThr);
        int x0 = rx[0], x1 = rx[1], y0 = ry[0], y1 = ry[1];
        Frame f = new Frame();
        f.borderFound = x0 > 0 || y0 > 0 || x1 < w || y1 < h;

        if (f.borderFound) {
            // base: bright, unclipped pixels outside the picture; drop the brighter group (sprocket holes)
            java.util.Arrays.fill(hist, 0);
            n = 0;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    if (x >= x0 && x < x1 && y >= y0 && y < y1) continue;
                    int c = px[y * w + x];
                    double l = lum(c);
                    if (l >= brightThr && maxLin(c) < CLIP) { hist[lumBin(l)]++; n++; }
                }
            if (n >= 200) {
                double p5 = pctFromHist(hist, n, 5), p95 = pctFromHist(hist, n, 95);
                double cut = p95 > HOLE_RATIO * p5 ? Math.sqrt(p5 * p95) : Double.POSITIVE_INFINITY;
                int[][] ch = new int[3][256];
                long m = 0;
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) {
                        if (x >= x0 && x < x1 && y >= y0 && y < y1) continue;
                        int c = px[y * w + x];
                        double l = lum(c);
                        if (l >= brightThr && maxLin(c) < CLIP && l < cut) {
                            ch[0][(c >> 16) & 255]++; ch[1][(c >> 8) & 255]++; ch[2][c & 255]++; m++;
                        }
                    }
                if (m > 0) f.base = new double[]{
                        LIN[percentileCode(ch[0], m, 50.0)], LIN[percentileCode(ch[1], m, 50.0)], LIN[percentileCode(ch[2], m, 50.0)]};
            }
        }
        int iy = (int) Math.round(FRAME_INSET * h), ix = (int) Math.round(FRAME_INSET * w);
        f.x0 = x0 > 0 ? x0 + ix : 0;
        f.y0 = y0 > 0 ? y0 + iy : 0;
        f.x1 = x1 < w ? x1 - ix : w;
        f.y1 = y1 < h ? y1 - iy : h;
        if (f.x1 - f.x0 < w / 4 || f.y1 - f.y0 < h / 4) {          // implausible: use the whole frame
            f.x0 = 0; f.y0 = 0; f.x1 = w; f.y1 = h; f.borderFound = false;
        }
        return f;
    }

    // ------------------------------------------------------------------ histograms
    /**
     * Accumulator for roll-level levels. Feed it frames/tiles with {@link #add};
     * pixels that are just film base (all channels within baseExclude density) are skipped.
     */
    public static final class Histogram {
        public final long[][] h = new long[3][256];
        public long n;
        private final boolean[][] nearBase = new boolean[3][256];

        public Histogram(Profile p) {
            for (int ch = 0; ch < 3; ch++)
                for (int i = 0; i < 256; i++)
                    nearBase[ch][i] = density(i, p.base[ch]) <= p.baseExclude;
        }

        /** Add every `step`-th pixel. */
        public void add(int[] px, int offset, int count, int step) {
            if (step < 1) step = 1;
            for (int i = offset; i < offset + count; i += step) {
                int c = px[i];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                if (nearBase[0][r] && nearBase[1][g] && nearBase[2][b]) continue;
                h[0][r]++; h[1][g]++; h[2][b]++;
                n++;
            }
        }

        /** Add the picture area of a frame (see {@link #analyseFrame}), every `step`-th pixel in x and y. */
        public void addArea(int[] px, int width, Frame f, int step) {
            if (step < 1) step = 1;
            for (int y = f.y0; y < f.y1; y += step) add(px, y * width + f.x0, f.x1 - f.x0, step);
        }
    }

    private static int percentileCode(int[] hist, long n, double pct) {
        long[] l = new long[256];
        for (int i = 0; i < 256; i++) l[i] = hist[i];
        return percentileCode(l, n, pct);
    }

    private static int percentileCode(long[] hist, long n, double pct) {
        long target = (long) Math.ceil(n * pct / 100.0);
        long acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= target) return i;
        }
        return 255;
    }

    /**
     * Density black/white points from a histogram. Density falls as the code value
     * rises, so the density's low percentile is the code value's high percentile.
     */
    public static void levelsFromHistogram(Profile p, Histogram hist) {
        double[] lo = new double[3], hi = new double[3];
        for (int ch = 0; ch < 3; ch++) {
            int codeForDLo = percentileCode(hist.h[ch], hist.n, 100.0 - p.loPct);
            int codeForDHi = percentileCode(hist.h[ch], hist.n, 100.0 - p.hiPct);
            lo[ch] = density(codeForDLo, p.base[ch]);
            hi[ch] = Math.max(density(codeForDHi, p.base[ch]), lo[ch] + 0.05);
        }
        if (p.film == FILM_BW) {
            double l = (lo[0] + lo[1] + lo[2]) / 3, h = (hi[0] + hi[1] + hi[2]) / 3;
            lo = new double[]{l, l, l}; hi = new double[]{h, h, h};
        }
        p.dLo = lo; p.dHi = hi;
    }

    // ------------------------------------------------------------------ curve
    static double printCurve(double v, double contrast, double gamma) {
        v = Math.max(0, Math.min(1, v));
        if (gamma != 1.0) v = Math.pow(v, 1.0 / gamma);
        if (contrast != 1.0) {
            double k = 4.0 * contrast;
            double s = 1.0 / (1.0 + Math.exp(-k * (v - 0.5)));
            double s0 = 1.0 / (1.0 + Math.exp(k * 0.5));
            double s1 = 1.0 / (1.0 + Math.exp(-k * 0.5));
            v = (s - s0) / (s1 - s0);
        }
        return v;
    }

    // ------------------------------------------------------------------ LUTs
    /** Build the three per-channel 256-entry output tables. Profile levels must be set (unless slide). */
    public static int[][] buildLuts(Profile p) {
        int[][] lut = new int[3][256];
        if (p.film == FILM_SLIDE) {
            for (int ch = 0; ch < 3; ch++) for (int i = 0; i < 256; i++) lut[ch][i] = i;
            return lut;
        }
        if (p.dLo == null || p.dHi == null) throw new IllegalStateException("levels not set");
        double[] shiftUnit = {p.temp, -p.tint, -p.temp};
        for (int ch = 0; ch < 3; ch++) {
            double span = p.dHi[ch] - p.dLo[ch];
            double shift = shiftUnit[ch] * 0.05 * span;
            for (int i = 0; i < 256; i++) {
                double d = density(i, p.base[ch]);
                double v = (d - p.dLo[ch] + shift) / span;
                int o = (int) Math.round(printCurve(v, p.contrast, p.gamma) * 255.0);
                lut[ch][i] = Math.max(0, Math.min(255, o));
            }
        }
        return lut;
    }

    /** In-place inversion of packed ARGB pixels. B&W uses the mean density of the three channels. */
    public static void apply(int[] px, int offset, int count, int[][] lut, int film) {
        int[] r = lut[0], g = lut[1], b = lut[2];
        if (film == FILM_BW) {
            for (int i = offset; i < offset + count; i++) {
                int c = px[i];
                int v = (r[(c >> 16) & 255] + g[(c >> 8) & 255] + b[c & 255]) / 3;
                px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            return;
        }
        for (int i = offset; i < offset + count; i++) {
            int c = px[i];
            px[i] = 0xFF000000 | (r[(c >> 16) & 255] << 16) | (g[(c >> 8) & 255] << 8) | b[c & 255];
        }
    }

    /** Profile -> small JSON string (no org.json on purpose: same code runs on desktop JVM). */
    public static String toJson(Profile p) {
        StringBuilder s = new StringBuilder("{");
        s.append("\"base\":").append(arr(p.base)).append(',');
        s.append("\"d_lo\":").append(arr(p.dLo)).append(',');
        s.append("\"d_hi\":").append(arr(p.dHi)).append(',');
        s.append("\"lo_pct\":").append(p.loPct).append(",\"hi_pct\":").append(p.hiPct).append(',');
        s.append("\"contrast\":").append(p.contrast).append(",\"gamma\":").append(p.gamma).append(',');
        s.append("\"temp\":").append(p.temp).append(",\"tint\":").append(p.tint).append(',');
        s.append("\"film_type\":\"").append(p.film == FILM_BW ? "bw" : p.film == FILM_SLIDE ? "slide" : "c41").append("\"}");
        return s.toString();
    }

    private static String arr(double[] a) {
        if (a == null) return "null";
        return "[" + a[0] + "," + a[1] + "," + a[2] + "]";
    }

    private NegEngine() {}
}

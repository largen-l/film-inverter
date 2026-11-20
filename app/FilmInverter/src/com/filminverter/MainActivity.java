package com.filminverter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Button;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.filminverter.engine.NegEngine;
import com.github.ma1co.openmemories.framework.DateTime;
import com.sony.scalar.hardware.CameraEx;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;

/**
 * M0 probe, version 2. Everything is logged to FIPROBE.TXT on the card root.
 *
 * On launch (status bar says WORKING, buttons are ignored until DONE):
 *   [1] device, heap, real (Sony) time
 *   [2] which Sony classes exist (names only; the full dump is in docs/api-usage/probe1.md)
 *   [3] invert the newest RAW+JPEG pair's JPEG -> DCIM/FITEST/*_POS.JPG
 * Centre button when READY:
 *   [4] live view tests with CameraEx: normal live view + preview-frame count, read gamma table and
 *       RGB matrix (saved to FIGAMMA.BIN), then for a few seconds each: red/blue swapped matrix,
 *       inverted gamma table (two variants). Everything is restored afterwards and on exit.
 * MENU: exits when READY; while WORKING it must be pressed twice within 3 s.
 * Touch test: on-screen "Live view tests" / "Exit" buttons do the same; every touch is logged.
 *
 * Probe 1 findings and why these steps exist: docs/api-usage/probe1.md.
 */
public class MainActivity extends SonyActivity implements SurfaceHolder.Callback {

    private static final int BUSY = 0, READY = 1, LIVE = 2;

    private TextView status;
    private TextView text;
    private ScrollView scroll;
    private SurfaceView surface;
    private PrintWriter log;
    private final Handler ui = new Handler();
    private volatile boolean surfaceReady;
    private volatile int state = BUSY;
    private long lastMenuPress;

    // live view test state, restored on exit no matter what
    private CameraEx cameraEx;
    private int[] origMatrix;
    private boolean matrixChanged;
    private byte[] origGamma;
    private boolean gammaChanged;
    private boolean origGammaWasNull;

    @Override
    protected void onCreate(Bundle b) {
        try {
            log = new PrintWriter(new FileWriter(new File(Environment.getExternalStorageDirectory(), "FIPROBE.TXT"), true));
        } catch (Exception e) {
            log = null;
        }
        installCrashLogger();
        super.onCreate(b);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        surface.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS); // needed for camera preview
        root.addView(surface, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setTextSize(16);
        status.setTextColor(Color.BLACK);
        status.setGravity(Gravity.CENTER);
        status.setPadding(4, 4, 4, 4);
        overlay.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Touch test: big buttons doing the same as the centre / MENU keys. Every touch is also logged
        // (dispatchTouchEvent) so we learn whether the a6500 passes touches to apps at all.
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button live = new Button(this);
        live.setText("Live view tests");
        live.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { out("touch: Live view tests button"); onEnterAction(); }
        });
        Button exit = new Button(this);
        exit.setText("Exit");
        exit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { out("touch: Exit button"); onMenuAction(); }
        });
        buttons.addView(live, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(exit, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        overlay.addView(buttons, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        scroll = new ScrollView(this);
        text = new TextView(this);
        text.setTextSize(11);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(0xC0000000);
        scroll.addView(text);
        overlay.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(overlay);
        setContentView(root);

        setStatus(BUSY, "WORKING... please wait");
        out("=== Film Inverter probe 2, " + now() + " ===");
        new Thread(new Runnable() { public void run() { runSafeProbes(); } }).start();
    }

    // ------------------------------------------------------------------ logging / status
    private void out(final String s) {
        if (log != null) { log.println(s); log.flush(); }
        ui.post(new Runnable() {
            public void run() {
                text.append(s + "\n");
                scroll.post(new Runnable() { public void run() { scroll.fullScroll(ScrollView.FOCUS_DOWN); } });
            }
        });
    }

    private void err(String what, Throwable t) {
        out("!! " + what + ": " + t);
        if (log != null) { t.printStackTrace(log); log.flush(); }
    }

    @Override
    protected void logWarning(String what, Throwable t) { err(what, t); }

    /** Big status bar at the top. Colour: yellow = working, green = ready, cyan = live view test. */
    private void setStatus(final int newState, final String msg) {
        state = newState;
        if (log != null) { log.println("STATUS: " + msg); log.flush(); }
        ui.post(new Runnable() {
            public void run() {
                status.setText(msg);
                status.setBackgroundColor(newState == READY ? 0xFF60E060 : newState == LIVE ? 0xFF60E0E0 : 0xFFF0D040);
            }
        });
    }

    private void showLog(final boolean visible) {
        ui.post(new Runnable() {
            public void run() { scroll.setVisibility(visible ? View.VISIBLE : View.GONE); }
        });
    }

    /** Real time from Sony's TimeUtil (java.util.Date says 1970 on the camera). */
    private String now() {
        try {
            Calendar c = DateTime.getInstance().getCurrentTime();
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            f.setTimeZone(c.getTimeZone());
            return f.format(c.getTime());
        } catch (Throwable t) {
            return new java.util.Date() + " (system clock)";
        }
    }

    /**
     * Any crash the probes do not catch themselves is written to FIPROBE.TXT with its stack trace
     * before the app dies, then handed to the default handler.
     */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread th, Throwable t) {
                try {
                    PrintWriter w = log != null ? log : new PrintWriter(new FileWriter(
                            new File(Environment.getExternalStorageDirectory(), "FIPROBE.TXT"), true));
                    w.println("!! CRASH in thread " + th.getName() + ": " + t);
                    t.printStackTrace(w);
                    w.flush();
                } catch (Throwable ignored) {}
                restoreCamera();
                if (prev != null) prev.uncaughtException(th, t);
            }
        });
    }

    // ------------------------------------------------------------------ safe probes
    private void runSafeProbes() {
        setStatus(BUSY, "WORKING 1/3: device info");
        try { probeDevice(); } catch (Throwable t) { err("device", t); }
        setStatus(BUSY, "WORKING 2/3: Sony API check");
        try { probeSonyApi(); } catch (Throwable t) { err("sony api", t); }
        setStatus(BUSY, "WORKING 3/3: inverting newest RAW+JPEG (can take ~30 s)");
        try {
            File jpg = pickJpeg();
            if (jpg != null) invertFile(jpg);
        } catch (Throwable t) { err("invert", t); }
        out("--- safe probes done ---");
        setStatus(READY, "DONE. Centre = live view tests, MENU = exit");
    }

    private void probeDevice() {
        Runtime rt = Runtime.getRuntime();
        out("[1] model=" + Build.MODEL + " device=" + Build.DEVICE + " android=" + Build.VERSION.RELEASE
                + " sdk=" + Build.VERSION.SDK_INT);
        out("    maxMemory=" + rt.maxMemory() / 1048576 + "MB cpus=" + rt.availableProcessors());
        out("    sony time=" + now() + "   system clock=" + new java.util.Date());
    }

    private static final String[] SONY_CLASSES = {
        "com.sony.scalar.hardware.CameraEx",
        "com.sony.scalar.hardware.CameraEx$GammaTable",
        "com.sony.scalar.hardware.CameraSequence",
        "com.sony.scalar.hardware.DSP",
        "com.sony.scalar.graphics.JpegExporter",
        "com.sony.scalar.graphics.OptimizedImageFactory",
        "com.sony.scalar.provider.AvindexStore",
        "com.sony.scalar.sysutil.TimeUtil",
    };

    private void probeSonyApi() {
        StringBuilder s = new StringBuilder("[2] Sony classes:");
        for (String name : SONY_CLASSES) {
            boolean ok;
            try { Class.forName(name); ok = true; } catch (Throwable t) { ok = false; }
            s.append(ok ? "  ok " : "  MISSING ").append(name.substring(name.lastIndexOf('.') + 1));
        }
        out(s.toString());
    }

    /** Newest JPEG that has an ARW with the same name next to it (a RAW+JPEG pair). */
    private File pickJpeg() {
        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
        File bestPair = null, bestAny = null;
        File[] sub = dcim.listFiles();
        if (sub != null) {
            for (File d : sub) {
                if (!d.isDirectory() || d.getName().equals("FITEST")) continue;
                File[] fs = d.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    String n = f.getName().toUpperCase();
                    if (!n.endsWith(".JPG")) continue;
                    if (bestAny == null || f.lastModified() > bestAny.lastModified()) bestAny = f;
                    File arw = new File(d, f.getName().substring(0, f.getName().length() - 4) + ".ARW");
                    if (arw.exists() && (bestPair == null || f.lastModified() > bestPair.lastModified())) bestPair = f;
                }
            }
        }
        if (bestPair != null) {
            out("[3] newest RAW+JPEG pair: " + bestPair.getAbsolutePath());
            return bestPair;
        }
        out("[3] NO RAW+JPEG pair found on the card. Set Quality = RAW+JPEG, shoot a negative, relaunch.");
        out("    (newest JPEG is " + (bestAny == null ? "none" : bestAny.getAbsolutePath()) + "; not inverting it)");
        return null;
    }

    /**
     * Real inversion at 1/4 size (1/2 does not fit the 24 MB heap): auto base, per-frame levels,
     * LUT apply, JPEG out. Full-res strip processing comes in M2.
     */
    private void invertFile(File jpg) throws Exception {
        long t0 = System.currentTimeMillis();
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = 4;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bm = BitmapFactory.decodeFile(jpg.getAbsolutePath(), d);
        if (bm == null) { out("    decode failed"); return; }
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        bm.recycle();
        long t1 = System.currentTimeMillis();
        out("    decoded " + w + "x" + h + " in " + (t1 - t0) + " ms");

        NegEngine.Profile p = new NegEngine.Profile();
        p.base = autoBase(px);
        out("    auto base (linear) = " + fmt(p.base));
        NegEngine.Histogram hist = new NegEngine.Histogram(p);
        hist.add(px, 0, px.length, 4);
        NegEngine.levelsFromHistogram(p, hist);
        int[][] lut = NegEngine.buildLuts(p);
        long t2 = System.currentTimeMillis();
        NegEngine.apply(px, 0, px.length, lut, p.film);
        long t3 = System.currentTimeMillis();
        out("    profile " + (t2 - t1) + " ms, apply " + (t3 - t2) + " ms");

        Bitmap outBm = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        px = null;
        File dir = new File(Environment.getExternalStorageDirectory(), "DCIM/FITEST");
        dir.mkdirs();
        File dst = new File(dir, jpg.getName().replaceAll("(?i)\\.JPG$", "") + "_POS.JPG");
        FileOutputStream fo = new FileOutputStream(dst);
        try { outBm.compress(Bitmap.CompressFormat.JPEG, 92, fo); } finally { fo.close(); }
        outBm.recycle();
        long t4 = System.currentTimeMillis();
        out("    encode+write " + (t4 - t3) + " ms, total " + (t4 - t0) + " ms");
        out("    wrote " + dst.getAbsolutePath());
        out("    profile " + NegEngine.toJson(p));
        out("    -> CHECK: does the camera's playback show DCIM/FITEST/" + dst.getName() + "?");
    }

    /** Film base guess: per-channel 99.5th percentile of pixels with no clipped channel. */
    private static double[] autoBase(int[] px) {
        long[][] h = new long[3][256];
        long n = 0;
        for (int i = 0; i < px.length; i += 2) {
            int c = px[i];
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            if (r >= 251 || g >= 251 || b >= 251) continue;
            h[0][r]++; h[1][g]++; h[2][b]++; n++;
        }
        double[] base = new double[3];
        for (int ch = 0; ch < 3; ch++) {
            long target = (long) (n * 0.995), acc = 0;
            int code = 255;
            for (int i = 0; i < 256; i++) { acc += h[ch][i]; if (acc >= target) { code = i; break; } }
            double x = code / 255.0;
            base[ch] = x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
        }
        return base;
    }

    private static String fmt(double[] a) {
        return String.format("[%.4f, %.4f, %.4f]", a[0], a[1], a[2]);
    }

    // ------------------------------------------------------------------ live view tests
    private void liveViewTests() {
        out("[4] live view tests (CameraEx), " + now());
        showLog(false);
        setDisplayColorDepthOnUi(false);
        try {
            if (!surfaceReady) { out("    surface not ready"); return; }
            setStatus(LIVE, "Opening camera...");
            cameraEx = CameraEx.open(0, null);
            Camera cam = cameraEx.getNormalCamera();
            try {
                Camera.Parameters prm = cam.getParameters();
                Camera.Size ps = prm.getPreviewSize();
                out("    preview size=" + (ps == null ? "null" : ps.width + "x" + ps.height)
                        + " format=" + prm.getPreviewFormat());
                out("    params: " + prm.flatten());
            } catch (Throwable t) { err("parameters", t); }

            cam.setPreviewDisplay(surface.getHolder());
            final int[] frames = {0};
            final long[] firstLen = {0};
            try {
                cam.setPreviewCallback(new Camera.PreviewCallback() {
                    public void onPreviewFrame(byte[] data, Camera c) {
                        if (frames[0] == 0 && data != null) firstLen[0] = data.length;
                        frames[0]++;
                    }
                });
            } catch (Throwable t) { err("setPreviewCallback", t); }
            cam.startPreview();
            setStatus(LIVE, "A) NORMAL live view (5 s)");
            Thread.sleep(5000);
            try { cam.setPreviewCallback(null); } catch (Throwable ignored) {}
            out("    A) preview frames to app in 5 s: " + frames[0] + " (first buffer " + firstLen[0] + " bytes)");

            readGammaAndMatrix();
            testMatrixSwap();
            testGammaInvert();

            setStatus(LIVE, "E) back to NORMAL live view (3 s)");
            Thread.sleep(3000);
        } catch (Throwable t) {
            err("live view tests", t);
        } finally {
            restoreCamera();
            setDisplayColorDepthOnUi(true);
            showLog(true);
            out("--- live view tests done, " + now() + " ---");
            setStatus(READY, "DONE. Centre = run live view tests again, MENU = exit");
        }
    }

    private void setDisplayColorDepthOnUi(final boolean high) {
        ui.post(new Runnable() { public void run() { setDisplayColorDepth(high); } });
    }

    private void readGammaAndMatrix() {
        CameraEx cx = cameraEx;
        try {
            boolean gs = modifier(cx.getNormalCamera().getParameters()).isExtendedGammaTableSupported();
            out("    gamma: isExtendedGammaTableSupported=" + gs);
            CameraEx.GammaTable cur = cx.getExtendedGammaTable();
            origGammaWasNull = cur == null;
            if (cur == null) {
                out("    gamma: getExtendedGammaTable() = null; reading a fresh createGammaTable() instead");
                cur = cx.createGammaTable();
            }
            if (cur != null) {
                int size = cur.getSize();
                byte[] bytes = new byte[size];
                int n = cur.read(bytes);
                out("    gamma: size=" + size + " bytes, read=" + n + ", forceOff=" + safeForceOff(cur));
                origGamma = bytes;
                saveBytes("FIGAMMA.BIN", bytes);
                describeTable(bytes);
            }
        } catch (Throwable t) { err("read gamma table", t); }
        try {
            boolean ms = modifier(cx.getNormalCamera().getParameters()).isRGBMatrixSupported();
            origMatrix = readMatrix();
            out("    matrix: isRGBMatrixSupported=" + ms + " value=" + arr(origMatrix));
        } catch (Throwable t) { err("read RGB matrix", t); }
    }

    private static String safeForceOff(CameraEx.GammaTable g) {
        try { return String.valueOf(g.isPictureEffectGammaForceOff()); } catch (Throwable t) { return "?"; }
    }

    /** Log the table as hex and as uint16 little-endian values so the format can be worked out. */
    private void describeTable(byte[] b) {
        StringBuilder hex = new StringBuilder("    gamma hex[0..63]:");
        for (int i = 0; i < Math.min(64, b.length); i++) hex.append(String.format(" %02x", b[i] & 255));
        out(hex.toString());
        int n = b.length / 2;
        if (n == 0) return;
        int[] v = u16(b);
        int min = Integer.MAX_VALUE, max = 0, drops = 0;
        for (int i = 0; i < n; i++) {
            min = Math.min(min, v[i]); max = Math.max(max, v[i]);
            if (i > 0 && v[i] < v[i - 1]) drops++;
        }
        StringBuilder s = new StringBuilder("    gamma u16: n=" + n + " min=" + min + " max=" + max + " decreasing steps=" + drops + " first:");
        for (int i = 0; i < Math.min(24, n); i++) s.append(' ').append(v[i]);
        s.append(" ... last:");
        for (int i = Math.max(0, n - 8); i < n; i++) s.append(' ').append(v[i]);
        out(s.toString());
        int[] seg = segments(v);
        out("    gamma segments (curve restarts): " + (seg.length - 1) + " -> " + arr(seg));
    }

    private void testMatrixSwap() throws InterruptedException {
        if (origMatrix == null || origMatrix.length != 9) {
            out("    B) matrix test skipped (matrix " + (origMatrix == null ? "null" : "length " + origMatrix.length) + ")");
            return;
        }
        int[] sw = origMatrix.clone();
        for (int i = 0; i < 3; i++) { sw[i] = origMatrix[6 + i]; sw[6 + i] = origMatrix[i]; } // swap R and B rows
        try {
            writeMatrix(sw);
            matrixChanged = true;
            out("    B) set swapped matrix " + arr(sw) + ", readback " + arr(readMatrix()));
            setStatus(LIVE, "B) RED and BLUE should look SWAPPED (5 s)");
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw e;
        } catch (Throwable t) {
            err("B) setRGBMatrix", t);
        } finally {
            restoreMatrix();
        }
    }

    private void testGammaInvert() throws InterruptedException {
        if (origGamma == null || origGamma.length < 4) { out("    C/D) gamma test skipped (no table read)"); return; }
        int[] v = u16(origGamma);
        int[] seg = segments(v);
        // C) reverse each curve: out(x) = f(max - x). Inverts the input, i.e. the linear signal.
        int[] rev = v.clone();
        for (int s = 0; s + 1 < seg.length; s++)
            for (int i = seg[s], j = seg[s + 1] - 1; i < seg[s + 1]; i++, j--) rev[i] = v[j];
        // D) mirror each curve's output: out(x) = max - f(x).
        int[] mir = v.clone();
        for (int s = 0; s + 1 < seg.length; s++) {
            int lo = Integer.MAX_VALUE, hi = 0;
            for (int i = seg[s]; i < seg[s + 1]; i++) { lo = Math.min(lo, v[i]); hi = Math.max(hi, v[i]); }
            for (int i = seg[s]; i < seg[s + 1]; i++) mir[i] = hi + lo - v[i];
        }
        applyGamma("C) curve REVERSED: should look like a POSITIVE / inverted (6 s)", rev);
        restoreGamma();
        applyGamma("D) curve MIRRORED: should look INVERTED, different tones (6 s)", mir);
        restoreGamma();
    }

    private void applyGamma(String label, int[] values) throws InterruptedException {
        try {
            CameraEx.GammaTable t = cameraEx.createGammaTable();
            byte[] b = toBytes(values, origGamma);
            int size = t.getSize();
            if (size < b.length) { out("    " + label.substring(0, 2) + " skipped: new table size " + size + " < " + b.length); return; }
            t.write(b);
            cameraEx.setExtendedGammaTable(t);
            gammaChanged = true;
            out("    " + label + " -> set, readback matches=" + readbackMatches(b));
            setStatus(LIVE, label);
            Thread.sleep(6000);
        } catch (InterruptedException e) {
            throw e;
        } catch (Throwable t) {
            err(label.substring(0, 2) + " setExtendedGammaTable", t);
        }
    }

    private String readbackMatches(byte[] expect) {
        try {
            CameraEx.GammaTable g = cameraEx.getExtendedGammaTable();
            if (g == null) return "null";
            byte[] got = new byte[g.getSize()];
            g.read(got);
            int n = Math.min(got.length, expect.length);
            for (int i = 0; i < n; i++) if (got[i] != expect[i]) return "NO (first diff at byte " + i + ")";
            return "yes";
        } catch (Throwable t) { return "error " + t; }
    }

    // ------------------------------------------------------------------ restore (also on exit / crash)
    private synchronized void restoreMatrix() {
        if (!matrixChanged || cameraEx == null) return;
        try {
            writeMatrix(origMatrix);
            out("    matrix restored, readback " + arr(readMatrix()));
        } catch (Throwable t) { err("restore matrix", t); }
        matrixChanged = false;
    }

    private synchronized void restoreGamma() {
        if (!gammaChanged || cameraEx == null) return;
        try {
            if (origGammaWasNull) {
                cameraEx.setExtendedGammaTable(null);
                out("    gamma restored to none (null)");
            } else {
                CameraEx.GammaTable t = cameraEx.createGammaTable();
                t.write(origGamma);
                cameraEx.setExtendedGammaTable(t);
                out("    gamma restored, readback matches=" + readbackMatches(origGamma));
            }
        } catch (Throwable t) { err("restore gamma", t); }
        gammaChanged = false;
    }

    private synchronized void restoreCamera() {
        restoreMatrix();
        restoreGamma();
        if (cameraEx != null) {
            try { cameraEx.getNormalCamera().setPreviewCallback(null); } catch (Throwable ignored) {}
            try { cameraEx.getNormalCamera().stopPreview(); } catch (Throwable ignored) {}
            try { cameraEx.release(); } catch (Throwable t) { err("camera release", t); }
            cameraEx = null;
        }
    }

    // ------------------------------------------------------------------ helpers
    /** RGB matrix and the gamma-support flag live on the camera parameters (via Sony's modifier). */
    private CameraEx.ParametersModifier modifier(Camera.Parameters p) {
        return cameraEx.createParametersModifier(p);
    }

    private int[] readMatrix() {
        return modifier(cameraEx.getNormalCamera().getParameters()).getRGBMatrix();
    }

    private void writeMatrix(int[] m) {
        Camera cam = cameraEx.getNormalCamera();
        Camera.Parameters p = cam.getParameters();
        modifier(p).setRGBMatrix(m);
        cam.setParameters(p);
    }

    private static int[] u16(byte[] b) {
        int[] v = new int[b.length / 2];
        for (int i = 0; i < v.length; i++) v[i] = (b[2 * i] & 255) | ((b[2 * i + 1] & 255) << 8);
        return v;
    }

    /** Values back to bytes, keeping any odd trailing byte of the original. */
    private static byte[] toBytes(int[] v, byte[] orig) {
        byte[] b = orig.clone();
        for (int i = 0; i < v.length; i++) { b[2 * i] = (byte) v[i]; b[2 * i + 1] = (byte) (v[i] >> 8); }
        return b;
    }

    /** Segment starts where the curve restarts (a big drop), e.g. separate R/G/B curves; ends with n. */
    private static int[] segments(int[] v) {
        int max = 0;
        for (int x : v) max = Math.max(max, x);
        int[] tmp = new int[v.length + 1];
        int k = 0;
        tmp[k++] = 0;
        for (int i = 1; i < v.length; i++) if (v[i] < v[i - 1] - max / 2) tmp[k++] = i;
        tmp[k++] = v.length;
        int[] out = new int[k];
        System.arraycopy(tmp, 0, out, 0, k);
        return out;
    }

    private void saveBytes(String name, byte[] b) {
        try {
            File f = new File(Environment.getExternalStorageDirectory(), name);
            FileOutputStream fo = new FileOutputStream(f);
            try { fo.write(b); } finally { fo.close(); }
            out("    saved " + f.getAbsolutePath());
        } catch (Throwable t) { err("save " + name, t); }
    }

    private static String arr(int[] a) {
        if (a == null) return "null";
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) s.append(i > 0 ? ", " : "").append(a[i]);
        return s.append(']').toString();
    }

    // ------------------------------------------------------------------ input / lifecycle
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        int scan = e.getScanCode();
        if (!(keyCode == 0 && scan == 528)) out("key " + keyCode + " scan " + scan);   // 528 = dial, very chatty
        boolean menu = isMenuOrBack(scan) || keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BACK;
        boolean enter = isEnter(scan) || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
        if (menu) { onMenuAction(); return true; }
        if (enter) { onEnterAction(); return true; }
        return super.onKeyDown(keyCode, e);
    }

    private void onMenuAction() {
        long t = System.currentTimeMillis();
        if (state == READY || t - lastMenuPress < 3000) {
            out("exit requested");
            finish();
        } else {
            lastMenuPress = t;
            final String prev = status.getText().toString();
            status.setText("Still working! Press MENU / Exit again within 3 s to quit anyway");
            ui.postDelayed(new Runnable() {
                public void run() { if (state != READY) status.setText(prev); }
            }, 3000);
        }
    }

    private void onEnterAction() {
        if (state == READY) {
            state = LIVE;
            new Thread(new Runnable() { public void run() { liveViewTests(); } }).start();
        } else {
            out("centre/button ignored: still working");
        }
    }

    private int touchLogs;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int a = ev.getAction() & MotionEvent.ACTION_MASK;
        if ((a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP) && touchLogs < 200) {
            touchLogs++;
            out("touch " + (a == MotionEvent.ACTION_DOWN ? "down" : "up") + " x=" + (int) ev.getX() + " y=" + (int) ev.getY()
                    + " pointers=" + ev.getPointerCount());
        }
        return super.dispatchTouchEvent(ev);
    }

    public void surfaceCreated(SurfaceHolder h) { surfaceReady = true; }
    public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
    public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; }

    @Override
    protected void onPause() {
        restoreCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        restoreCamera();
        out("=== exit, " + now() + " ===");
        if (log != null) log.close();
        super.onDestroy();
    }
}

package com.filminverter;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.filminverter.engine.NegEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;

/**
 * M0 probe app. Runs the feasibility checks from the plan on the real camera and
 * writes everything to /sdcard/FIPROBE.TXT (camera card root: FIPROBE.TXT).
 *
 * On launch (safe, read-mostly):
 *   1. device + memory info (heap limit decides tiling strategy)
 *   2. Sony API reconnaissance: which com.sony.scalar classes exist, their methods
 *   3. listing of /system/app and /system/framework (Sony built-in apps to decompile)
 *   4. decode benchmark on the newest JPEG in DCIM (full / 1/2 / 1/4, region decoder)
 *   5. real inversion of that JPEG with NegEngine (auto base) -> DCIM/FITEST/*_POS.JPG
 *      (then check whether the camera's own playback shows the file)
 * On pressing the centre/enter button:
 *   6. live preview-frame probe via android.hardware.Camera (risky; separate on purpose)
 * MENU or BACK exits.
 */
public class MainActivity extends SonyActivity implements SurfaceHolder.Callback {

    private TextView text;
    private ScrollView scroll;
    private SurfaceView surface;
    private PrintWriter log;
    private final Handler ui = new Handler();
    private volatile boolean surfaceReady;

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
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        surface.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS); // needed on API 10
        root.addView(surface, new FrameLayout.LayoutParams(160, 120));
        scroll = new ScrollView(this);
        text = new TextView(this);
        text.setTextSize(11);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(0xC0000000);
        scroll.addView(text);
        root.addView(scroll);
        setContentView(root);

        out("=== Film Inverter probe " + new java.util.Date() + " ===");
        new Thread(new Runnable() { public void run() { runSafeProbes(); } }).start();
    }

    // ------------------------------------------------------------------ logging
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

    /**
     * Any crash the probes do not catch themselves (UI thread, lifecycle, a missing Sony class) is
     * written to FIPROBE.TXT with its stack trace before the app dies, then handed to the default handler.
     */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread th, Throwable t) {
                try {
                    PrintWriter w = log != null ? log : new PrintWriter(new FileWriter(
                            new File(Environment.getExternalStorageDirectory(), "FIPROBE.TXT"), true));
                    w.println("!! CRASH in thread " + th.getName() + " at " + new java.util.Date() + ": " + t);
                    t.printStackTrace(w);
                    w.flush();
                } catch (Throwable ignored) {}
                if (prev != null) prev.uncaughtException(th, t);
            }
        });
    }

    // ------------------------------------------------------------------ probes
    private void runSafeProbes() {
        try { probeDevice(); } catch (Throwable t) { err("device", t); }
        try { probeSonyApi(); } catch (Throwable t) { err("sony api", t); }
        try { probeSystemDirs(); } catch (Throwable t) { err("system dirs", t); }
        File jpg = null;
        try { jpg = newestJpeg(); } catch (Throwable t) { err("find jpeg", t); }
        if (jpg == null) {
            out("No JPEG found in DCIM - shoot a frame (RAW+JPEG) and relaunch for steps 4-5.");
        } else {
            try { probeDecode(jpg); } catch (Throwable t) { err("decode", t); }
            try { invertFile(jpg); } catch (Throwable t) { err("invert", t); }
        }
        out("--- safe probes done. Press centre/enter for the live-preview probe, MENU to exit. ---");
    }

    private void probeDevice() {
        Runtime rt = Runtime.getRuntime();
        out("[1] model=" + Build.MODEL + " device=" + Build.DEVICE + " android=" + Build.VERSION.RELEASE
                + " sdk=" + Build.VERSION.SDK_INT);
        out("    maxMemory=" + rt.maxMemory() / 1048576 + "MB total=" + rt.totalMemory() / 1048576
                + "MB free=" + rt.freeMemory() / 1048576 + "MB cpus=" + rt.availableProcessors());
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            out("    memoryClass=" + am.getMemoryClass() + "MB");
        } catch (Throwable t) { err("memoryClass", t); }
        out("    external storage=" + Environment.getExternalStorageDirectory());
    }

    private static final String[] SONY_CANDIDATES = {
        "com.sony.scalar.hardware.CameraEx",
        "com.sony.scalar.hardware.CameraSequence",
        "com.sony.scalar.hardware.DeviceBuffer",
        "com.sony.scalar.hardware.avio.DisplayManager",
        "com.sony.scalar.sysutil.ScalarInput",
        "com.sony.scalar.sysutil.didep.Settings",
        "com.sony.scalar.media.AvindexStore",
        "com.sony.scalar.graphics.OptimizedImage",
        "com.sony.scalar.graphics.OptimizedImageFactory",
        "com.sony.scalar.graphics.ImageAnalyzer",
        "com.sony.scalar.graphics.imagefilter.ScaleImageFilter",
        "com.sony.scalar.provider.AvindexStore",
        // listed by up209d/open-memories-app-ai (unverified - the probe confirms or refutes):
        "com.sony.scalar.hardware.DSP",
        "com.sony.scalar.graphics.JpegExporter",
        "com.sony.scalar.graphics.ImageFilter",
        "com.sony.scalar.meta.Histogram",
        "com.sony.scalar.meta.TakenPhotoInfo",
        "com.sony.scalar.sysutil.ScalarProperties",
        "com.sony.scalar.widget.OptimizedImageView",
    };

    /** Dump which Sony classes exist and every method on them (plus nested classes). */
    private void probeSonyApi() {
        out("[2] Sony API reconnaissance");
        for (String name : SONY_CANDIDATES) {
            try {
                Class<?> c = Class.forName(name);
                out("  FOUND " + name);
                dumpClass(c, "    ", 0);
            } catch (ClassNotFoundException e) {
                out("  missing " + name);
            } catch (Throwable t) {
                err(name, t);
            }
        }
    }

    private void dumpClass(Class<?> c, String indent, int depth) {
        Method[] ms = c.getDeclaredMethods();
        Arrays.sort(ms, new Comparator<Method>() {
            public int compare(Method a, Method b) { return a.getName().compareTo(b.getName()); }
        });
        for (Method m : ms) {
            StringBuilder s = new StringBuilder(indent).append(m.getReturnType().getSimpleName()).append(' ')
                    .append(m.getName()).append('(');
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) s.append(i > 0 ? ", " : "").append(ps[i].getSimpleName());
            out(s.append(')').toString());
        }
        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                out(indent + "const " + f.getType().getSimpleName() + " " + f.getName());
        }
        if (depth < 2) {
            for (Class<?> inner : c.getDeclaredClasses()) {
                out(indent + "class " + inner.getName());
                dumpClass(inner, indent + "  ", depth + 1);
            }
        }
    }

    private void probeSystemDirs() {
        String[] dirs = {"/system/app", "/system/framework", "/android/system/app"};
        for (String d : dirs) {
            File[] fs = new File(d).listFiles();
            out("[3] " + d + ": " + (fs == null ? "(not readable)" : fs.length + " entries"));
            if (fs != null) {
                Arrays.sort(fs);
                for (File f : fs) out("    " + f.getName() + "  " + f.length());
            }
        }
    }

    private File newestJpeg() {
        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
        File best = null;
        File[] sub = dcim.listFiles();
        if (sub == null) return null;
        for (File d : sub) {
            if (!d.isDirectory() || d.getName().equals("FITEST")) continue;
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName().toUpperCase();
                if (n.endsWith(".JPG") && (best == null || f.lastModified() > best.lastModified())) best = f;
            }
        }
        return best;
    }

    private void probeDecode(File jpg) {
        out("[4] decode benchmark on " + jpg.getAbsolutePath() + " (" + jpg.length() / 1024 + " KB)");
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(jpg.getAbsolutePath(), o);
        out("    size " + o.outWidth + "x" + o.outHeight);
        int[] samples = {4, 2, 1};
        for (int s : samples) {
            System.gc();
            long t = System.currentTimeMillis();
            try {
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = s;
                Bitmap bm = BitmapFactory.decodeFile(jpg.getAbsolutePath(), d);
                long ms = System.currentTimeMillis() - t;
                out("    inSampleSize=" + s + " -> " + bm.getWidth() + "x" + bm.getHeight() + " in " + ms + " ms");
                bm.recycle();
            } catch (OutOfMemoryError e) {
                out("    inSampleSize=" + s + " -> OUT OF MEMORY after " + (System.currentTimeMillis() - t) + " ms");
            }
        }
        try {
            long t = System.currentTimeMillis();
            BitmapRegionDecoder rd = BitmapRegionDecoder.newInstance(jpg.getAbsolutePath(), false);
            Bitmap strip = rd.decodeRegion(new Rect(0, 0, rd.getWidth(), 256), null);
            out("    region decoder: " + rd.getWidth() + "x256 strip in " + (System.currentTimeMillis() - t) + " ms");
            strip.recycle();
            rd.recycle();
        } catch (Throwable e) { err("region decoder", e); }
    }

    /**
     * Real inversion at 1/2 size (falls back to 1/4): auto base from the histogram,
     * per-frame levels, LUT apply, JPEG out. Full-res strip processing comes in M2.
     */
    private void invertFile(File jpg) throws Exception {
        out("[5] invert " + jpg.getName());
        long t0 = System.currentTimeMillis();
        Bitmap bm = null;
        int[] samples = {2, 4};
        for (int s : samples) {
            try {
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = s;
                d.inPreferredConfig = Bitmap.Config.ARGB_8888;
                bm = BitmapFactory.decodeFile(jpg.getAbsolutePath(), d);
                out("    decoded at 1/" + s);
                break;
            } catch (OutOfMemoryError e) {
                System.gc();
            }
        }
        if (bm == null) { out("    could not decode even at 1/4"); return; }
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        long t1 = System.currentTimeMillis();

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

        Bitmap outBm = bm.isMutable() ? bm : bm.copy(Bitmap.Config.ARGB_8888, true);
        if (outBm != bm) bm.recycle();
        outBm.setPixels(px, 0, w, 0, 0, w, h);
        File dir = new File(Environment.getExternalStorageDirectory(), "DCIM/FITEST");
        dir.mkdirs();
        File dst = new File(dir, jpg.getName().replaceAll("(?i)\\.JPG$", "") + "_POS.JPG");
        FileOutputStream fo = new FileOutputStream(dst);
        outBm.compress(Bitmap.CompressFormat.JPEG, 92, fo);
        fo.close();
        outBm.recycle();
        long t4 = System.currentTimeMillis();
        out("    decode " + (t1 - t0) + " ms, profile " + (t2 - t1) + " ms, apply " + (t3 - t2)
                + " ms, encode+write " + (t4 - t3) + " ms");
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

    // ------------------------------------------------------------------ live preview probe
    private void probePreview() {
        out("[6] preview-frame probe (android.hardware.Camera)");
        if (!surfaceReady) { out("    surface not ready yet, try again"); return; }
        Camera cam = null;
        try {
            cam = Camera.open();
            Camera.Parameters prm = cam.getParameters();
            out("    preview size=" + prm.getPreviewSize().width + "x" + prm.getPreviewSize().height
                    + " format=" + prm.getPreviewFormat());
            out("    params: " + prm.flatten());
            cam.setPreviewDisplay(surface.getHolder());
            final int[] frames = {0};
            final long[] firstLen = {0};
            cam.setPreviewCallback(new Camera.PreviewCallback() {
                public void onPreviewFrame(byte[] data, Camera c) {
                    if (frames[0] == 0 && data != null) firstLen[0] = data.length;
                    frames[0]++;
                }
            });
            cam.startPreview();
            Thread.sleep(3000);
            cam.setPreviewCallback(null);
            cam.stopPreview();
            out("    frames in 3 s: " + frames[0] + " (first buffer " + firstLen[0] + " bytes)");
            out(frames[0] > 0 ? "    -> live frames ARE available to apps: software inverted preview is possible"
                              : "    -> no frames: try CameraEx / Multiple Exposure blend route");
        } catch (Throwable t) {
            err("preview probe", t);
        } finally {
            if (cam != null) try { cam.release(); } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------ input / lifecycle
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        // Camera buttons are matched by Sony scan code; Android key codes are a fallback (emulator/desktop).
        int scan = e.getScanCode();
        out("key " + keyCode + " scan " + scan);
        if (isMenuOrBack(scan) || keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        if (isEnter(scan) || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            new Thread(new Runnable() { public void run() { probePreview(); } }).start();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    public void surfaceCreated(SurfaceHolder h) { surfaceReady = true; }
    public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
    public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; }

    @Override
    protected void onDestroy() {
        if (log != null) log.close();
        super.onDestroy();
    }
}

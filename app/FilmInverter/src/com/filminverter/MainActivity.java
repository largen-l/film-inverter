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
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
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
 * M0 probe, version 3. Everything is logged to FIPROBE.TXT on the card root.
 *
 * On launch (yellow status bar = WORKING, buttons ignored until green DONE):
 *   [1] device, heap, Sony time   [2] Sony classes
 *   [3] invert the newest RAW+JPEG pair's JPEG -> /FILMINV/<name>_POS.JPG (camera refuses new DCIM folders)
 * Centre (or the "Curves" button) when READY opens the CURVE EXPLORER (live view):
 *   Probe 2 showed CameraEx.setExtendedGammaTable() changes live view instantly, but the 2048-byte table
 *   format is unknown (the camera has no table by default; a new one holds leftover memory). The explorer
 *   builds clean curves in several candidate formats:
 *     LEFT/RIGHT or dials (or < > buttons)  = previous / next format
 *     CENTRE (or "Normal/Inverted")         = toggle plain (identity) vs inverted curve
 *     UP (or "Camera default")              = remove our table (camera's own curve)
 *     MENU (or "Back")                      = leave the explorer, then MENU again exits
 *   The format whose PLAIN curve looks exactly like normal live view is the right one.
 * Every change is logged with a timestamp. Tables are released after use (probe 2 never released them,
 * a suspect for the freeze on exit). Exit steps are logged so a hang shows where it happened.
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

    // curve explorer state; restoreCamera() undoes everything
    private CameraEx cameraEx;
    private CameraEx.GammaTable activeTable;
    private int candidate = 0;
    private boolean inverted = false;
    private boolean usingDefault = true;

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
        status.setTextSize(15);
        status.setTextColor(Color.BLACK);
        status.setGravity(Gravity.CENTER);
        status.setPadding(4, 4, 4, 4);
        overlay.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        scroll = new ScrollView(this);
        text = new TextView(this);
        text.setTextSize(11);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(0xC0000000);
        scroll.addView(text);
        overlay.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Touch test + touch controls: same actions as the keys. Every touch is logged (dispatchTouchEvent),
        // so the log tells whether the a6500 passes touches to apps at all.
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        addButton(buttons, "<", new Runnable() { public void run() { onPrevAction(); } });
        addButton(buttons, "Curves / Normal-Inv", new Runnable() { public void run() { onEnterAction(); } });
        addButton(buttons, ">", new Runnable() { public void run() { onNextAction(); } });
        addButton(buttons, "Camera default", new Runnable() { public void run() { onUpAction(); } });
        addButton(buttons, "Back / Exit", new Runnable() { public void run() { onMenuAction(); } });
        overlay.addView(buttons, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(overlay);
        setContentView(root);

        setStatus(BUSY, "WORKING... please wait");
        out("=== Film Inverter probe 3, " + now() + " ===");
        new Thread(new Runnable() { public void run() { runSafeProbes(); } }).start();
    }

    private void addButton(LinearLayout row, final String label, final Runnable action) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(12);
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { out("touch button: " + label); action.run(); }
        });
        row.addView(btn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
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

    /** Big status bar at the top. Colour: yellow = working, green = ready, cyan = curve explorer. */
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
        setStatus(BUSY, "WORKING 3/3: inverting newest RAW+JPEG (~10 s)");
        try {
            File jpg = pickJpeg();
            if (jpg != null) invertFile(jpg);
        } catch (Throwable t) { err("invert", t); }
        out("--- safe probes done ---");
        setStatus(READY, "DONE. Centre = curve explorer, MENU = exit");
    }

    private void probeDevice() {
        Runtime rt = Runtime.getRuntime();
        out("[1] model=" + Build.MODEL + " android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT
                + " maxMemory=" + rt.maxMemory() / 1048576 + "MB  sony time=" + now());
    }

    private static final String[] SONY_CLASSES = {
        "com.sony.scalar.hardware.CameraEx",
        "com.sony.scalar.hardware.CameraEx$GammaTable",
        "com.sony.scalar.provider.AvindexStore",
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
        out("[3] DCIM canWrite=" + dcim.canWrite());
        File best = null;
        File[] sub = dcim.listFiles();
        if (sub != null) {
            for (File d : sub) {
                if (!d.isDirectory()) continue;
                File[] fs = d.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    String n = f.getName();
                    if (!n.toUpperCase().endsWith(".JPG")) continue;
                    File arw = new File(d, n.substring(0, n.length() - 4) + ".ARW");
                    if (arw.exists() && (best == null || f.lastModified() > best.lastModified())) best = f;
                }
            }
        }
        if (best == null) {
            out("    NO RAW+JPEG pair found. Set Quality = RAW+JPEG, shoot a negative, relaunch.");
            return null;
        }
        out("    newest RAW+JPEG pair: " + best.getAbsolutePath());
        return best;
    }

    /** Inversion at 1/4 size (1/2 does not fit the 24 MB heap). Output outside DCIM, a new file only. */
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

        NegEngine.Profile p = new NegEngine.Profile();
        p.base = autoBase(px);
        out("    decoded " + w + "x" + h + " in " + (t1 - t0) + " ms; auto base (linear) = " + fmt(p.base));
        NegEngine.Histogram hist = new NegEngine.Histogram(p);
        hist.add(px, 0, px.length, 4);
        NegEngine.levelsFromHistogram(p, hist);
        int[][] lut = NegEngine.buildLuts(p);
        NegEngine.apply(px, 0, px.length, lut, p.film);
        long t2 = System.currentTimeMillis();

        Bitmap outBm = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        px = null;
        File dir = new File(Environment.getExternalStorageDirectory(), "FILMINV");
        boolean made = dir.mkdirs();
        out("    output folder " + dir.getAbsolutePath() + " exists=" + dir.isDirectory() + " (mkdirs " + made + ")");
        File dst = new File(dir, jpg.getName().replaceAll("(?i)\\.JPG$", "") + "_POS.JPG");
        FileOutputStream fo = new FileOutputStream(dst);
        try { outBm.compress(Bitmap.CompressFormat.JPEG, 92, fo); } finally { fo.close(); }
        outBm.recycle();
        long t3 = System.currentTimeMillis();
        out("    invert " + (t2 - t1) + " ms, encode+write " + (t3 - t2) + " ms -> " + dst.getAbsolutePath());
        out("    profile " + NegEngine.toJson(p));
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

    // ------------------------------------------------------------------ curve explorer
    /**
     * Candidate layouts for the 2048-byte table. Each fills the whole table with one or more curves.
     * curves x entries, bytes per entry, big-endian?, interleaved?, output maximum.
     */
    private static final int[][] CAND = {
        // curves, entries, bytes, bigEndian, interleaved, outMax
        {1, 1024, 2, 0, 0, 65535},
        {1, 1024, 2, 0, 0, 4095},
        {1, 1024, 2, 0, 0, 1023},
        {1, 1024, 2, 1, 0, 65535},
        {1, 1024, 2, 1, 0, 4095},
        {4, 256, 2, 0, 0, 65535},
        {4, 256, 2, 0, 0, 4095},
        {4, 256, 2, 0, 0, 1023},
        {4, 256, 2, 0, 1, 4095},
        {4, 256, 2, 0, 1, 65535},
        {1, 512, 4, 0, 0, 65535},
        {1, 512, 4, 0, 0, 4095},
        {1, 2048, 1, 0, 0, 255},
    };

    private static String describe(int c) {
        int[] k = CAND[c];
        String bits = k[5] == 65535 ? "16-bit" : k[5] == 4095 ? "12-bit" : k[5] == 1023 ? "10-bit" : "8-bit";
        String layout = k[0] == 1 ? "1 curve x " + k[1] : k[0] + " curves x " + k[1] + (k[4] == 1 ? " interleaved" : " planar");
        String type = k[2] == 1 ? "u8" : k[2] == 2 ? (k[3] == 1 ? "u16 BE" : "u16 LE") : "u32 LE";
        return (c + 1) + "/" + CAND.length + "  " + layout + ", " + type + ", " + bits;
    }

    private static byte[] buildTable(int c, boolean invert, int size) {
        int[] k = CAND[c];
        int curves = k[0], entries = k[1], bpe = k[2], max = k[5];
        boolean be = k[3] == 1, inter = k[4] == 1;
        byte[] b = new byte[size];
        int total = Math.min(curves * entries, size / bpe);
        for (int e = 0; e < total; e++) {
            int i = inter ? e / curves : e % entries;           // position along the curve
            double x = i / (double) (entries - 1);
            double y = invert ? 1.0 - x : x;
            long v = Math.round(y * max);
            int o = e * bpe;
            for (int j = 0; j < bpe; j++) {
                int shift = be ? 8 * (bpe - 1 - j) : 8 * j;
                b[o + j] = (byte) (v >> shift);
            }
        }
        return b;
    }

    private void startExplorer() {
        out("[4] curve explorer, " + now());
        showLog(false);
        try {
            if (!surfaceReady) { out("    surface not ready"); setStatus(READY, "Surface not ready, try again"); showLog(true); return; }
            cameraEx = CameraEx.open(0, null);
            Camera cam = cameraEx.getNormalCamera();
            cam.setPreviewDisplay(surface.getHolder());
            cam.startPreview();
            try {
                Camera.Parameters prm = cam.getParameters();
                out("    gamma supported=" + cameraEx.createParametersModifier(prm).isExtendedGammaTableSupported()
                        + ", current table=" + (cameraEx.getExtendedGammaTable() == null ? "none" : "set"));
            } catch (Throwable t) { err("gamma query", t); }
            usingDefault = true;
            setStatus(LIVE, "CAMERA DEFAULT curve.  Centre = start formats, < > = format, MENU = back");
            out("    showing camera default, " + now());
        } catch (Throwable t) {
            err("start explorer", t);
            restoreCamera();
            showLog(true);
            setStatus(READY, "Camera open failed (see log). MENU = exit");
        }
    }

    /** Build and apply the current candidate; release the previous table once it is no longer in use. */
    private synchronized void applyCandidate() {
        if (cameraEx == null) return;
        try {
            CameraEx.GammaTable t = cameraEx.createGammaTable();
            int size = t.getSize();
            t.write(buildTable(candidate, inverted, size));
            cameraEx.setExtendedGammaTable(t);
            CameraEx.GammaTable old = activeTable;
            activeTable = t;
            usingDefault = false;
            if (old != null) { try { old.release(); } catch (Throwable r) { err("release old table", r); } }
            String d = describe(candidate) + (inverted ? "  INVERTED" : "  PLAIN");
            out("    " + now() + "  " + d + "  (table " + size + " bytes)");
            setStatus(LIVE, d + (inverted ? "" : "  - normal-looking?"));
        } catch (Throwable t) {
            err("apply candidate " + (candidate + 1), t);
        }
    }

    private synchronized void useCameraDefault() {
        if (cameraEx == null) return;
        try {
            cameraEx.setExtendedGammaTable(null);
            if (activeTable != null) { activeTable.release(); activeTable = null; }
            usingDefault = true;
            out("    " + now() + "  camera default curve");
            setStatus(LIVE, "CAMERA DEFAULT curve.  < > = format, Centre = plain/inverted, MENU = back");
        } catch (Throwable t) { err("camera default", t); }
    }

    /** Undo everything: our table off and released, preview stopped, camera released. Safe to call twice. */
    private synchronized void restoreCamera() {
        if (cameraEx == null) return;
        long t0 = System.currentTimeMillis();
        try { cameraEx.setExtendedGammaTable(null); } catch (Throwable t) { err("restore: table off", t); }
        if (activeTable != null) {
            try { activeTable.release(); } catch (Throwable t) { err("restore: release table", t); }
            activeTable = null;
        }
        try { cameraEx.getNormalCamera().stopPreview(); } catch (Throwable t) { err("restore: stopPreview", t); }
        try { cameraEx.release(); } catch (Throwable t) { err("restore: camera release", t); }
        cameraEx = null;
        out("    camera restored and released in " + (System.currentTimeMillis() - t0) + " ms, " + now());
    }

    // ------------------------------------------------------------------ input (keys and touch share these)
    private void onEnterAction() {
        if (state == READY) {
            state = LIVE;
            new Thread(new Runnable() { public void run() { startExplorer(); } }).start();
        } else if (state == LIVE) {
            if (!usingDefault) inverted = !inverted;
            applyCandidate();
        } else {
            out("centre ignored: still working");
        }
    }

    private void onNextAction() {
        if (state != LIVE) return;
        candidate = (candidate + 1) % CAND.length;
        applyCandidate();
    }

    private void onPrevAction() {
        if (state != LIVE) return;
        candidate = (candidate + CAND.length - 1) % CAND.length;
        applyCandidate();
    }

    private void onUpAction() {
        if (state == LIVE) useCameraDefault();
    }

    private void onMenuAction() {
        if (state == LIVE) {                     // leave the explorer, stay in the app
            out("leaving curve explorer");
            restoreCamera();
            showLog(true);
            setStatus(READY, "DONE. Centre = curve explorer again, MENU = exit");
            return;
        }
        long t = System.currentTimeMillis();
        if (state == READY || t - lastMenuPress < 3000) {
            out("exit requested, " + now());
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        int scan = e.getScanCode();
        boolean dial = keyCode == 0 && scan == 528;
        if (!dial) out("key " + keyCode + " scan " + scan);
        if (isMenuOrBack(scan) || keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BACK) { onMenuAction(); return true; }
        if (isEnter(scan) || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) { onEnterAction(); return true; }
        if (isNext(scan) || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { onNextAction(); return true; }
        if (isPrev(scan) || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { onPrevAction(); return true; }
        if (isUp(scan) || keyCode == KeyEvent.KEYCODE_DPAD_UP) { onUpAction(); return true; }
        return super.onKeyDown(keyCode, e);
    }

    private int touchLogs;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int a = ev.getAction() & MotionEvent.ACTION_MASK;
        if ((a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP) && touchLogs < 200) {
            touchLogs++;
            out("touch " + (a == MotionEvent.ACTION_DOWN ? "down" : "up") + " x=" + (int) ev.getX() + " y=" + (int) ev.getY());
        }
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------------ lifecycle (logged: probe 2 hung on exit)
    public void surfaceCreated(SurfaceHolder h) { surfaceReady = true; }
    public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
    public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; }

    @Override
    protected void onPause() {
        out("onPause, " + now());
        restoreCamera();
        super.onPause();
        out("onPause done");
    }

    @Override
    protected void onStop() {
        out("onStop, " + now());
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        out("onDestroy, " + now());
        restoreCamera();
        out("=== exit ===");
        if (log != null) log.close();
        super.onDestroy();
    }
}

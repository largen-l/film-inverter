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
import android.widget.SeekBar;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M0 probe, version 4. Everything is logged to FIPROBE.TXT on the card root.
 *
 * Probe 3 found the extended gamma table format: 1 curve x 1024 entries, u16 little-endian, 0..1023.
 *
 * On launch (yellow = WORKING, green = DONE):
 *   [1] device  [2] filename test: does the card accept long names, or only 8.3?
 *   [3] invert the newest RAW+JPEG pair -> /FILMINV/<same 8.3 name>.JPG
 * Centre (or "Live view") when READY opens INVERTED LIVE VIEW with adjustable rows:
 *   UP / DOWN (or Row buttons)         = choose a row
 *   LEFT / RIGHT / dials, or the slider = change the row's value
 *   CENTRE (or "Pos/Neg")              = positive (inverted curve) <-> negative (plain curve)
 *   SHUTTER (or "Capture")             = take a picture with the current curve: is the JPEG a positive?
 *   MENU (or "Back")                   = leave live view (everything restored), MENU again = exit
 * Rows: curve top (headroom against the yellow speckles), colour temperature, WB shift amber-blue,
 *       WB shift green-magenta (to cancel the orange mask), RGB matrix identity scale test.
 * Every camera setting touched is saved first and restored (and read back) when leaving live view.
 */
public class MainActivity extends SonyActivity implements SurfaceHolder.Callback {

    private static final int BUSY = 0, READY = 1, LIVE = 2;

    private TextView status;
    private TextView rowLabel;
    private SeekBar slider;
    private LinearLayout rowPanel;
    private TextView text;
    private ScrollView scroll;
    private SurfaceView surface;
    private PrintWriter log;
    private final Handler ui = new Handler();
    private volatile boolean surfaceReady;
    private volatile int state = BUSY;
    private long lastMenuPress;
    private boolean sliderUpdating;

    // live view state; restoreCamera() undoes everything
    private CameraEx cameraEx;
    private CameraEx.GammaTable activeTable;
    private final Map<String, String> savedParams = new LinkedHashMap<String, String>();
    private boolean positive = true;

    // ------------------------------------------------------------------ adjustable rows
    private static final int ROW_CURVE = 0, ROW_TEMP = 1, ROW_AB = 2, ROW_GM = 3, ROW_MATRIX = 4;
    private static final String[] ROW_NAMES = {"Curve top", "Colour temp", "WB amber-blue", "WB green-magenta", "Matrix scale test"};
    private static final int[] CURVE_TOPS = {1023, 1008, 992, 960, 900, 800};
    private static final int[] MATRIX_SCALES = {0, 1, 64, 100, 128, 256, 512, 1000, 1024, 4096};   // 0 = matrix off
    private int row = ROW_CURVE;
    private int curveIdx = 2;          // 992: a little headroom by default
    private int temp = 5500;           // 2500..9900 step 100
    private int shiftAB = 0, shiftGM = 0;   // -32..32
    private int matrixIdx = 0;
    private boolean wbTouched, shiftTouched, matrixTouched;

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
        status.setTextSize(14);
        status.setTextColor(Color.BLACK);
        status.setGravity(Gravity.CENTER);
        status.setPadding(4, 2, 4, 2);
        overlay.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // row panel: label + touch slider (hidden until live view)
        rowPanel = new LinearLayout(this);
        rowPanel.setOrientation(LinearLayout.HORIZONTAL);
        rowPanel.setBackgroundColor(0xA0000000);
        rowLabel = new TextView(this);
        rowLabel.setTextSize(14);
        rowLabel.setTextColor(Color.WHITE);
        rowLabel.setPadding(6, 0, 6, 0);
        rowPanel.addView(rowLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        slider = new SeekBar(this);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && !sliderUpdating) { setRowValueFromSlider(p); }
            }
            public void onStartTrackingTouch(SeekBar s) { out("touch: slider start"); }
            public void onStopTrackingTouch(SeekBar s) { out("touch: slider stop at " + s.getProgress()); }
        });
        rowPanel.addView(slider, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
        rowPanel.setVisibility(View.GONE);
        overlay.addView(rowPanel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        text = new TextView(this);
        text.setTextSize(11);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(0xC0000000);
        scroll.addView(text);
        overlay.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        addButton(buttons, "Row up", new Runnable() { public void run() { onUpAction(); } });
        addButton(buttons, "Row down", new Runnable() { public void run() { onDownAction(); } });
        addButton(buttons, "<", new Runnable() { public void run() { onPrevAction(); } });
        addButton(buttons, ">", new Runnable() { public void run() { onNextAction(); } });
        addButton(buttons, "Live view / Pos-Neg", new Runnable() { public void run() { onEnterAction(); } });
        addButton(buttons, "Capture", new Runnable() { public void run() { onShutterAction(); } });
        addButton(buttons, "Back / Exit", new Runnable() { public void run() { onMenuAction(); } });
        overlay.addView(buttons, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(overlay);
        setContentView(root);

        setStatus(BUSY, "WORKING... please wait");
        out("=== Film Inverter probe 4, " + now() + " ===");
        new Thread(new Runnable() { public void run() { runSafeProbes(); } }).start();
    }

    private void addButton(LinearLayout row, final String label, final Runnable action) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(11);
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

    /** Status bar. Colour: yellow = working, green = ready, cyan = live view. */
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

    private void showLive(final boolean live) {
        ui.post(new Runnable() {
            public void run() {
                scroll.setVisibility(live ? View.GONE : View.VISIBLE);
                rowPanel.setVisibility(live ? View.VISIBLE : View.GONE);
            }
        });
    }

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
        out("[1] model=" + Build.MODEL + " android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT
                + " maxMemory=" + Runtime.getRuntime().maxMemory() / 1048576 + "MB  sony time=" + now());
        setStatus(BUSY, "WORKING 2/3: filename test");
        try { filenameTest(); } catch (Throwable t) { err("filename test", t); }
        setStatus(BUSY, "WORKING 3/3: inverting newest RAW+JPEG (~10 s)");
        try {
            File jpg = pickJpeg();
            if (jpg != null) invertFile(jpg);
        } catch (Throwable t) { err("invert", t); }
        out("--- safe probes done ---");
        setStatus(READY, "DONE. Centre / 'Live view' = inverted live view, MENU = exit");
    }

    /** Probe 3: FILMINV/LTL00002_POS.JPG failed with ENOENT while 8.3 names always worked. Confirm. */
    private void filenameTest() {
        File dir = new File(Environment.getExternalStorageDirectory(), "FILMINV");
        dir.mkdirs();
        String[] names = {"SHORT.TXT", "LONGNAME_TEST.TXT", "lower.txt", "AB_CD.TXT"};
        StringBuilder s = new StringBuilder("[2] write test in " + dir.getAbsolutePath() + ":");
        for (String n : names) {
            File f = new File(dir, n);
            try {
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(n.getBytes());
                fo.close();
                s.append("  ").append(n).append("=OK");
                f.delete();                                   // our own test file only
            } catch (Throwable t) {
                s.append("  ").append(n).append("=FAIL(").append(t.getMessage()).append(')');
            }
        }
        out(s.toString());
    }

    /** Newest JPEG that has an ARW with the same name next to it (a RAW+JPEG pair). */
    private File pickJpeg() {
        File dcim = new File(Environment.getExternalStorageDirectory(), "DCIM");
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
        if (best == null) { out("[3] NO RAW+JPEG pair found."); return null; }
        out("[3] newest RAW+JPEG pair: " + best.getAbsolutePath());
        return best;
    }

    /** Inversion at 1/4 size. Output: /FILMINV/<same 8.3 name> (a new file; the original is untouched). */
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
        long tf = System.currentTimeMillis();
        NegEngine.Frame fr = NegEngine.analyseFrame(px, w, h);     // picture area + base from the rebate
        long tf2 = System.currentTimeMillis();
        NegEngine.Profile p = new NegEngine.Profile();
        p.base = fr.base != null ? fr.base : autoBase(px);
        out("    frame " + fr + " in " + (tf2 - tf) + " ms" + (fr.base == null ? " (no rebate: fallback base)" : ""));
        NegEngine.Histogram hist = new NegEngine.Histogram(p);
        hist.addArea(px, w, fr, 2);                                 // levels from the picture area only
        NegEngine.levelsFromHistogram(p, hist);
        NegEngine.apply(px, 0, px.length, NegEngine.buildLuts(p), p.film);
        Bitmap outBm = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        px = null;
        File dir = new File(Environment.getExternalStorageDirectory(), "FILMINV");
        dir.mkdirs();
        File dst = new File(dir, jpg.getName().toUpperCase());
        FileOutputStream fo = new FileOutputStream(dst);
        try { outBm.compress(Bitmap.CompressFormat.JPEG, 92, fo); } finally { fo.close(); }
        outBm.recycle();
        out("    " + w + "x" + h + " base " + fmt(p.base) + " -> wrote " + dst.getAbsolutePath()
                + " (" + dst.length() / 1024 + " KB) in " + (System.currentTimeMillis() - t0) + " ms");
    }

    /** Fallback base when no film border is visible: per-channel 99.5th percentile of unclipped pixels. */
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

    // ------------------------------------------------------------------ live view
    private static final String[] SAVED_KEYS = {
        "whitebalance", "color-temperture-white-balance", "white-balance-shift-mode",
        "white-balance-shift-lb", "white-balance-shift-cc", "rgb-matrix-mode", "rgb-matrix",
    };

    private void startLive() {
        out("[4] inverted live view, " + now());
        try {
            if (!surfaceReady) { setStatus(READY, "Surface not ready, try again"); return; }
            cameraEx = CameraEx.open(0, null);
            Camera cam = cameraEx.getNormalCamera();
            Camera.Parameters p = cam.getParameters();
            savedParams.clear();
            StringBuilder s = new StringBuilder("    saved settings:");
            for (String k : SAVED_KEYS) { savedParams.put(k, p.get(k)); s.append(' ').append(k).append('=').append(p.get(k)); }
            out(s.toString());
            try { temp = Integer.parseInt(p.get("color-temperture-white-balance")); } catch (Throwable ignored) {}
            wbTouched = shiftTouched = matrixTouched = false;
            cam.setPreviewDisplay(surface.getHolder());
            cam.startPreview();
            positive = true;
            applyCurve();
            showLive(true);
            refreshRow();
        } catch (Throwable t) {
            err("start live view", t);
            restoreCamera();
            showLive(false);
            setStatus(READY, "Camera open failed (see log). MENU = exit");
        }
    }

    /** Format from probe 3: 1024 x u16 LE, 0..top. Positive = inverted. */
    private synchronized void applyCurve() {
        if (cameraEx == null) return;
        try {
            int top = CURVE_TOPS[curveIdx];
            CameraEx.GammaTable t = cameraEx.createGammaTable();
            byte[] b = new byte[t.getSize()];                 // the buffer is uninitialised: fill all of it
            for (int i = 0; i < 1024 && 2 * i + 1 < b.length; i++) {
                int v = (int) Math.round((positive ? 1023 - i : i) * top / 1023.0);
                b[2 * i] = (byte) v;
                b[2 * i + 1] = (byte) (v >> 8);
            }
            t.write(b);
            cameraEx.setExtendedGammaTable(t);
            CameraEx.GammaTable old = activeTable;
            activeTable = t;
            if (old != null) { try { old.release(); } catch (Throwable r) { err("release old table", r); } }
            out("    " + now() + "  curve " + (positive ? "POSITIVE" : "NEGATIVE") + " top=" + top);
            updateStatusLine();
        } catch (Throwable t) {
            err("apply curve", t);
        }
    }

    /** Write one or more camera parameters, then read them back. */
    private synchronized void setParams(String... kv) {
        if (cameraEx == null) return;
        Camera cam = cameraEx.getNormalCamera();
        try {
            Camera.Parameters p = cam.getParameters();
            for (int i = 0; i + 1 < kv.length; i += 2) p.set(kv[i], kv[i + 1]);
            cam.setParameters(p);
            Camera.Parameters r = cam.getParameters();
            StringBuilder s = new StringBuilder("    " + now() + "  set");
            for (int i = 0; i + 1 < kv.length; i += 2) {
                String got = r.get(kv[i]);
                s.append(' ').append(kv[i]).append('=').append(kv[i + 1]);
                if (got == null || !got.equals(kv[i + 1])) s.append(" (READBACK ").append(got).append(')');
            }
            out(s.toString());
        } catch (Throwable t) {
            err("setParameters " + java.util.Arrays.toString(kv), t);
        }
    }

    private void applyRow() {
        switch (row) {
            case ROW_CURVE: applyCurve(); break;
            case ROW_TEMP:
                wbTouched = true;
                setParams("whitebalance", "color-temp", "color-temperture-white-balance", String.valueOf(temp));
                break;
            case ROW_AB: case ROW_GM:
                shiftTouched = true;
                setParams("white-balance-shift-mode", "true", "white-balance-shift-lb", String.valueOf(shiftAB),
                        "white-balance-shift-cc", String.valueOf(shiftGM));
                break;
            case ROW_MATRIX:
                matrixTouched = true;
                int s = MATRIX_SCALES[matrixIdx];
                if (s == 0) setParams("rgb-matrix-mode", "false");
                else setParams("rgb-matrix-mode", "true", "rgb-matrix", s + ",0,0,0," + s + ",0,0,0," + s);
                break;
        }
        refreshRow();
    }

    private String rowValueText(int r) {
        switch (r) {
            case ROW_CURVE: return String.valueOf(CURVE_TOPS[curveIdx]);
            case ROW_TEMP: return temp + " K";
            case ROW_AB: return (shiftAB > 0 ? "B+" : shiftAB < 0 ? "A+" : "") + Math.abs(shiftAB);
            case ROW_GM: return (shiftGM > 0 ? "M+" : shiftGM < 0 ? "G+" : "") + Math.abs(shiftGM);
            default: return MATRIX_SCALES[matrixIdx] == 0 ? "off" : "identity x" + MATRIX_SCALES[matrixIdx];
        }
    }

    private int[] sliderRange() {   // {max, progress}
        switch (row) {
            case ROW_CURVE: return new int[]{CURVE_TOPS.length - 1, curveIdx};
            case ROW_TEMP: return new int[]{(9900 - 2500) / 100, (temp - 2500) / 100};
            case ROW_AB: return new int[]{64, shiftAB + 32};
            case ROW_GM: return new int[]{64, shiftGM + 32};
            default: return new int[]{MATRIX_SCALES.length - 1, matrixIdx};
        }
    }

    private void setRowValueFromSlider(int p) {
        if (state != LIVE) return;
        switch (row) {
            case ROW_CURVE: curveIdx = p; break;
            case ROW_TEMP: temp = 2500 + 100 * p; break;
            case ROW_AB: shiftAB = p - 32; break;
            case ROW_GM: shiftGM = p - 32; break;
            default: matrixIdx = p; break;
        }
        applyRow();
    }

    private void stepRow(int dir) {
        switch (row) {
            case ROW_CURVE: curveIdx = clamp(curveIdx + dir, 0, CURVE_TOPS.length - 1); break;
            case ROW_TEMP: temp = clamp(temp + 100 * dir, 2500, 9900); break;
            case ROW_AB: shiftAB = clamp(shiftAB + dir, -32, 32); break;
            case ROW_GM: shiftGM = clamp(shiftGM + dir, -32, 32); break;
            default: matrixIdx = clamp(matrixIdx + dir, 0, MATRIX_SCALES.length - 1); break;
        }
        applyRow();
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

    private void refreshRow() {
        final String label = "[" + (row + 1) + "/" + ROW_NAMES.length + "] " + ROW_NAMES[row] + ": " + rowValueText(row);
        final int[] r = sliderRange();
        ui.post(new Runnable() {
            public void run() {
                rowLabel.setText(label);
                sliderUpdating = true;
                slider.setMax(r[0]);
                slider.setProgress(r[1]);
                sliderUpdating = false;
            }
        });
        updateStatusLine();
    }

    private void updateStatusLine() {
        setStatus(LIVE, (positive ? "POSITIVE" : "NEGATIVE") + " | top " + CURVE_TOPS[curveIdx]
                + (wbTouched ? " | " + temp + "K" : "") + (shiftTouched ? " | shift " + rowValueText(ROW_AB) + "/" + rowValueText(ROW_GM) : "")
                + (matrixTouched ? " | matrix " + rowValueText(ROW_MATRIX) : ""));
    }

    /** Take a picture with the current curve. Is the saved JPEG a positive? (the ARW never is) */
    private void capture() {
        if (cameraEx == null) return;
        try {
            final Camera cam = cameraEx.getNormalCamera();
            out("    " + now() + "  CAPTURE with curve " + (positive ? "POSITIVE" : "NEGATIVE") + " top=" + CURVE_TOPS[curveIdx]);
            setStatus(LIVE, "Capturing...");
            cam.takePicture(null, null, null);
            ui.postDelayed(new Runnable() {
                public void run() {
                    try { cam.startPreview(); } catch (Throwable t) { err("restart preview after capture", t); }
                    out("    newest file after capture: " + newestDcimFile());
                    updateStatusLine();
                }
            }, 3000);
        } catch (Throwable t) {
            err("capture", t);
        }
    }

    private String newestDcimFile() {
        File best = null;
        File[] sub = new File(Environment.getExternalStorageDirectory(), "DCIM").listFiles();
        if (sub != null) for (File d : sub) {
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best == null ? "none" : best.getAbsolutePath();
    }

    /** Undo everything: settings back (read back), our curve off and released, camera released. */
    private synchronized void restoreCamera() {
        if (cameraEx == null) return;
        long t0 = System.currentTimeMillis();
        try {
            if (!savedParams.isEmpty() && (wbTouched || shiftTouched || matrixTouched)) {
                Camera cam = cameraEx.getNormalCamera();
                Camera.Parameters p = cam.getParameters();
                for (Map.Entry<String, String> e : savedParams.entrySet()) if (e.getValue() != null) p.set(e.getKey(), e.getValue());
                cam.setParameters(p);
                Camera.Parameters r = cam.getParameters();
                StringBuilder s = new StringBuilder("    settings restored:");
                for (Map.Entry<String, String> e : savedParams.entrySet()) {
                    String got = r.get(e.getKey());
                    boolean ok = e.getValue() == null ? got == null : e.getValue().equals(got);
                    s.append(' ').append(e.getKey()).append(ok ? "=ok" : "=MISMATCH(" + got + " vs " + e.getValue() + ")");
                }
                out(s.toString());
            }
        } catch (Throwable t) { err("restore: settings", t); }
        try { cameraEx.setExtendedGammaTable(null); } catch (Throwable t) { err("restore: table off", t); }
        if (activeTable != null) {
            try { activeTable.release(); } catch (Throwable t) { err("restore: release table", t); }
            activeTable = null;
        }
        try { cameraEx.getNormalCamera().stopPreview(); } catch (Throwable t) { err("restore: stopPreview", t); }
        try { cameraEx.release(); } catch (Throwable t) { err("restore: camera release", t); }
        cameraEx = null;
        wbTouched = shiftTouched = matrixTouched = false;
        out("    camera restored and released in " + (System.currentTimeMillis() - t0) + " ms, " + now());
    }

    // ------------------------------------------------------------------ input (keys and touch share these)
    private void onEnterAction() {
        if (state == READY) {
            state = LIVE;
            new Thread(new Runnable() { public void run() { startLive(); } }).start();
        } else if (state == LIVE) {
            positive = !positive;
            applyCurve();
        } else {
            out("centre ignored: still working");
        }
    }

    private void onNextAction() { if (state == LIVE) stepRow(+1); }
    private void onPrevAction() { if (state == LIVE) stepRow(-1); }
    private void onUpAction() { if (state == LIVE) { row = (row + ROW_NAMES.length - 1) % ROW_NAMES.length; refreshRow(); } }
    private void onDownAction() { if (state == LIVE) { row = (row + 1) % ROW_NAMES.length; refreshRow(); } }
    private void onShutterAction() { if (state == LIVE) capture(); else out("capture ignored: not in live view"); }

    private void onMenuAction() {
        if (state == LIVE) {
            out("leaving live view");
            restoreCamera();
            showLive(false);
            setStatus(READY, "DONE. Centre = live view again, MENU = exit");
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
        if (isShutter(scan)) { onShutterAction(); return true; }
        if (isHalfPress(scan)) return true;
        if (isNext(scan) || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { onNextAction(); return true; }
        if (isPrev(scan) || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { onPrevAction(); return true; }
        if (isUp(scan) || keyCode == KeyEvent.KEYCODE_DPAD_UP) { onUpAction(); return true; }
        if (isDown(scan) || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { onDownAction(); return true; }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return super.dispatchTouchEvent(ev);   // probe 3 confirmed touch; buttons/slider log their own use
    }

    // ------------------------------------------------------------------ lifecycle
    public void surfaceCreated(SurfaceHolder h) { surfaceReady = true; }
    public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
    public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; }

    @Override
    protected void onPause() {
        out("onPause, " + now());
        restoreCamera();
        super.onPause();
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

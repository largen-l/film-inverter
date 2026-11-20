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
 * Rows: input white (film base -> black), input black, gamma (levels built into the curve), colour temperature,
 *       WB shift amber-blue, WB shift green-magenta (to cancel the orange mask), RGB matrix test patterns.
 * Capture releases the shutter (cancelTakePicture) after ~300 ms so the next capture works (probe 5 bug).
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
    private final Map<String, String> savedParams = new LinkedHashMap<String, String>();
    private boolean positive = true;

    // ------------------------------------------------------------------ adjustable rows
    // Rows. Curve levels work on the table's input (0..1023, the camera's signal before our curve):
    // POSITIVE maps input "white" (the film base: thinnest negative) to black and input "black" to white.
    private static final int ROW_HI = 0, ROW_LO = 1, ROW_GAMMA = 2, ROW_TEMP = 3, ROW_AB = 4, ROW_GM = 5, ROW_MATRIX = 6;
    private static final String[] ROW_NAMES = {"Input white (base)", "Input black", "Gamma", "Colour temp",
            "WB amber-blue", "WB green-magenta", "Matrix pattern"};
    // Probe 4: the matrix is live but identity-at-scale-N barely changed anything. These patterns are chosen to
    // look obviously different depending on what the numbers mean (absolute matrix vs offset from identity).
    private static final String[] MATRIX_LABELS = {"off", "1 all zeros", "2 red only 256", "3 red only 1024",
            "4 red only 4096", "5 swap R/B 1024", "6 swap R/B 4096", "7 minus blue -1024", "8 minus blue -4096"};
    private static final String[] MATRIX_VALUES = {null, "0,0,0,0,0,0,0,0,0", "256,0,0,0,0,0,0,0,0",
            "1024,0,0,0,0,0,0,0,0", "4096,0,0,0,0,0,0,0,0", "0,0,1024,0,1024,0,1024,0,0", "0,0,4096,0,4096,0,4096,0,0",
            "0,0,0,0,0,0,0,0,-1024", "0,0,0,0,0,0,0,0,-4096"};
    private int row = ROW_HI;
    private int inHi = 1023, inLo = 0; // table input range mapped to the full output range, step 8
    private static final int GAMMA_DEFAULT = 85;   // owner's pick in 0.8 (looked right with full-range levels)
    private int gammaX100 = GAMMA_DEFAULT;         // 50..250: output = x^(1/gamma)
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
        // keep the slider's ends away from the screen edge (hard to reach at the bezel), bigger thumb travel area
        slider.setPadding(36, 12, 36, 12);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f);
        sl.leftMargin = 24;
        sl.rightMargin = 48;
        rowPanel.addView(slider, sl);
        // double-tap the slider = reset this row to its default (camera's own WB for the WB rows)
        slider.setOnTouchListener(new View.OnTouchListener() {
            private long lastUp;
            public boolean onTouch(View v, MotionEvent e) {
                if ((e.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
                    long t = System.currentTimeMillis();
                    if (t - lastUp < 350) {
                        out("touch: slider double-tap -> reset " + ROW_NAMES[row]);
                        resetRow();
                        lastUp = 0;
                        return true;
                    }
                    lastUp = t;
                }
                return false;                                  // let the SeekBar handle the touch normally
            }
        });
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
        addButton(buttons, "Flip", new Runnable() { public void run() { cycleFlip(); } });
        addButton(buttons, "Back / Exit", new Runnable() { public void run() { onMenuAction(); } });
        overlay.addView(buttons, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(overlay);
        setContentView(root);

        try { flipMode = getSharedPreferences("fi", MODE_PRIVATE).getInt("flip", 0); } catch (Throwable ignored) {}
        applyWindowFlip();
        setStatus(BUSY, "WORKING... please wait");
        out("=== Film Inverter probe 9, " + now() + " ===  flip mode " + FLIP_NAMES[flipMode]);
        new Thread(new Runnable() { public void run() { runSafeProbes(); } }).start();
    }

    // ------------------------------------------------------------------ display flip (owner's rig: screen upside down)
    // Sony's avio.DisplayManager only reports DeviceStatus.viewPattern (no setter), so try the Android ways:
    // rotate the window (buttons, text, touch) and/or rotate the camera preview. Which combination turns both the
    // live view and the buttons upside down can only be seen on the camera, hence 4 modes.
    private static final String[] FLIP_NAMES = {"1 normal", "2 buttons flipped", "3 buttons + live view flipped",
            "4 live view flipped"};
    private int flipMode;

    private void applyWindowFlip() {
        boolean flipWindow = flipMode == 1 || flipMode == 2;
        try {
            setRequestedOrientation(flipWindow ? 8 /* SCREEN_ORIENTATION_REVERSE_LANDSCAPE, API 9 */
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } catch (Throwable t) { err("flip window", t); }
    }

    private void applyPreviewFlip() {
        if (cameraEx == null) return;
        try {
            cameraEx.getNormalCamera().setDisplayOrientation(flipMode >= 2 ? 180 : 0);
        } catch (Throwable t) { err("flip preview", t); }
    }

    private void cycleFlip() {
        flipMode = (flipMode + 1) % FLIP_NAMES.length;
        try { getSharedPreferences("fi", MODE_PRIVATE).edit().putInt("flip", flipMode).commit(); } catch (Throwable ignored) {}
        applyWindowFlip();
        applyPreviewFlip();
        out("flip mode " + FLIP_NAMES[flipMode]);
        final String msg = "Flip: " + FLIP_NAMES[flipMode];
        ui.post(new Runnable() { public void run() { status.setText(msg); } });
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
        java.util.Set<String> skip = positiveCaptures();
        File[] sub = dcim.listFiles();
        if (sub != null) {
            for (File d : sub) {
                if (!d.isDirectory()) continue;
                File[] fs = d.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    String n = f.getName();
                    if (!n.toUpperCase().endsWith(".JPG") || skip.contains(n.toUpperCase())) continue;
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
        "white-balance-shift-lb", "white-balance-shift-cc", "rgb-matrix-mode", "rgb-matrix", "storage-fmt",
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
            // The positive only exists in the JPEG (the ARW is always the negative). Quality kept reverting to
            // RAW only (probe 4, 0.8: captures were ARW-only), so make sure captures write RAW+JPEG while we are
            // in live view; restored on exit with the other saved settings.
            if ("raw".equals(savedParams.get("storage-fmt"))) {
                setParamsWith(cam, "storage-fmt", "rawjpeg");
                wbTouched = true;                                // forces the restore on exit
            }
            cam.setPreviewDisplay(surface.getHolder());
            applyPreviewFlip();
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

    /**
     * Two GammaTable buffers per live-view session, reused (double buffering): write the one not on screen,
     * then switch to it. Probe 0.7 created and released 98 tables in two minutes and the camera hung after the
     * app exited; probe 2 leaked 12 and hung the same way. Sessions with a handful of tables exited cleanly.
     * So: never more than 2 allocations per session, released only after live view has stopped.
     */
    private final CameraEx.GammaTable[] tables = new CameraEx.GammaTable[2];
    private int nextTable;
    private int tablesCreated;

    /** Coalesce rapid changes (slider drags): apply at most every CURVE_MIN_MS. */
    private static final long CURVE_MIN_MS = 150;
    private long lastCurveAt;
    private boolean curvePending;

    private void requestCurve() {
        long wait = lastCurveAt + CURVE_MIN_MS - System.currentTimeMillis();
        if (wait <= 0) { applyCurve(); return; }
        if (curvePending) return;
        curvePending = true;
        ui.postDelayed(new Runnable() {
            public void run() { curvePending = false; applyCurve(); }
        }, wait);
    }

    /** Format from probe 3: 1024 x u16 LE, 0..1023. Positive = inverted. */
    private synchronized void applyCurve() {
        if (cameraEx == null) return;
        lastCurveAt = System.currentTimeMillis();
        try {
            if (tables[nextTable] == null) { tables[nextTable] = cameraEx.createGammaTable(); tablesCreated++; }
            CameraEx.GammaTable t = tables[nextTable];
            byte[] b = new byte[t.getSize()];                 // the buffer is uninitialised: fill all of it
            double span = Math.max(8, inHi - inLo), g = gammaX100 / 100.0;
            for (int i = 0; i < 1024 && 2 * i + 1 < b.length; i++) {
                double x = positive ? (inHi - i) / span : (i - inLo) / span;
                x = Math.max(0, Math.min(1, x));
                int v = (int) Math.round(1023 * Math.pow(x, 1.0 / g));
                b[2 * i] = (byte) v;
                b[2 * i + 1] = (byte) (v >> 8);
            }
            t.write(b);
            cameraEx.setExtendedGammaTable(t);
            nextTable ^= 1;
            out("    " + now() + "  curve " + curveText() + " (table " + (nextTable ^ 1) + ", allocated " + tablesCreated + ")");
            updateStatusLine();
        } catch (Throwable t) {
            err("apply curve", t);
        }
    }

    /** Write one or more camera parameters, then read them back. */
    private synchronized void setParams(String... kv) {
        if (cameraEx == null) return;
        setParamsWith(cameraEx.getNormalCamera(), kv);
    }

    private void setParamsWith(Camera cam, String... kv) {
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

    private String curveText() {
        return (positive ? "POSITIVE" : "NEGATIVE") + " in " + inLo + ".." + inHi + " gamma " + (gammaX100 / 100.0);
    }

    private void applyRow() {
        switch (row) {
            case ROW_HI: case ROW_LO: case ROW_GAMMA: requestCurve(); break;
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
                if (MATRIX_VALUES[matrixIdx] == null) setParams("rgb-matrix-mode", "false");
                else setParams("rgb-matrix-mode", "true", "rgb-matrix", MATRIX_VALUES[matrixIdx]);
                out("    matrix pattern " + MATRIX_LABELS[matrixIdx]);
                break;
        }
        refreshRow();
    }

    private String rowValueText(int r) {
        switch (r) {
            case ROW_HI: return String.valueOf(inHi);
            case ROW_LO: return String.valueOf(inLo);
            case ROW_GAMMA: return String.valueOf(gammaX100 / 100.0);
            case ROW_TEMP: return temp + " K";
            case ROW_AB: return (shiftAB > 0 ? "B+" : shiftAB < 0 ? "A+" : "") + Math.abs(shiftAB);
            case ROW_GM: return (shiftGM > 0 ? "M+" : shiftGM < 0 ? "G+" : "") + Math.abs(shiftGM);
            default: return MATRIX_LABELS[matrixIdx];
        }
    }

    private int[] sliderRange() {   // {max, progress}
        switch (row) {
            case ROW_HI: return new int[]{1023 / 8, inHi / 8};
            case ROW_LO: return new int[]{1023 / 8, inLo / 8};
            case ROW_GAMMA: return new int[]{(250 - 50) / 5, (gammaX100 - 50) / 5};
            case ROW_TEMP: return new int[]{(9900 - 2500) / 100, (temp - 2500) / 100};
            case ROW_AB: return new int[]{64, shiftAB + 32};
            case ROW_GM: return new int[]{64, shiftGM + 32};
            default: return new int[]{MATRIX_LABELS.length - 1, matrixIdx};
        }
    }

    private void setRowValueFromSlider(int p) {
        if (state != LIVE) return;
        switch (row) {
            case ROW_HI: inHi = Math.max(inLo + 8, Math.min(1023, p * 8 + 7)); break;
            case ROW_LO: inLo = Math.min(inHi - 8, p * 8); break;
            case ROW_GAMMA: gammaX100 = 50 + 5 * p; break;
            case ROW_TEMP: temp = 2500 + 100 * p; break;
            case ROW_AB: shiftAB = p - 32; break;
            case ROW_GM: shiftGM = p - 32; break;
            default: matrixIdx = p; break;
        }
        applyRow();
    }

    private void stepRow(int dir) {
        switch (row) {
            case ROW_HI: inHi = clamp(inHi + 8 * dir, inLo + 8, 1023); break;
            case ROW_LO: inLo = clamp(inLo + 8 * dir, 0, inHi - 8); break;
            case ROW_GAMMA: gammaX100 = clamp(gammaX100 + 5 * dir, 50, 250); break;
            case ROW_TEMP: temp = clamp(temp + 100 * dir, 2500, 9900); break;
            case ROW_AB: shiftAB = clamp(shiftAB + dir, -32, 32); break;
            case ROW_GM: shiftGM = clamp(shiftGM + dir, -32, 32); break;
            default: matrixIdx = clamp(matrixIdx + dir, 0, MATRIX_LABELS.length - 1); break;
        }
        applyRow();
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

    /** Double-tap on the slider: back to the default (levels) or to the camera's own setting (WB rows, matrix). */
    private void resetRow() {
        if (state != LIVE) return;
        switch (row) {
            case ROW_HI: inHi = 1023; requestCurve(); break;
            case ROW_LO: inLo = 0; requestCurve(); break;
            case ROW_GAMMA: gammaX100 = GAMMA_DEFAULT; requestCurve(); break;
            case ROW_TEMP: {
                String wb = savedParams.get("whitebalance"), ct = savedParams.get("color-temperture-white-balance");
                try { temp = Integer.parseInt(ct); } catch (Throwable ignored) {}
                if (wb != null && ct != null) setParams("whitebalance", wb, "color-temperture-white-balance", ct);
                break;
            }
            case ROW_AB: case ROW_GM: {
                if (row == ROW_AB) shiftAB = 0; else shiftGM = 0;
                String mode = savedParams.get("white-balance-shift-mode");
                boolean off = shiftAB == 0 && shiftGM == 0;
                setParams("white-balance-shift-mode", off && mode != null ? mode : "true",
                        "white-balance-shift-lb", String.valueOf(shiftAB), "white-balance-shift-cc", String.valueOf(shiftGM));
                break;
            }
            default:
                matrixIdx = 0;
                setParams("rgb-matrix-mode", "false");
                break;
        }
        refreshRow();
    }

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
        setStatus(LIVE, curveText()
                + (wbTouched ? " | " + temp + "K" : "") + (shiftTouched ? " | shift " + rowValueText(ROW_AB) + "/" + rowValueText(ROW_GM) : "")
                + (matrixTouched ? " | matrix " + rowValueText(ROW_MATRIX) : ""));
    }

    private volatile boolean capturing;

    /**
     * Take a picture with the current curve: with the POSITIVE curve the saved JPEG is a full-res positive
     * (probe 4), the ARW stays the negative. Ignored while the previous capture is still being saved
     * (probe 5: a second takePicture within ~3 s throws "takePicture failed").
     */
    private void capture() {
        if (cameraEx == null) return;
        if (capturing) { out("    capture ignored: previous capture still saving"); return; }
        capturing = true;
        try {
            final Camera cam = cameraEx.getNormalCamera();
            final boolean pos = positive;
            final long startedAt = System.currentTimeMillis();
            try { out("    storage-fmt at capture: " + cam.getParameters().get("storage-fmt")); } catch (Throwable ignored) {}
            out("    " + now() + "  CAPTURE with curve " + curveText());
            setStatus(LIVE, "Capturing... wait");
            cam.takePicture(null, null, null);
            // Sony treats takePicture as "shutter pressed" and waits for "shutter released" (cancelTakePicture,
            // which PMCADemo sends on shutter key-up). Without it the next takePicture fails until live view is
            // reopened (probe 5).
            ui.postDelayed(new Runnable() {
                public void run() {
                    try { if (cameraEx != null) cameraEx.cancelTakePicture(); } catch (Throwable t) { err("release shutter", t); }
                }
            }, 300);
            ui.postDelayed(new Runnable() {
                public void run() {
                    try {
                        if (cameraEx == null) return;                 // left live view meanwhile
                        try { cam.startPreview(); } catch (Throwable t) { err("restart preview after capture", t); }
                        File jpg = newestDcimJpeg();
                        // only a JPEG written by this capture counts (0.8 listed an old negative's JPEG when the
                        // capture was RAW-only); file times have 2 s resolution on FAT/exFAT
                        if (jpg != null && jpg.lastModified() < startedAt - 2500) jpg = null;
                        out("    JPEG from this capture: " + (jpg == null ? "none (RAW only?)" : jpg.getAbsolutePath()));
                        if (pos && jpg != null) rememberPositiveCapture(jpg.getName());
                        updateStatusLine();
                    } finally {
                        capturing = false;
                    }
                }
            }, 3000);
        } catch (Throwable t) {
            capturing = false;
            err("capture", t);
        }
    }

    private File newestDcimJpeg() {
        File best = null;
        File[] sub = new File(Environment.getExternalStorageDirectory(), "DCIM").listFiles();
        if (sub != null) for (File d : sub) {
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs)
                if (f.getName().toUpperCase().endsWith(".JPG") && (best == null || f.lastModified() > best.lastModified())) best = f;
        }
        return best;
    }

    /** JPEGs captured with the POSITIVE curve are already positives: list them so the engine skips them. */
    private static final String CAPTURED_LIST = "FILMINV/CAPTURED.TXT";

    private void rememberPositiveCapture(String name) {
        try {
            File f = new File(Environment.getExternalStorageDirectory(), CAPTURED_LIST);
            f.getParentFile().mkdirs();
            FileWriter fw = new FileWriter(f, true);
            fw.write(name.toUpperCase() + "\n");
            fw.close();
        } catch (Throwable t) { err("remember capture", t); }
    }

    private java.util.Set<String> positiveCaptures() {
        java.util.Set<String> s = new java.util.HashSet<String>();
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(
                    new File(Environment.getExternalStorageDirectory(), CAPTURED_LIST)));
            String line;
            while ((line = r.readLine()) != null) if (line.trim().length() > 0) s.add(line.trim().toUpperCase());
            r.close();
        } catch (Throwable ignored) {}
        return s;
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
        // Order matters (see the hang notes on `tables`): curve off, let a frame or two pass, stop the preview,
        // and only then free the table buffers and the camera. Each step is timed in the log.
        try { cameraEx.setExtendedGammaTable(null); } catch (Throwable t) { err("restore: table off", t); }
        out("    restore: curve off at +" + (System.currentTimeMillis() - t0) + " ms");
        try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        try { cameraEx.getNormalCamera().stopPreview(); } catch (Throwable t) { err("restore: stopPreview", t); }
        out("    restore: preview stopped at +" + (System.currentTimeMillis() - t0) + " ms");
        for (int i = 0; i < tables.length; i++) {
            if (tables[i] != null) {
                try { tables[i].release(); } catch (Throwable t) { err("restore: release table " + i, t); }
                tables[i] = null;
            }
        }
        out("    restore: " + tablesCreated + " table(s) released at +" + (System.currentTimeMillis() - t0) + " ms");
        tablesCreated = 0;
        nextTable = 0;
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
        if (capturing) { out("MENU ignored: capture still saving"); return; }
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

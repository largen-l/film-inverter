package com.filminverter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
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

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Film Inverter, first app build (0.12) on the "refined minimal" UI (concept #1).
 *
 * Opens straight into inverted live view (hardware tone curve, see docs/api-usage/probe3.md).
 *   Chips / UP-DOWN: choose Black, White, Gamma, Temp, A–B, G–M.  Slider / LEFT-RIGHT / dials: change it.
 *   Double-tap the slider: reset.  CENTER: positive <-> negative.  SHUTTER / round button: capture.
 *   MENU / ☰: menu (calibrate base, auto levels, alignment check, guides, delay, silent shutter, theme, flip,
 *   develop newest frame, lens controls, log, reset roll, exit). In the menu UP/DOWN move, CENTER selects, MENU closes.
 *
 * Measurements (calibration, auto levels, alignment) take small JPEG-only captures with a plain curve
 * (3008x2000, quality 25), read them back and adjust the camera: the camera gives apps no live-view frames.
 * Every camera setting touched is saved first and restored (and read back) when the app pauses or exits.
 * Hard-won rules kept from the probes: two reused GammaTables per session (more hung the camera), release the
 * shutter after takePicture, 8.3 file names only, hand the screen back in normal landscape on exit.
 */
public class MainActivity extends SonyActivity implements SurfaceHolder.Callback {

    private final Handler ui = new Handler();
    private final StringBuilder logBuf = new StringBuilder();     // lines waiting for the card (see flushLog)
    private File card;

    // ------------------------------------------------------------------ views
    private SurfaceView surface;
    private OverlayViews.Guides guides;
    private TextView modePill, framePill, basePill, wbPill, toast, menuBtn, valueLabel, logText;
    private OverlayViews.CaptureButton captureBtn;
    private LinearLayout menuPanel;
    private ScrollView menuScroll;
    private ScrollView logScroll;
    private Slider slider;
    private final List<TextView> chips = new ArrayList<TextView>();
    private final List<TextView> menuItems = new ArrayList<TextView>();
    private int menuSel;
    private boolean menuOpen;

    // ------------------------------------------------------------------ settings / roll profile
    private List<Theme> themes;
    private int themeIdx;
    private int flipMode;                         // 0 normal, 1 upside down (window rotation)
    private boolean guidesOn, silent;
    private int delaySec;

    private static final int ROW_BLACK = 0, ROW_WHITE = 1, ROW_GAMMA = 2, ROW_TEMP = 3, ROW_AB = 4, ROW_GM = 5;
    private static final int ROW_EXP = 6;                  // rows 6..9: f/, Shutter, ISO, EV (Lens controls on)
    private static final String[] ROW_NAMES = {"Black", "White", "Gamma", "Temp", "A–B", "G–M", "f/", "Shutter", "ISO", "EV"};
    private int row = ROW_BLACK;
    private boolean lensControls;                          // Menu › Lens controls (for electronic lenses)
    private Exposure exposure;
    private TextView pageChip, expPill;
    private boolean lensHintShown;
    private int inLo = 0, inHi = 1023, gammaX100 = 85;     // curve: input [inLo, inHi] -> full output range
    private int temp = 5500, shiftAB = 0, shiftGM = 0;     // WB, applied only when wbActive
    private boolean wbActive;
    private boolean positive = true;

    // ------------------------------------------------------------------ camera
    private volatile boolean surfaceReady, live, busy;
    private CameraEx cameraEx;
    private final CameraEx.GammaTable[] tables = new CameraEx.GammaTable[2];
    private int nextTable, tablesCreated;
    private boolean measureCurve;                           // plain identity curve while measuring
    private final Map<String, String> savedParams = new LinkedHashMap<String, String>();
    private boolean paramsChanged;
    private volatile boolean capturing;
    private long lastCurveAt;
    private boolean curvePending;
    private static final long CURVE_MIN_MS = 150;
    private static final String[] SAVED_KEYS = {
        "whitebalance", "color-temperture-white-balance", "white-balance-shift-mode", "white-balance-shift-lb",
        "white-balance-shift-cc", "rgb-matrix-mode", "rgb-matrix", "storage-fmt", "picture-size", "jpeg-quality",
        "silent-shutter-mode",
    };

    // ================================================================== lifecycle
    @Override
    protected void onCreate(Bundle b) {
        card = Environment.getExternalStorageDirectory();

        installCrashLogger();
        super.onCreate(b);
        exposure = new Exposure(new Exposure.Host() {
            public void log(String s) { out(s); }
            public void toast(String s) { showToast(s); }
            public void changed() { if (row >= ROW_EXP) refreshRow(); else refreshPills(); }
        }, ui);
        loadPrefs();
        loadRoll();
        List<String> errs = new ArrayList<String>();
        themes = Theme.all(new File(card, "FILMINV/THEMES"), errs);
        themeIdx = Math.max(0, Math.min(themes.size() - 1, themeIdx));
        buildUi();
        applyWindowFlip();
        out("=== Film Inverter 0.20, " + now() + " === theme " + themes.get(themeIdx).name + ", " + themes.size() + " themes");
        for (String e : errs) out("!! theme file " + e);
        if (!errs.isEmpty()) showToast("Theme file error: " + errs.get(0));
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyWindowFlip();
        if (surfaceReady && !live) startLiveAsync();
    }

    @Override
    protected void onPause() {
        out("onPause, " + now());
        restoreCamera();
        saveRoll();
        unflipForExit();
        flushLog(true);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        restoreCamera();
        out("=== exit ===");
        flushLog(true);
        super.onDestroy();
    }

    public void surfaceCreated(SurfaceHolder h) { surfaceReady = true; if (!live) startLiveAsync(); }
    public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) { out("surface " + w + "x" + ht); }
    public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; }

    // ================================================================== UI
    // Eight layouts, one per UI concept (Theme.LAYOUTS), all dark and all with the same controls: mode, status,
    // base, WB, exposure, every row with its value, slider, menu, shutter. Coordinates are in the 640x480 space of
    // the concept mockups. The live view always fills the screen; side bars left and right of the picture (when the
    // circle-shape setting leaves any) may be decorated. Panels over the picture hide after a few idle seconds
    // (Menu > Controls), so the whole picture can be seen; any key or touch brings them back.
    // Menu > Circle shape (PA, 75-100%) narrows round things and text in case the screen's pixels are not square:
    // 0.18 assumed a 16:9 panel (75%) and the owner found it looked worse than 100%.
    private int sp(float v) { return Math.round(v * themes.get(themeIdx).textScale); }

    private FrameLayout root;
    private String layout = "minimal";
    private float sx = 1f, sy = 1f;
    static float PA = 1f;                                          // pixel aspect correction (see above), Menu > Circle shape
    private int aspectPct = 100;
    private float picL = 50, picR = 590;                             // the picture's x range in mockup space
    private int menuLeft = 12, menuBottom = 116, toastTop = 52;
    private final List<View> rowBoxes = new ArrayList<View>();       // per row: what is highlighted / hidden
    private final List<TextView> rowVals = new ArrayList<TextView>();  // per row: its value (layouts that list them)
    private final OverlayViews.Dial[] dialFor = new OverlayViews.Dial[10];
    private TextView rowTitle, ledText, shootText, baseMark, segNeg, segPos, enlTag;
    private OverlayViews.Histogram histogram;
    private volatile int[][] lastHist;
    private volatile String lastHistName;

    // controls that hide when idle
    private final List<View> chrome = new ArrayList<View>();
    private boolean collect = true, chromeShown = true, autoHide = true, swallowTouch;
    private static final long IDLE_MS = 5000;

    private static final String[] ROW_LONG = {"Black point", "White point", "Gamma", "Color temp", "Amber–blue",
            "Green–magenta", "Aperture", "Shutter speed", "ISO", "Exposure comp."};
    private static final String[] ROW_SHORT = {"BLK", "WHT", "GAM", "TEMP", "A–B", "G–M", "F/", "SHUT", "ISO", "EV"};

    private final View.OnClickListener onMenu = new View.OnClickListener() { public void onClick(View v) { toggleMenu(); } };
    private final View.OnClickListener onShoot = new View.OnClickListener() { public void onClick(View v) { onShutterAction(); } };
    private final View.OnClickListener onMode = new View.OnClickListener() { public void onClick(View v) { togglePositive(); } };

    private void togglePositive() {
        if (busy || menuOpen) return;
        positive = !positive;
        requestCurve();
        refreshPills();
    }

    private final Runnable hideChrome = new Runnable() { public void run() {
        if (!autoHide) return;
        if (menuOpen || busy || capturing || (logScroll != null && logScroll.getVisibility() == View.VISIBLE)) {
            ui.postDelayed(this, 2000);
            return;
        }
        setChrome(false);
    } };

    private void setChrome(boolean shown) {
        chromeShown = shown;
        for (View v : chrome) v.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    /** Any input: controls back, and hidden again after IDLE_MS (if Controls = hide when idle). */
    private void poke() {
        ui.post(new Runnable() { public void run() {
            if (!chromeShown) setChrome(true);
            ui.removeCallbacks(hideChrome);
            if (autoHide) ui.postDelayed(hideChrome, IDLE_MS);
        } });
    }

    /** While the controls are hidden, a touch only brings them back (it does not also press what is under it). */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        int a = e.getAction() & android.view.MotionEvent.ACTION_MASK;
        if (a == android.view.MotionEvent.ACTION_DOWN && !chromeShown) swallowTouch = true;
        poke();
        if (swallowTouch) {
            if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) swallowTouch = false;
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    private int X(float v) { return Math.round(v * sx); }
    private int Y(float v) { return Math.round(v * sy); }
    /** Width of something round that is `d` tall (mockup units). */
    private float R(float d) { return d * PA; }

    /** Adds v to the root at mockup coordinates; w/h of -1 (match parent) and -2 (wrap) are passed through. */
    private <T extends View> T put(T v, float l, float t, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? (int) w : X(w), h < 0 ? (int) h : Y(h),
                Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = X(l);
        lp.topMargin = Y(t);
        root.addView(v, lp);
        if (collect) chrome.add(v);
        return v;
    }

    /** As put, anchored to the right edge (`r` from the right). */
    private <T extends View> T putRight(T v, float r, float t, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? (int) w : X(w), h < 0 ? (int) h : Y(h),
                Gravity.TOP | Gravity.RIGHT);
        lp.rightMargin = X(r);
        lp.topMargin = Y(t);
        root.addView(v, lp);
        if (collect) chrome.add(v);
        return v;
    }

    /** As put, centered horizontally. */
    private <T extends View> T putCenter(T v, float t, float w, float h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? (int) w : X(w), h < 0 ? (int) h : Y(h),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Y(t);
        root.addView(v, lp);
        if (collect) chrome.add(v);
        return v;
    }

    private LinearLayout.LayoutParams lp(float w, float h) {
        return new LinearLayout.LayoutParams(w < 0 ? (int) w : X(w), h < 0 ? (int) h : Y(h));
    }

    private LinearLayout.LayoutParams lpw(float h, float weight) {
        return new LinearLayout.LayoutParams(0, h < 0 ? (int) h : Y(h), weight);
    }

    private LinearLayout.LayoutParams topMargin(float m) { LinearLayout.LayoutParams p = lp(-2, -2); p.topMargin = Y(m); return p; }

    private LinearLayout box(boolean vertical) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        return b;
    }

    private View spacer() { return new View(this); }

    private View line(int color) { View v = new View(this); v.setBackgroundColor(color); return v; }

    private TextView text(String s, float size, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp(size));
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    private TextView button(TextView t, View.OnClickListener l) {
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    private void style(TextView t, float size, int color, Typeface tf, android.graphics.drawable.Drawable bg) {
        t.setTextSize(sp(size));
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        t.setBackgroundDrawable(bg);
    }

    private GradientDrawable shape(int fill, int stroke, float radius) { return shape(fill, stroke, radius, 1); }

    /** Rounded rectangle; corners are elliptical (x radius * PA) so they look round on the wide pixels. */
    private GradientDrawable shape(int fill, int stroke, float radius, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (stroke != 0) d.setStroke(strokeWidth, stroke);
        float rx = radius * PA, ry = radius;
        d.setCornerRadii(new float[]{rx, ry, rx, ry, rx, ry, rx, ry});
        return d;
    }

    /** View.setRotation / setAlpha are API 11; the app compiles against API 10 and the a6500 runs API 16. */
    private static void rotate(View v, float deg) {
        try { View.class.getMethod("setRotation", float.class).invoke(v, deg); } catch (Throwable ignored) {}
    }

    private static void alpha(View v, float a) {
        try { View.class.getMethod("setAlpha", float.class).invoke(v, a); } catch (Throwable ignored) {}
    }

    /** Condense every text by PA so letters keep their shape on the wide pixels. */
    private static void fixAspect(View v) {
        if (v instanceof TextView) ((TextView) v).setTextScaleX(PA);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) fixAspect(g.getChildAt(i));
        }
    }

    private static final Typeface MONO = Typeface.MONOSPACE, MONO_B = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    private static final Typeface COND = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    private static final Typeface COND_B = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private static final Typeface MARKER = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC);
    private static final Typeface BOLD = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        surface.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        android.view.Display d = getWindowManager().getDefaultDisplay();
        int w = Math.max(d.getWidth(), d.getHeight()), h = Math.min(d.getWidth(), d.getHeight());
        sx = w / 640f;
        sy = h / 480f;
        PA = aspectPct / 100f;                                       // Menu > Circle shape (owner: 0.75 looked worse than 1)
        float picW = Math.min(640f, 480f * 1.5f * PA);              // 3:2 picture, full height (or full width)
        picL = (640f - picW) / 2f;
        picR = 640f - picL;
        out("screen " + w + "x" + h + ", pixel aspect " + PA + ", picture x " + Math.round(picL) + ".." + Math.round(picR));
        buildOverlay();
    }

    private void applyAspect() {
        PA = aspectPct / 100f;
        float picW = Math.min(640f, 480f * 1.5f * PA);
        picL = (640f - picW) / 2f;
        picR = 640f - picL;
        buildOverlay();
    }

    /** Theme changed (or first start): rebuild every view but the live view surface in the theme's layout. */
    private void applyTheme() { buildOverlay(); }

    private void buildOverlay() {
        Theme t = themes.get(themeIdx);
        layout = t.layout;
        CharSequence oldLog = logText == null ? "" : logText.getText();
        while (root.getChildCount() > 1) root.removeViewAt(1);
        chips.clear();
        rowBoxes.clear();
        rowVals.clear();
        chrome.clear();
        chromeShown = true;
        java.util.Arrays.fill(dialFor, null);
        rowTitle = ledText = shootText = baseMark = segNeg = segPos = enlTag = null;
        histogram = null;
        makeCommon(t, oldLog);

        collect = false;                                             // live view and side-bar decoration stay
        guides = new OverlayViews.Guides(this);
        guides.setColor(t.accent);
        guides.setVisibility(guidesOn ? View.VISIBLE : View.GONE);
        put(guides, picL, 0, picR - picL, 480);
        collect = true;

        if ("camera".equals(layout)) layoutCamera(t);
        else if ("material".equals(layout)) layoutMaterial(t);
        else if ("glass".equals(layout)) layoutGlass(t);
        else if ("enlarger".equals(layout)) layoutEnlarger(t);
        else if ("lighttable".equals(layout)) layoutLightTable(t);
        else if ("scope".equals(layout)) layoutScope(t);
        else if ("safelight".equals(layout)) layoutSafelight(t);
        else layoutMinimal(t);

        // on top in every layout, never hidden: toast, menu, log
        collect = false;
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tl.topMargin = Y(toastTop); tl.leftMargin = X(24); tl.rightMargin = X(24);
        root.addView(toast, tl);
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(X(300), -2, Gravity.BOTTOM | Gravity.LEFT);
        ml.bottomMargin = Y(menuBottom); ml.leftMargin = X(menuLeft); ml.topMargin = Y(8);
        root.addView(menuScroll, ml);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-1, -1);
        ll.bottomMargin = Y(112);
        root.addView(logScroll, ll);
        collect = true;

        menuPanel.setBackgroundDrawable(shape(t.bar | 0xff000000, t.chipBorder, t.radius));
        refreshRow();
        refreshPills();
        rebuildMenu();
        fixAspect(root);
        poke();
    }

    /** Views every layout uses (attached or not); layouts place and style them. */
    private void makeCommon(Theme t, CharSequence oldLog) {
        modePill = text("POS", 13, t.pillText, null); framePill = text("Live", 13, t.pillText, null);
        basePill = text("Base: not set", 13, t.pillText, null); wbPill = text("Camera WB", 13, t.pillText, null);
        expPill = text("", 13, t.pillText, null);
        expPill.setVisibility(View.GONE);
        toast = text("", 13, t.pillText, null);
        for (TextView p : new TextView[]{modePill, framePill, basePill, wbPill, expPill, toast}) {
            p.setPadding(X(10), Y(4), X(10), Y(4));
            p.setBackgroundDrawable(shape(t.pill, 0, 14));
        }
        button(modePill, onMode);                                    // tap the mode anywhere: positive <-> negative
        toast.setVisibility(View.GONE);
        toast.setGravity(Gravity.CENTER);
        toast.setBackgroundDrawable(shape(t.pill | 0xff000000, t.chipBorder, Math.min(14, t.radius + 4)));

        menuBtn = button(text("Menu", 14, t.text, null), onMenu);
        menuBtn.setContentDescription("Menu");

        for (int i = 0; i < ROW_NAMES.length; i++) {
            final int r = i;
            TextView c = text(ROW_NAMES[i], 13, t.textDim, null);
            c.setPadding(X(11), Y(6), X(11), Y(6));
            c.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { selectRow(r); } });
            chips.add(c);
            rowBoxes.add(c);
            rowVals.add(text("", 13, t.textDim, null));
        }
        pageChip = text("", 13, t.text, null);                  // color <-> exposure rows (Lens controls on)
        pageChip.setPadding(X(11), Y(6), X(11), Y(6));
        pageChip.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            selectRow(row >= ROW_EXP ? ROW_BLACK : ROW_EXP); } });

        slider = new Slider(this);
        slider.setListener(new Slider.Listener() {
            public void onValue(int v, boolean fromUser) { setRowFromSlider(v); }
            public void onReset() { resetRow(); }
        });
        slider.setColors(t.chipBorder, t.accent, t.text);
        valueLabel = text("", 15, t.text, MONO);
        valueLabel.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        captureBtn = new OverlayViews.CaptureButton(this);
        captureBtn.setOnClickListener(onShoot);
        captureBtn.setColor(t.text);

        menuPanel = box(true);
        menuPanel.setPadding(8, 8, 8, 8);
        // in a ScrollView: the menu is taller than the screen; styleMenu keeps the cursor in view
        menuScroll = new ScrollView(this);
        menuScroll.setVerticalScrollBarEnabled(true);
        // never take key focus: a focused ScrollView eats the d-pad (it scrolled instead of moving the cursor, 0.14)
        menuScroll.setFocusable(false);
        menuScroll.setFocusableInTouchMode(false);
        menuScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        menuScroll.addView(menuPanel, new FrameLayout.LayoutParams(-1, -2));
        menuScroll.setVisibility(menuOpen ? View.VISIBLE : View.GONE);

        logScroll = new ScrollView(this);
        logScroll.setFocusable(false);
        logScroll.setFocusableInTouchMode(false);
        logScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        logText = new TextView(this);
        logText.setTextSize(11);
        logText.setTextColor(Color.WHITE);
        logText.setBackgroundColor(0xd8000000);
        logText.setText(oldLog);
        logScroll.addView(logText);
        logScroll.setVisibility(View.GONE);
    }

    /** Decoration in the dark bars left and right of the picture (never over it, never hidden). */
    private void sideBars(int color, int deco, int decoColor) {
        collect = false;
        OverlayViews.SideBars b = new OverlayViews.SideBars(this);
        b.set(color, X(picL), X(picR), deco, decoColor);
        put(b, 0, 0, -1, -1);
        collect = true;
    }

    /** A status line of pills (mode, frame, exposure ...) in a row. */
    private LinearLayout pillRow(TextView... ps) {
        LinearLayout r = box(false);
        for (int i = 0; i < ps.length; i++) {
            LinearLayout.LayoutParams g = lp(-2, -2);
            if (i > 0) g.leftMargin = X(6);
            r.addView(ps[i], g);
        }
        return r;
    }

    private void pills(Theme t, int fill, int fg, float radius, Typeface tf, float size, TextView... ps) {
        for (TextView p : ps) {
            style(p, size, fg, tf, shape(fill, 0, radius));
            p.setPadding(X(9), Y(4), X(9), Y(4));
        }
    }

    // ------------------------------------------------------------------ 1 Minimal: pills on top, one bottom bar
    private void layoutMinimal(Theme t) {
        LinearLayout top = pillRow(modePill, framePill, expPill);
        put(top, 12, 12, -2, -2);
        putRight(pillRow(basePill, wbPill), 12, 12, -2, -2);

        LinearLayout bar = box(false);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(X(16), 0, X(16), 0);
        bar.setBackgroundColor(t.bar);
        menuBtn.setBackgroundDrawable(shape(0, t.chipBorder, t.radius));
        bar.addView(menuBtn, lp(64, 52));
        LinearLayout mid = box(true);
        mid.setPadding(X(14), 0, X(14), 0);
        LinearLayout chipRow = box(false);
        for (TextView c : chips) { LinearLayout.LayoutParams cl = lp(-2, -2); cl.rightMargin = X(6); chipRow.addView(c, cl); }
        chipRow.addView(pageChip, lp(-2, -2));
        mid.addView(chipRow);
        LinearLayout sliderRow = box(false);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        sliderRow.addView(slider, lpw(44, 1f));
        sliderRow.addView(valueLabel, lp(84, -2));
        mid.addView(sliderRow);
        bar.addView(mid, lpw(-2, 1f));
        bar.addView(captureBtn, lp(R(76), 76));
        put(bar, 0, 368, -1, 112);
        menuLeft = 12; menuBottom = 116; toastTop = 52;
    }

    // ------------------------------------------------------------------ 2 Camera: the camera's own menu style
    private void layoutCamera(Theme t) {
        LinearLayout rail = box(true);                       // section rail: menu, mode, shutter
        rail.setBackgroundColor(t.pill);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(0, Y(8), 0, Y(8));
        style(menuBtn, 13, t.text, COND_B, shape(0, t.chipBorder, t.radius));
        rail.addView(menuBtn, lp(52, 52));
        modePill.setPadding(0, 0, 0, 0);
        style(modePill, 15, t.onAccent, COND_B, shape(t.accent, 0, t.radius));
        LinearLayout.LayoutParams mp = lp(52, 52); mp.topMargin = Y(6);
        rail.addView(modePill, mp);
        rail.addView(spacer(), new LinearLayout.LayoutParams(1, 0, 1f));
        captureBtn.setStyle(OverlayViews.CaptureButton.RING, t.text, t.text, 0, 3);
        rail.addView(captureBtn, lp(R(56), 56));
        put(rail, 0, 0, 64, 480);

        LinearLayout panel = box(true);
        panel.setBackgroundColor(t.bar);
        LinearLayout head = box(false);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(X(14), 0, X(14), 0);
        head.addView(text("Develop", 17, t.text, COND_B));
        head.addView(spacer(), lpw(1, 1f));
        style(framePill, 14, t.textDim, COND, null);
        framePill.setPadding(0, 0, 0, 0);
        head.addView(framePill);
        panel.addView(head, lp(-1, 40));
        panel.addView(line(t.chipBorder), lp(-1, 1));
        LinearLayout list = box(true);
        for (int i = 0; i < chips.size(); i++) list.addView(listRow(t, i, 16, COND, true), lp(-1, 42));
        style(pageChip, 16, t.text, COND, null);
        pageChip.setPadding(X(14), 0, X(14), 0);
        pageChip.setGravity(Gravity.CENTER_VERTICAL);
        list.addView(pageChip, lp(-1, 42));
        panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        panel.addView(line(t.chipBorder), lp(-1, 1));
        LinearLayout foot = box(true);
        foot.setPadding(X(6), Y(2), X(10), Y(6));
        pills(t, 0, t.textDim, 0, COND, 13, basePill, wbPill, expPill);
        foot.addView(pillRow(basePill, wbPill));
        foot.addView(expPill);
        slider.setStyle(Slider.LINE);
        foot.addView(slider, lp(-1, 40));
        TextView hint = text("‹ › adjust  ·  up/down row  ·  center pos/neg", 12, t.textDim, COND);
        hint.setPadding(X(8), 0, 0, 0);
        foot.addView(hint);
        panel.addView(foot, lp(-1, -2));
        put(panel, 64, 0, 236, 480);
        menuLeft = 70; menuBottom = 8; toastTop = 52;
    }

    /** A list row: name (the row's chip) left, value right; tap selects. */
    private LinearLayout listRow(Theme t, final int i, float size, Typeface tf, boolean longName) {
        LinearLayout r = box(false);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(X(14), 0, X(14), 0);
        TextView name = chips.get(i), val = rowVals.get(i);
        name.setText(longName ? ROW_LONG[i] : ROW_SHORT[i]);
        name.setPadding(0, 0, 0, 0);
        style(name, size, t.text, tf, null);
        style(val, size, t.textDim, tf, null);
        r.addView(name);
        r.addView(spacer(), lpw(1, 1f));
        r.addView(val);
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { selectRow(i); } });
        rowBoxes.set(i, r);
        return r;
    }

    // ------------------------------------------------------------------ 3 Material: chips on top, bottom sheet
    private void layoutMaterial(Theme t) {
        int chip = 0xd9111414;
        pills(t, chip, t.text, 8, null, 13, modePill, framePill, expPill, basePill, wbPill);
        put(pillRow(modePill, framePill, expPill), 16, 16, -2, -2);
        LinearLayout topR = pillRow(basePill, wbPill);
        style(menuBtn, 11, t.text, null, shape(chip, 0, 20));
        LinearLayout.LayoutParams mb = lp(R(44), 40); mb.leftMargin = X(8);
        topR.addView(menuBtn, mb);
        putRight(topR, 16, 16, -2, -2);

        LinearLayout sheet = box(true);
        GradientDrawable sd = new GradientDrawable();
        sd.setColor(t.bar);
        float rr = 28;
        sd.setCornerRadii(new float[]{rr * PA, rr, rr * PA, rr, 0, 0, 0, 0});
        sheet.setBackgroundDrawable(sd);
        sheet.setPadding(X(20), Y(10), X(20), Y(14));
        LinearLayout handleRow = box(false);
        handleRow.setGravity(Gravity.CENTER_HORIZONTAL);
        View handle = new View(this);
        handle.setBackgroundDrawable(shape(t.chipBorder, 0, 2));
        handleRow.addView(handle, lp(32, 4));
        sheet.addView(handleRow, lp(-1, -2));
        LinearLayout chipRow = box(false);
        for (TextView c : chips) {
            c.setPadding(X(12), Y(6), X(12), Y(6));
            LinearLayout.LayoutParams cl = lp(-2, -2); cl.rightMargin = X(8);
            chipRow.addView(c, cl);
        }
        pageChip.setPadding(X(12), Y(6), X(12), Y(6));
        chipRow.addView(pageChip, lp(-2, -2));
        LinearLayout.LayoutParams crp = lp(-1, -2); crp.topMargin = Y(10);
        sheet.addView(chipRow, crp);
        LinearLayout row2 = box(false);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = box(true);
        LinearLayout labels = box(false);
        rowTitle = text("", 13, t.textDim, null);
        labels.addView(rowTitle);
        labels.addView(spacer(), lpw(1, 1f));
        style(valueLabel, 13, t.textDim, null, null);
        labels.addView(valueLabel);
        labels.addView(text(" · double-tap to reset", 13, t.textDim, null));
        col.addView(labels, lp(-1, -2));
        slider.setStyle(Slider.THICK);
        slider.setColors(0xff3f4947, t.accent, t.accent);
        col.addView(slider, lp(-1, 36));
        row2.addView(col, lpw(-2, 1f));
        captureBtn.setStyle(OverlayViews.CaptureButton.SQUARE, t.accent, t.accent, t.onAccent, 0);
        LinearLayout.LayoutParams cbp = lp(R(72), 72); cbp.leftMargin = X(16);
        row2.addView(captureBtn, cbp);
        LinearLayout.LayoutParams r2p = lp(-1, -2); r2p.topMargin = Y(8);
        sheet.addView(row2, r2p);
        put(sheet, 0, 312, 640, 168);
        menuLeft = 12; menuBottom = 172; toastTop = 64;
    }

    // ------------------------------------------------------------------ 4 Glass: floating translucent controls
    private void layoutGlass(Theme t) {
        int edge = 0x2effffff;
        LinearLayout seg = box(false);
        seg.setPadding(X(3), Y(3), X(3), Y(3));
        seg.setBackgroundDrawable(shape(t.pill, edge, 18));
        segNeg = button(text("Negative", 14, t.text, null), new View.OnClickListener() { public void onClick(View v) { if (positive) togglePositive(); } });
        segPos = button(text("Positive", 14, t.text, null), new View.OnClickListener() { public void onClick(View v) { if (!positive) togglePositive(); } });
        segNeg.setPadding(X(16), 0, X(16), 0);
        segPos.setPadding(X(16), 0, X(16), 0);
        seg.addView(segNeg, lp(-2, 30));
        seg.addView(segPos, lp(-2, 30));
        putCenter(seg, 14, -2, -2);

        LinearLayout left = box(true);
        pills(t, t.pill, t.text, 14, null, 13, framePill, basePill, wbPill, expPill);
        for (TextView p : new TextView[]{framePill, basePill, wbPill, expPill}) {
            LinearLayout.LayoutParams g = lp(-2, -2); g.bottomMargin = Y(6);
            left.addView(p, g);
        }
        put(left, 16, 16, -2, -2);
        style(menuBtn, 11, t.text, null, shape(t.pill, edge, 20));
        putRight(menuBtn, 16, 14, R(44), 40);

        LinearLayout panel = box(true);
        panel.setBackgroundDrawable(shape(t.bar, 0x29ffffff, 24));
        panel.setPadding(X(16), Y(8), X(16), Y(6));
        LinearLayout labels = box(false);
        for (int i = 0; i < chips.size(); i++) {
            TextView c = chips.get(i);
            c.setText(ROW_NAMES[i].toUpperCase());
            c.setPadding(0, 0, X(14), 0);
            style(c, 13, 0xb3ffffff, BOLD, null);
            labels.addView(c);
        }
        style(pageChip, 13, t.accent, BOLD, null);
        pageChip.setPadding(0, 0, 0, 0);
        labels.addView(pageChip);
        panel.addView(labels, lp(-1, -2));
        style(valueLabel, 22, t.text, BOLD, null);
        valueLabel.setGravity(Gravity.CENTER);
        panel.addView(valueLabel, lp(-1, -2));
        slider.setStyle(Slider.RULER);
        slider.setColors(0x8cffffff, t.accent, t.text);
        panel.addView(slider, lp(-1, 22));
        put(panel, 16, 368, 520, 96);
        captureBtn.setStyle(OverlayViews.CaptureButton.RING, t.text, t.text, 0, 4);
        putRight(captureBtn, 22, 376, R(84), 84);
        menuLeft = 16; menuBottom = 120; toastTop = 60;
    }

    // ------------------------------------------------------------------ 5 Enlarger: easel blades, filter dials, LED
    private void layoutEnlarger(Theme t) {
        int yellow = t.pillText;
        sideBars(t.pill, OverlayViews.SideBars.TICKS, yellow);
        pills(t, 0xd91a1714, yellow, 3, COND_B, 13, framePill, basePill, wbPill, expPill);
        put(pillRow(basePill, wbPill, expPill), 60, 12, -2, -2);
        enlTag = button(text("", 13, t.pill, COND_B), onMode);           // tap: positive <-> negative
        enlTag.setPadding(X(8), Y(3), X(8), Y(3));
        enlTag.setBackgroundDrawable(shape(yellow, 0, 3));
        putRight(enlTag, 60, 12, -2, -2);

        LinearLayout panel = box(false);
        panel.setBackgroundColor(t.bar);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(X(18), 0, X(18), 0);
        int[][] knob = {{0xffb6ecf7, 0xff1e9bb8, 0xff0b4e5e}, {0xfffbf3a8, 0xffc9a417, 0xff7a6208}, {0xfff7b3d8, 0xffc2307f, 0xff6d1146}};
        for (int k = 0; k < 3; k++) {
            final int r = ROW_TEMP + k;
            LinearLayout col = box(true);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            OverlayViews.Dial d = new OverlayViews.Dial(this, knob[k][0], knob[k][1], knob[k][2]);
            d.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { selectRow(r); } });
            dialFor[r] = d;
            col.addView(d, lp(R(72), 72));
            LinearLayout lab = box(false);
            TextView name = chips.get(r), val = rowVals.get(r);
            name.setText(ROW_SHORT[r] + " ");
            name.setPadding(0, 0, 0, 0);
            style(name, 14, t.text, COND_B, null);
            style(val, 14, t.text, COND_B, null);
            lab.addView(name);
            lab.addView(val);
            col.addView(lab);
            LinearLayout.LayoutParams cp = lp(-2, -2); cp.rightMargin = X(14);
            panel.addView(col, cp);
        }
        LinearLayout right = box(true);
        ledText = text("", 26, t.accent, MONO_B);
        ledText.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        ledText.setPadding(X(12), Y(2), X(12), Y(2));
        ledText.setBackgroundDrawable(shape(0xff120806, 0xff4a4d51, 6, 3));
        ledText.setShadowLayer(8, 0, 0, t.accent);
        ledText.setClickable(true);
        ledText.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            int[] order = lensControls ? new int[]{0, 1, 2, 6, 7, 8, 9} : new int[]{0, 1, 2};
            int next = order[0];
            for (int i = 0; i < order.length; i++) if (order[i] == row) next = order[(i + 1) % order.length];
            selectRow(next); } });
        right.addView(ledText, lp(-1, 48));
        slider.setStyle(Slider.LINE);
        slider.setColors(0xff55585c, t.accent, 0xffe6e6e6);
        right.addView(slider, lp(-1, 34));
        LinearLayout btns = box(false);
        style(menuBtn, 15, 0xffe6e6e6, COND_B, shape(0xff3a3d40, t.chipBorder, 6, 2));
        menuBtn.setText("MENU");
        btns.addView(menuBtn, lpw(50, 1f));
        shootText = button(text("EXPOSE", 17, t.onAccent, COND_B), onShoot);
        shootText.setBackgroundDrawable(shape(0xffb0261d, 0xff5c1410, 6, 2));
        LinearLayout.LayoutParams ep = lpw(50, 1.4f); ep.leftMargin = X(10);
        btns.addView(shootText, ep);
        LinearLayout.LayoutParams bp = lp(-1, -2); bp.topMargin = Y(4);
        right.addView(btns, bp);
        panel.addView(right, lpw(-2, 1f));
        put(panel, 0, 300, 640, 180);
        menuLeft = 60; menuBottom = 184; toastTop = 48;
    }

    // ------------------------------------------------------------------ 6 Light table: film edges, sticky notes
    private void layoutLightTable(Theme t) {
        sideBars(t.chipBorder, OverlayViews.SideBars.SPROCKETS, 0xffd8ccb0);
        pills(t, 0xd9151210, t.pillText, 2, MONO_B, 12, framePill, expPill, wbPill);
        put(pillRow(framePill, expPill), 60, 10, -2, -2);
        baseMark = text("", 22, t.accent, MARKER);
        rotate(baseMark, -6);
        putRight(baseMark, 66, 6, -2, -2);

        LinearLayout notes = box(true);
        LinearLayout row1 = box(false), row2 = box(false);
        int[] r1 = {0, 1, 2, 6, 7}, r2 = {3, 4, 5, 8, 9};
        float rot = -1.5f;
        for (int k = 0; k < 2; k++) {
            LinearLayout rowBox = k == 0 ? row1 : row2;
            for (int i : (k == 0 ? r1 : r2)) {
                LinearLayout n = note(t, i);
                rotate(n, rot);
                rot = -rot * 0.8f;
                LinearLayout.LayoutParams np = lp(-2, -2); np.rightMargin = X(8);
                rowBox.addView(n, np);
            }
        }
        style(modePill, 17, t.text, MARKER, shape(t.pill, 0, 2));
        modePill.setPadding(X(10), Y(5), X(10), Y(5));
        rotate(modePill, 1f);
        row1.addView(modePill);
        style(menuBtn, 17, t.text, MARKER, shape(t.pill, 0, 2));
        menuBtn.setPadding(X(10), Y(5), X(10), Y(5));
        menuBtn.setText("menu");
        rotate(menuBtn, -1f);
        row2.addView(menuBtn);
        style(pageChip, 17, t.text, MARKER, shape(t.pill, 0, 2));
        pageChip.setPadding(X(10), Y(5), X(10), Y(5));
        LinearLayout.LayoutParams pp = lp(-2, -2); pp.leftMargin = X(8);
        row2.addView(pageChip, pp);
        notes.addView(row1);
        LinearLayout.LayoutParams r2p = lp(-2, -2); r2p.topMargin = Y(8);
        notes.addView(row2, r2p);
        put(notes, 60, 316, 440, -2);

        captureBtn.setStyle(OverlayViews.CaptureButton.LOUPE, 0xff4a3c2c, 0xff2a2118, t.text, 6);
        putRight(captureBtn, 60, 316, R(84), 84);

        LinearLayout strip = box(false);
        strip.setBackgroundColor(t.bar);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(X(7), Y(2), X(12), Y(2));
        style(wbPill, 13, t.pillText, MONO_B, null);
        strip.addView(wbPill);
        slider.setStyle(Slider.LINE);
        slider.setColors(0xff5a4a38, t.accent, t.text);
        strip.addView(slider, lpw(-1, 1f));
        style(valueLabel, 16, t.text, MONO_B, null);
        strip.addView(valueLabel, lp(84, -2));
        put(strip, 50, 420, 540, 50);
        menuLeft = 60; menuBottom = 64; toastTop = 52;
    }

    /** A sticky note for a row: "black 791" in a marker hand; the selected one is circled in red (styleChips). */
    private LinearLayout note(Theme t, final int i) {
        LinearLayout n = box(false);
        n.setPadding(X(9), Y(5), X(9), Y(5));
        TextView name = chips.get(i), val = rowVals.get(i);
        name.setText(ROW_NAMES[i].toLowerCase() + " ");
        name.setPadding(0, 0, 0, 0);
        style(name, 17, t.text, MARKER, null);
        style(val, 17, t.text, MARKER, null);
        n.addView(name);
        n.addView(val);
        n.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { selectRow(i); } });
        name.setClickable(false);
        rowBoxes.set(i, n);
        return n;
    }

    // ------------------------------------------------------------------ 7 Scope: HUD, readout panel, histogram
    private void layoutScope(Theme t) {
        sideBars(0xff000000, OverlayViews.SideBars.SCALE, 0xff2c2c2c);
        LinearLayout hud = box(false);
        pills(t, 0xb3000000, t.accent, 0, MONO, 12, modePill, framePill, expPill);
        for (TextView p : new TextView[]{modePill, framePill, expPill}) {
            p.setPadding(X(6), Y(2), X(6), Y(2));
            hud.addView(p);
        }
        put(hud, 56, 6, -2, -2);

        LinearLayout side = box(true);
        side.setBackgroundColor(t.bar);
        side.setPadding(X(10), Y(8), X(10), Y(8));
        side.addView(text("LEVELS (IN 0–1023)", 11, t.textDim, MONO));
        for (int i = 0; i < 3; i++) side.addView(scopeRow(t, i), lp(-1, 22));
        side.addView(text("COLOR", 11, t.textDim, MONO), topMargin(6));
        for (int i = 3; i < 6; i++) side.addView(scopeRow(t, i), lp(-1, 22));
        if (lensControls) {
            side.addView(text("EXPOSURE", 11, t.textDim, MONO), topMargin(6));
            for (int i = 6; i < 10; i++) side.addView(scopeRow(t, i), lp(-1, 22));
        }
        side.addView(text("BASE", 11, t.textDim, MONO), topMargin(6));
        pills(t, 0, t.text, 0, MONO, 12, basePill, wbPill);
        basePill.setPadding(0, 0, 0, 0);
        wbPill.setPadding(0, 0, 0, 0);
        side.addView(basePill);
        side.addView(wbPill);
        side.addView(spacer(), new LinearLayout.LayoutParams(1, 0, 1f));
        slider.setStyle(Slider.FLAT);
        slider.setColors(t.chipBorder, t.accent, t.accent);
        side.addView(slider, lp(-1, 30));
        put(side, 470, 0, 170, 344);

        LinearLayout bottom = box(false);
        bottom.setBackgroundColor(t.bar);
        bottom.setPadding(X(10), Y(8), X(10), Y(8));
        histogram = new OverlayViews.Histogram(this);
        histogram.setColors(0xff222222, t.textDim);
        histogram.setBins(lastHist, lastHistName == null ? null : "LAST CAPTURE " + lastHistName + " · RGB");
        bottom.addView(histogram, lpw(-1, 1f));
        LinearLayout btns = box(true);
        shootText = button(text("● REC", 16, t.text, MONO_B), onShoot);
        shootText.setBackgroundDrawable(shape(0xff111111, t.text, 0, 2));
        btns.addView(shootText, lp(112, 66));
        style(menuBtn, 13, t.accent, MONO_B, shape(0, t.accent, 0, 1));
        menuBtn.setText("MENU");
        LinearLayout.LayoutParams mp = lp(112, 40); mp.topMargin = Y(6);
        btns.addView(menuBtn, mp);
        LinearLayout.LayoutParams bp = lp(-2, -1); bp.leftMargin = X(10);
        bottom.addView(btns, bp);
        put(bottom, 0, 344, 640, 136);
        menuLeft = 56; menuBottom = 140; toastTop = 40;
    }

    private LinearLayout scopeRow(Theme t, final int i) {
        LinearLayout r = box(false);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = chips.get(i), val = rowVals.get(i);
        name.setPadding(0, 0, 0, 0);
        style(name, 13, t.text, MONO, null);
        style(val, 13, t.text, MONO, null);
        r.addView(name);
        r.addView(spacer(), lpw(1, 1f));
        r.addView(val);
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { selectRow(i); } });
        rowBoxes.set(i, r);
        return r;
    }

    // ------------------------------------------------------------------ 8 Safelight: huge targets, red only
    private void layoutSafelight(Theme t) {
        int red = t.accent;
        pills(t, 0xcc000000, red, 4, BOLD, 13, framePill, basePill, wbPill, expPill);
        put(pillRow(framePill, basePill, wbPill, expPill), 56, 10, -2, -2);

        LinearLayout bottom = box(false);
        bottom.setBackgroundColor(t.bar);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(X(16), 0, X(16), 0);
        TextView minus = button(text("−", 40, red, BOLD), new View.OnClickListener() { public void onClick(View v) { if (!menuOpen) stepRow(-1); } });
        minus.setBackgroundDrawable(shape(0xff000000, red, 14, 3));
        bottom.addView(minus, lp(80, 72));
        LinearLayout mid = box(true);
        mid.setGravity(Gravity.CENTER);
        rowTitle = text("", 15, red, BOLD);
        rowTitle.setGravity(Gravity.CENTER);
        mid.addView(rowTitle);
        style(valueLabel, 34, red, BOLD, null);
        valueLabel.setGravity(Gravity.CENTER);
        valueLabel.setLongClickable(true);
        valueLabel.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { resetRow(); return true; } });
        mid.addView(valueLabel);
        slider.setStyle(Slider.FLAT);
        slider.setColors(0xff5a1410, red, red);
        mid.addView(slider, lp(-1, 22));
        bottom.addView(mid, lpw(-2, 1f));
        TextView plus = button(text("+", 40, red, BOLD), new View.OnClickListener() { public void onClick(View v) { if (!menuOpen) stepRow(1); } });
        plus.setBackgroundDrawable(shape(0xff000000, red, 14, 3));
        bottom.addView(plus, lp(80, 72));
        put(bottom, 0, 376, 490, 104);

        LinearLayout col = box(true);
        col.setBackgroundColor(t.bar);
        col.setPadding(X(12), Y(14), X(14), Y(14));
        TextView next = button(text("NEXT ROW", 18, red, BOLD), new View.OnClickListener() { public void onClick(View v) { selectRow(row + 1); } });
        next.setBackgroundDrawable(shape(0xff000000, red, 14, 3));
        col.addView(next, lp(-1, 64));
        style(modePill, 18, red, BOLD, shape(0xff000000, red, 14, 3));
        modePill.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams g1 = lp(-1, 64); g1.topMargin = Y(12);
        col.addView(modePill, g1);
        style(menuBtn, 18, red, BOLD, shape(0xff000000, red, 14, 3));
        menuBtn.setText("MENU");
        LinearLayout.LayoutParams g2 = lp(-1, 64); g2.topMargin = Y(12);
        col.addView(menuBtn, g2);
        shootText = button(text("SHOOT", 22, 0xff000000, BOLD), onShoot);
        shootText.setBackgroundDrawable(shape(red, 0, 14));
        LinearLayout.LayoutParams g3 = new LinearLayout.LayoutParams(-1, 0, 1f); g3.topMargin = Y(12);
        col.addView(shootText, g3);
        put(col, 490, 0, 150, 480);
        menuLeft = 56; menuBottom = 108; toastTop = 52;
    }

    /** Selection highlight per layout (and which rows show: the color page or, with Lens controls, exposure). */
    private void styleChips() {
        Theme t = themes.get(themeIdx);
        boolean expPage = row >= ROW_EXP;
        for (int i = 0; i < chips.size(); i++) {
            TextView c = chips.get(i), v = rowVals.get(i);
            View box = rowBoxes.get(i);
            boolean sel = i == row;
            boolean vis = "scope".equals(layout) ? (i < ROW_EXP || lensControls) : (i >= ROW_EXP) == expPage;
            if ("enlarger".equals(layout)) vis = true;
            box.setVisibility(vis ? View.VISIBLE : View.GONE);
            if ("camera".equals(layout)) {
                box.setBackgroundColor(sel ? t.accent : 0);
                c.setTextColor(sel ? t.onAccent : t.text);
                v.setTextColor(sel ? t.onAccent : t.textDim);
                c.setTypeface(sel ? COND_B : COND);
                v.setTypeface(sel ? COND_B : COND);
            } else if ("glass".equals(layout)) {
                c.setTextColor(sel ? t.accent : 0xb3ffffff);
            } else if ("lighttable".equals(layout)) {
                box.setBackgroundDrawable(shape(t.pill, sel ? t.accent : 0, 2, 3));
            } else if ("scope".equals(layout)) {
                c.setText((sel ? "› " : "  ") + ROW_SHORT[i]);
                c.setTextColor(sel ? t.accent : t.text);
                v.setTextColor(sel ? t.accent : t.text);
            } else if ("enlarger".equals(layout)) {
                c.setTextColor(sel ? t.accent : t.text);
                v.setTextColor(sel ? t.accent : t.text);
                if (dialFor[i] != null) dialFor[i].setSelected(sel, t.accent);
            } else if (!"safelight".equals(layout)) {      // minimal, material: chips
                float r = "material".equals(layout) ? 8 : Math.min(8, t.radius);
                c.setTextSize(sp("material".equals(layout) ? 14 : 13));
                c.setTextColor(sel ? t.onAccent : ("material".equals(layout) ? t.text : t.textDim));
                c.setBackgroundDrawable(sel ? shape(t.accent, 0, r) : shape(0, t.chipBorder, r));
            }
        }
        pageChip.setVisibility(lensControls && !"scope".equals(layout) ? View.VISIBLE : View.GONE);
        pageChip.setText("lighttable".equals(layout) ? (expPage ? "‹ color" : "exposure ›") : expPage ? "‹ Color" : "Exposure ›");
        if ("minimal".equals(layout) || "material".equals(layout)) {
            pageChip.setTextSize(sp(13));
            pageChip.setTextColor(t.text);
            pageChip.setBackgroundDrawable(shape(0, t.chipBorder, "material".equals(layout) ? 8 : Math.min(8, t.radius)));
        }
    }

    /** RGB histogram (64 bins) of a capture, for the Scope layout. Worker thread. */
    private void updateHistogram(File jpg) {
        if (!"scope".equals(layout) || jpg == null) return;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(jpg.getAbsolutePath(), o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = Math.max(1, o.outWidth / 320);
            Bitmap bm = BitmapFactory.decodeFile(jpg.getAbsolutePath(), o);
            if (bm == null) return;
            int w = bm.getWidth(), h = bm.getHeight();
            int[] px = new int[w * h];
            bm.getPixels(px, 0, w, 0, 0, w, h);
            bm.recycle();
            int[][] b = new int[3][64];
            for (int c : px) { b[0][((c >> 16) & 255) >> 2]++; b[1][((c >> 8) & 255) >> 2]++; b[2][(c & 255) >> 2]++; }
            lastHist = b;
            lastHistName = jpg.getName();
            ui.post(new Runnable() { public void run() {
                if (histogram != null) histogram.setBins(lastHist, "LAST CAPTURE " + lastHistName + " · RGB"); } });
        } catch (Throwable t) { err("histogram", t); }
    }

    private int rowCount() { return lensControls ? ROW_NAMES.length : ROW_EXP; }

    private void showToast(final String s) {
        out("toast: " + s);
        ui.post(new Runnable() {
            public void run() {
                toast.setText(s);
                toast.setVisibility(View.VISIBLE);
                ui.removeCallbacks(hideToast);
                ui.postDelayed(hideToast, 5000);
            }
        });
    }

    private final Runnable hideToast = new Runnable() { public void run() { toast.setVisibility(View.GONE); } };

    private void refreshPills() {
        ui.post(new Runnable() {
            public void run() {
                modePill.setText(positive ? "POS" : "NEG");
                framePill.setText(busy ? "Working…" : capturing ? "Saving…" : live ? "Live" : "Starting…");
                basePill.setText(wbActive ? "Base set" : "Base: not set");
                wbPill.setText(wbActive ? temp + " K · " + shiftLabel() : "Camera WB");
                captureBtn.setBusy(capturing || busy);
                expPill.setVisibility(lensControls && live ? View.VISIBLE : View.GONE);
                if (lensControls) expPill.setText(exposure.summary());
                // layout-specific status
                if (segPos != null) {
                    Theme t = themes.get(themeIdx);
                    segPos.setBackgroundDrawable(positive ? shape(0xebffffff, 0, Y(15)) : null);
                    segPos.setTextColor(positive ? 0xff111111 : 0xd9ffffff);
                    segNeg.setBackgroundDrawable(positive ? null : shape(0xebffffff, 0, Y(15)));
                    segNeg.setTextColor(positive ? 0xd9ffffff : 0xff111111);
                }
                if (baseMark != null) baseMark.setText(wbActive ? "base set" : "no base");
                if (enlTag != null) enlTag.setText((positive ? "POSITIVE" : "NEGATIVE") + " · " + framePill.getText().toString().toUpperCase());
                if (shootText != null) {
                    alpha(shootText, capturing || busy ? 0.5f : 1f);
                    if ("safelight".equals(layout)) shootText.setText(capturing ? "SAVING" : busy ? "WORKING" : "SHOOT");
                }
            }
        });
    }

    private String shiftLabel() {
        String ab = shiftAB == 0 ? "" : (shiftAB > 0 ? "B" : "A") + Math.abs(shiftAB);
        String gm = shiftGM == 0 ? "" : (shiftGM > 0 ? "M" : "G") + Math.abs(shiftGM);
        String s = (ab + " " + gm).trim();
        return s.length() == 0 ? "neutral" : s;
    }

    // ------------------------------------------------------------------ rows
    private void selectRow(int r) {
        if (menuOpen) return;
        row = (r + rowCount()) % rowCount();
        refreshRow();
    }

    private String rowValue() { return rowValue(row); }

    private String rowValue(int row) {
        if (row >= ROW_EXP) { try { return exposure.label(row - ROW_EXP); } catch (Throwable t) { return "–"; } }
        switch (row) {
            case ROW_BLACK: return String.valueOf(inHi);
            case ROW_WHITE: return String.valueOf(inLo);
            case ROW_GAMMA: return String.format("%.2f", gammaX100 / 100.0);
            case ROW_TEMP: return wbActive ? temp + "K" : "cam";
            case ROW_AB: return (shiftAB > 0 ? "B" : shiftAB < 0 ? "A" : "") + Math.abs(shiftAB);
            default: return (shiftGM > 0 ? "M" : shiftGM < 0 ? "G" : "") + Math.abs(shiftGM);
        }
    }

    private int[] sliderRange() { return sliderRange(row); }

    private int[] sliderRange(int row) {   // {max, value}
        if (row >= ROW_EXP) return exposure.range(row - ROW_EXP);
        switch (row) {
            case ROW_BLACK: return new int[]{127, inHi / 8};
            case ROW_WHITE: return new int[]{127, inLo / 8};
            case ROW_GAMMA: return new int[]{40, (gammaX100 - 50) / 5};
            case ROW_TEMP: return new int[]{74, (temp - 2500) / 100};
            case ROW_AB: return new int[]{64, shiftAB + 32};
            default: return new int[]{64, shiftGM + 32};
        }
    }

    private void refreshRow() {
        final int[] r = sliderRange();
        final String v = rowValue();
        ui.post(new Runnable() {
            public void run() {
                slider.setRange(r[0], r[1]);
                valueLabel.setText(v);
                refreshRowValues();
                styleChips();
            }
        });
        refreshPills();
    }

    /** Every row's value where the layout lists them, the dials, the LED and the row title. UI thread. */
    private void refreshRowValues() {
        for (int i = 0; i < rowVals.size(); i++) {
            rowVals.get(i).setText(rowValue(i));
            if (dialFor[i] != null) { int[] r = sliderRange(i); dialFor[i].setFraction(r[1] / (float) Math.max(1, r[0])); }
        }
        if (ledText != null) ledText.setText(ROW_SHORT[row] + " " + rowValue());
        if (rowTitle != null) rowTitle.setText("safelight".equals(layout) ? ROW_LONG[row].toUpperCase() : ROW_LONG[row]);
    }

    private void setRowFromSlider(int p) {
        if (busy) return;
        if (row >= ROW_EXP) { exposure.setFromSlider(row - ROW_EXP, p); return; }   // label follows the camera's reply
        switch (row) {
            case ROW_BLACK: inHi = Math.max(inLo + 8, Math.min(1023, p * 8 + 7)); requestCurve(); break;
            case ROW_WHITE: inLo = Math.min(inHi - 8, p * 8); requestCurve(); break;
            case ROW_GAMMA: gammaX100 = 50 + 5 * p; requestCurve(); break;
            case ROW_TEMP: temp = 2500 + 100 * p; applyWb(); break;
            case ROW_AB: shiftAB = p - 32; applyWb(); break;
            default: shiftGM = p - 32; applyWb(); break;
        }
        ui.post(new Runnable() { public void run() { valueLabel.setText(rowValue()); refreshRowValues(); } });
        refreshPills();
    }

    private void stepRow(int dir) {
        if (row >= ROW_EXP) { if (!busy) exposure.step(row - ROW_EXP, dir); return; }
        int[] r = sliderRange();
        int v = Math.max(0, Math.min(r[0], r[1] + dir));
        setRowFromSlider(v);
        refreshRow();
    }

    private void resetRow() {
        if (busy) return;
        if (row >= ROW_EXP) { exposure.reset(row - ROW_EXP); return; }
        switch (row) {
            case ROW_BLACK: inHi = 1023; requestCurve(); break;
            case ROW_WHITE: inLo = 0; requestCurve(); break;
            case ROW_GAMMA: gammaX100 = 85; requestCurve(); break;
            // one row only (0.16 reset all three WB rows): Temp back to 5500 K, a shift back to 0. Back to the
            // camera's own WB only when nothing is left to apply.
            case ROW_TEMP: temp = 5500; resetWbRow(); break;
            case ROW_AB: shiftAB = 0; resetWbRow(); break;
            default: shiftGM = 0; resetWbRow(); break;
        }
        out("reset " + ROW_NAMES[row]);
        refreshRow();
    }

    // ------------------------------------------------------------------ menu
    private interface Action { void run(); }

    private final List<Action> menuActions = new ArrayList<Action>();

    private void addMenu(String label, Action a) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setPadding(14, 9, 14, 9);
        final int idx = menuItems.size();
        t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { menuSel = idx; activateMenu(); } });
        menuPanel.addView(t, new LinearLayout.LayoutParams(-1, -2));
        menuItems.add(t);
        menuActions.add(a);
    }

    private void rebuildMenu() {
        menuPanel.removeAllViews();
        menuItems.clear();
        menuActions.clear();
        addMenu("Calibrate film base", new Action() { public void run() { runAsync(new Runnable() { public void run() { calibrateBase(); } }); } });
        addMenu("Auto levels (this frame)", new Action() { public void run() { runAsync(new Runnable() { public void run() { autoLevels(); } }); } });
        addMenu("Check alignment", new Action() { public void run() { runAsync(new Runnable() { public void run() { checkAlignment(); } }); } });
        addMenu("Guides: " + (guidesOn ? "on" : "off"), new Action() { public void run() {
            guidesOn = !guidesOn; guides.setVisibility(guidesOn ? View.VISIBLE : View.GONE); savePrefs(); } });
        addMenu("Capture delay: " + delaySec + " s", new Action() { public void run() { delaySec = delaySec == 0 ? 2 : 0; savePrefs(); } });
        addMenu("Silent shutter: " + (silent ? "on" : "off"), new Action() { public void run() {
            silent = !silent; savePrefs(); setParams("silent-shutter-mode", silent ? "true" : "false"); } });
        addMenu("Lens controls: " + (lensControls ? "on" : "off (manual lens)"), new Action() { public void run() {
            lensControls = !lensControls; savePrefs();
            if (lensControls) {
                synchronized (MainActivity.this) { if (cameraEx != null) exposure.attach(cameraEx, true); }
                showToast(exposure.electronicLens() ? "Lens controls for " + exposure.lensName() + ": tap Exposure ›"
                        : "No electronic lens found: aperture needs a lens with contacts");
            } else {
                exposure.detach();
                if (row >= ROW_EXP) row = ROW_BLACK;
            }
            if ("scope".equals(layout)) buildOverlay(); else refreshRow(); } });   // Scope lists the exposure rows
        addMenu("Controls: " + (autoHide ? "hide when idle" : "always shown"), new Action() { public void run() {
            autoHide = !autoHide; savePrefs(); poke(); } });
        addMenu("Circle shape: " + aspectPct + "% wide", new Action() { public void run() {
            aspectPct = aspectPct <= 75 ? 100 : aspectPct - 5; savePrefs(); applyAspect();
            showToast("Circle shape " + aspectPct + "%: pick what looks round"); } });
        addMenu("Theme: " + themes.get(themeIdx).name, new Action() { public void run() {
            themeIdx = (themeIdx + 1) % themes.size(); savePrefs(); applyTheme();
            showToast("Theme " + themes.get(themeIdx).name + " (" + themes.get(themeIdx).source + ")"); } });
        addMenu("Flip display: " + (flipMode == 1 ? "on" : "off"), new Action() { public void run() {
            flipMode = 1 - flipMode; savePrefs(); applyWindowFlip(); } });
        addMenu("Develop newest frame", new Action() { public void run() { runAsync(new Runnable() { public void run() { developNewest(); } }); } });
        addMenu(logScroll.getVisibility() == View.VISIBLE ? "Hide log" : "Show log", new Action() { public void run() {
            logScroll.setVisibility(logScroll.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); } });
        addMenu("Reset roll (levels + WB)", new Action() { public void run() {
            inLo = 0; inHi = 1023; gammaX100 = 85; wbActive = false; temp = 5500; shiftAB = 0; shiftGM = 0;
            restoreWbOnly(); requestCurve(); saveRoll(); refreshRow(); showToast("Roll reset"); } });
        addMenu("Exit", new Action() { public void run() { exitApp(); } });
        menuSel = Math.min(menuSel, menuItems.size() - 1);
        if (themes != null) styleMenu();
        fixAspect(menuPanel);
    }

    private void styleMenu() {
        Theme t = themes.get(themeIdx);
        for (int i = 0; i < menuItems.size(); i++) {
            TextView m = menuItems.get(i);
            boolean sel = i == menuSel;
            m.setTextSize(sp(15));
            m.setTextColor(sel ? t.onAccent : t.text);
            m.setBackgroundDrawable(sel ? shape(t.accent, 0, Math.min(8, t.radius)) : null);
        }
        menuScroll.post(new Runnable() { public void run() { scrollMenuToCursor(); } });
    }

    /** Scrolls the menu just enough that the selected item is fully visible. */
    private void scrollMenuToCursor() {
        if (menuSel < 0 || menuSel >= menuItems.size()) return;
        View v = menuItems.get(menuSel);
        int top = v.getTop() - 8, bottom = v.getBottom() + 8, y = menuScroll.getScrollY(), h = menuScroll.getHeight();
        if (h <= 0) return;
        if (top < y) menuScroll.scrollTo(0, Math.max(0, top));
        else if (bottom > y + h) menuScroll.scrollTo(0, bottom - h);
    }

    private void toggleMenu() {
        menuOpen = !menuOpen;
        if (menuOpen) rebuildMenu();
        menuScroll.setVisibility(menuOpen ? View.VISIBLE : View.GONE);
    }

    private void activateMenu() {
        Action a = menuActions.get(menuSel);
        out("menu: " + menuItems.get(menuSel).getText());
        menuOpen = false;
        menuScroll.setVisibility(View.GONE);
        a.run();
    }

    private void runAsync(final Runnable r) {
        if (busy || capturing) { showToast("Busy, try again in a moment"); return; }
        new Thread(new Runnable() { public void run() {
            busy = true; refreshPills();
            try { r.run(); } catch (Throwable t) { err("action", t); showToast("Failed: " + t.getMessage()); }
            finally { busy = false; refreshPills(); }
        } }).start();
    }

    // ================================================================== input
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        poke();
        int scan = e.getScanCode();
        if (isMenuOrBack(scan) || keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BACK) { toggleMenu(); return true; }
        if (isEnter(scan) || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (menuOpen) activateMenu();
            else togglePositive();
            return true;
        }
        if (isShutter(scan)) { onShutterAction(); return true; }
        if (isHalfPress(scan)) {                            // autofocus only with Lens controls on (manual lens: nothing to do)
            if (lensControls && exposure.electronicLens() && cameraEx != null && !busy && !capturing && e.getRepeatCount() == 0) {
                try { cameraEx.getNormalCamera().autoFocus(null); } catch (Throwable t) { err("autofocus", t); }
            }
            return true;
        }
        // With Flip on the screen is upside down, so the four-way keys follow what the screen shows: the key at
        // the top of the camera body (now at the bottom of the picture) moves down. The dials are not flipped.
        boolean flip = flipMode == 1;
        boolean up = scan == com.sony.scalar.sysutil.ScalarInput.ISV_KEY_UP || keyCode == KeyEvent.KEYCODE_DPAD_UP;
        boolean down = scan == com.sony.scalar.sysutil.ScalarInput.ISV_KEY_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean right = scan == com.sony.scalar.sysutil.ScalarInput.ISV_KEY_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean left = scan == com.sony.scalar.sysutil.ScalarInput.ISV_KEY_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
        if (flip) { boolean u = up; up = down; down = u; boolean r = right; right = left; left = r; }
        if (up) {
            if (menuOpen) { menuSel = (menuSel + menuItems.size() - 1) % menuItems.size(); styleMenu(); } else selectRow(row - 1);
            return true;
        }
        if (down) {
            if (menuOpen) { menuSel = (menuSel + 1) % menuItems.size(); styleMenu(); } else selectRow(row + 1);
            return true;
        }
        if (right || isNext(scan)) { if (!menuOpen) stepRow(+1); return true; }
        if (left || isPrev(scan)) { if (!menuOpen) stepRow(-1); return true; }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (isHalfPress(e.getScanCode())) {                 // as PMCADemo: focus is held only while half-pressed
            if (lensControls && exposure.electronicLens() && cameraEx != null && !capturing) {
                try { cameraEx.getNormalCamera().cancelAutoFocus(); } catch (Throwable t) { err("cancel autofocus", t); }
            }
            return true;
        }
        return super.onKeyUp(keyCode, e);
    }

    private void exitApp() {
        if (capturing || busy) { showToast("Still saving, try again in a moment"); return; }
        out("exit requested, " + now());
        unflipForExit();
        finish();
    }

    // ================================================================== flip (window rotation; see probe5-8.md)
    private void applyWindowFlip() {
        try {
            setRequestedOrientation(flipMode == 1 ? 8 /* REVERSE_LANDSCAPE, API 9 */
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } catch (Throwable t) { err("flip", t); }
    }

    private void unflipForExit() {
        try { setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE); } catch (Throwable ignored) {}
    }

    // ================================================================== prefs + roll profile (8.3 names)
    private void loadPrefs() {
        try {
            android.content.SharedPreferences p = getSharedPreferences("fi", MODE_PRIVATE);
            int old = p.getInt("flip", 0);
            flipMode = p.getInt("flip2", old == 1 || old == 2 ? 1 : 0);
            guidesOn = p.getBoolean("guides", false);
            delaySec = p.getInt("delay", 0);
            silent = p.getBoolean("silent", false);
            themeIdx = p.getInt("theme", 0);
            lensControls = p.getBoolean("lens", false);
            autoHide = p.getBoolean("autohide", true);
            aspectPct = p.getInt("aspect", 100);
        } catch (Throwable ignored) {}
    }

    private void savePrefs() {
        try {
            getSharedPreferences("fi", MODE_PRIVATE).edit().putInt("flip2", flipMode).putBoolean("guides", guidesOn)
                    .putInt("delay", delaySec).putBoolean("silent", silent).putInt("theme", themeIdx)
                    .putBoolean("lens", lensControls).putBoolean("autohide", autoHide).putInt("aspect", aspectPct).commit();
        } catch (Throwable ignored) {}
    }

    private File rollFile() { return new File(card, "FILMINV/ROLL.TXT"); }

    private void loadRoll() {
        try {
            File f = rollFile();
            if (!f.exists()) return;
            byte[] b = new byte[(int) Math.min(f.length(), 8192)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b);
            in.close();
            JSONObject o = new JSONObject(new String(b, 0, Math.max(0, n), "UTF-8"));
            inLo = o.optInt("inLo", 0); inHi = o.optInt("inHi", 1023); gammaX100 = o.optInt("gamma", 85);
            wbActive = o.optBoolean("wb", false); temp = o.optInt("temp", 5500);
            shiftAB = o.optInt("ab", 0); shiftGM = o.optInt("gm", 0);
        } catch (Throwable t) { err("load roll", t); }
    }

    private void saveRoll() {
        try {
            JSONObject o = new JSONObject();
            o.put("inLo", inLo); o.put("inHi", inHi); o.put("gamma", gammaX100);
            o.put("wb", wbActive); o.put("temp", temp); o.put("ab", shiftAB); o.put("gm", shiftGM);
            File f = rollFile();
            f.getParentFile().mkdirs();
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(o.toString().getBytes("UTF-8"));
            fo.close();
        } catch (Throwable t) { err("save roll", t); }
    }

    // ================================================================== camera: live view
    private void startLiveAsync() {
        new Thread(new Runnable() { public void run() { startLive(); } }).start();
    }

    private synchronized void startLive() {
        if (live || cameraEx != null || !surfaceReady) return;
        try {
            cameraEx = CameraEx.open(0, null);
            Camera cam = cameraEx.getNormalCamera();
            Camera.Parameters p = cam.getParameters();
            savedParams.clear();
            for (String k : SAVED_KEYS) savedParams.put(k, p.get(k));
            out("saved settings: " + savedParams);
            paramsChanged = false;
            if ("raw".equals(savedParams.get("storage-fmt"))) setParamsWith(cam, "storage-fmt", "rawjpeg");
            if (silent) setParamsWith(cam, "silent-shutter-mode", "true");
            if (wbActive) applyWbWith(cam);
            cam.setPreviewDisplay(surface.getHolder());
            cam.startPreview();
            live = true;
            applyCurve();
            exposure.attach(cameraEx, lensControls);        // off: only reads the lens info (for the hint below)
            if (!lensControls && exposure.electronicLens() && !lensHintShown) {
                lensHintShown = true;
                showToast("Electronic lens found (" + exposure.lensName() + "): Menu › Lens controls");
            }
            refreshRow();
            out("live view started, " + now() + "; Android sees: " + dcimSummary());
        } catch (Throwable t) {
            err("start live view", t);
            showToast("Camera could not start (see log)");
            restoreCamera();
        }
    }

    private void requestCurve() {
        long wait = lastCurveAt + CURVE_MIN_MS - System.currentTimeMillis();
        if (wait <= 0) { applyCurve(); return; }
        if (curvePending) return;
        curvePending = true;
        ui.postDelayed(new Runnable() { public void run() { curvePending = false; applyCurve(); } }, wait);
    }

    /** Gamma table (probe 3 format: 1024 x u16 LE, 0..1023), two reused buffers per session. */
    private synchronized void applyCurve() {
        if (cameraEx == null) return;
        lastCurveAt = System.currentTimeMillis();
        try {
            if (tables[nextTable] == null) { tables[nextTable] = cameraEx.createGammaTable(); tablesCreated++; }
            CameraEx.GammaTable t = tables[nextTable];
            byte[] b = new byte[t.getSize()];
            double span = Math.max(8, inHi - inLo), g = gammaX100 / 100.0;
            for (int i = 0; i < 1024 && 2 * i + 1 < b.length; i++) {
                int v;
                if (measureCurve) v = i;
                else {
                    double x = positive ? (inHi - i) / span : (i - inLo) / span;
                    x = Math.max(0, Math.min(1, x));
                    v = (int) Math.round(1023 * Math.pow(x, 1.0 / g));
                }
                b[2 * i] = (byte) v;
                b[2 * i + 1] = (byte) (v >> 8);
            }
            t.write(b);
            cameraEx.setExtendedGammaTable(t);
            nextTable ^= 1;
        } catch (Throwable t) {
            err("apply curve", t);
        }
    }

    private void applyWb() {
        wbActive = true;
        if (cameraEx != null) applyWbWith(cameraEx.getNormalCamera());
    }

    private void applyWbWith(Camera cam) {
        setParamsWith(cam, "whitebalance", "color-temp", "color-temperture-white-balance", String.valueOf(temp),
                "white-balance-shift-mode", "true", "white-balance-shift-lb", String.valueOf(shiftAB),
                "white-balance-shift-cc", String.valueOf(shiftGM));
    }

    /** Back to the camera's own white balance (as saved when live view started). */
    private void restoreWbOnly() {
        if (cameraEx == null) return;
        String[] k = {"whitebalance", "color-temperture-white-balance", "white-balance-shift-mode", "white-balance-shift-lb", "white-balance-shift-cc"};
        List<String> kv = new ArrayList<String>();
        for (String key : k) if (savedParams.get(key) != null) { kv.add(key); kv.add(savedParams.get(key)); }
        setParams(kv.toArray(new String[kv.size()]));
    }

    private synchronized void setParams(String... kv) {
        if (cameraEx == null) return;
        setParamsWith(cameraEx.getNormalCamera(), kv);
    }

    private void setParamsWith(Camera cam, String... kv) {
        try {
            Camera.Parameters p = cam.getParameters();
            for (int i = 0; i + 1 < kv.length; i += 2) p.set(kv[i], kv[i + 1]);
            cam.setParameters(p);
            paramsChanged = true;
            Camera.Parameters r = cam.getParameters();
            StringBuilder s = new StringBuilder("set");
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

    /** Undo everything (settings read back), curve off, preview stopped, tables and camera released. */
    private synchronized void restoreCamera() {
        if (cameraEx == null) { live = false; return; }
        long t0 = System.currentTimeMillis();
        try {
            if (paramsChanged && !savedParams.isEmpty()) {
                Camera cam = cameraEx.getNormalCamera();
                Camera.Parameters p = cam.getParameters();
                for (Map.Entry<String, String> e : savedParams.entrySet()) if (e.getValue() != null) p.set(e.getKey(), e.getValue());
                cam.setParameters(p);
                Camera.Parameters r = cam.getParameters();
                StringBuilder s = new StringBuilder("settings restored:");
                for (Map.Entry<String, String> e : savedParams.entrySet()) {
                    String got = r.get(e.getKey());
                    boolean ok = e.getValue() == null ? got == null : e.getValue().equals(got);
                    s.append(' ').append(e.getKey()).append(ok ? "=ok" : "=MISMATCH(" + got + " vs " + e.getValue() + ")");
                }
                out(s.toString());
            }
        } catch (Throwable t) { err("restore settings", t); }
        exposure.detach();
        try { cameraEx.setExtendedGammaTable(null); } catch (Throwable t) { err("restore: curve off", t); }
        try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        try { cameraEx.getNormalCamera().stopPreview(); } catch (Throwable t) { err("restore: stopPreview", t); }
        for (int i = 0; i < tables.length; i++) {
            if (tables[i] != null) { try { tables[i].release(); } catch (Throwable t) { err("release table", t); } tables[i] = null; }
        }
        try { cameraEx.release(); } catch (Throwable t) { err("camera release", t); }
        out("camera restored in " + (System.currentTimeMillis() - t0) + " ms (" + tablesCreated + " tables)");
        cameraEx = null;
        tablesCreated = 0;
        nextTable = 0;
        live = false;
        paramsChanged = false;
    }

    // ================================================================== capture
    private void onShutterAction() {
        if (menuOpen) return;
        if (capturing || busy) { showToast("Saving, wait a moment"); return; }
        if (delaySec > 0) {
            capturing = true;
            refreshPills();
            showToast("Capturing in " + delaySec + " s");
            ui.postDelayed(new Runnable() { public void run() { capturing = false; capture(); } }, delaySec * 1000L);
        } else {
            capture();
        }
    }

    /** Full-quality capture with the current curve (POSITIVE: the JPEG is a full-res positive). */
    private void capture() {
        if (cameraEx == null || capturing) return;
        capturing = true;
        refreshPills();
        final boolean pos = positive;
        out("CAPTURE " + (pos ? "POSITIVE" : "NEGATIVE") + " in " + inLo + ".." + inHi + " g" + gammaX100
                + (wbActive ? " " + temp + "K " + shiftLabel() : " camWB"));
        new Thread(new Runnable() { public void run() {
            try {
                ShotResult r = shoot(15000);
                out("JPEG from capture: " + (r.file == null ? "none (RAW only?)" : r.file.getName()));
                if (pos && r.file != null) rememberSkip(r.file.getName());
                updateHistogram(r.file);
                showToast(r.file == null ? "Captured (no JPEG found: RAW only?)"
                        : "Captured " + r.file.getName());
            } catch (Throwable t) {
                err("capture", t);
                showToast("Capture failed: " + t.getMessage());
            } finally {
                capturing = false;
                refreshPills();
            }
        } }).start();
    }

    /** Every JPEG under DCIM (all folders: the camera starts a new folder each day, mid-session too). */
    private java.util.Set<String> dcimJpegs() {
        java.util.Set<String> s = new java.util.HashSet<String>();
        File[] sub = new File(card, "DCIM").listFiles();
        if (sub != null) for (File d : sub) {
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) if (f.getName().toUpperCase().endsWith(".JPG")) s.add(f.getAbsolutePath());
        }
        return s;
    }

    /** Newest DCIM folder as Android lists it: name, file count, last three names (is the camera's newest file visible?). */
    private String dcimSummary() {
        try {
            File[] dirs = new File(card, "DCIM").listFiles();
            if (dirs == null || dirs.length == 0) return "no DCIM";
            java.util.Arrays.sort(dirs);
            File d = null;                                  // camera folders are 8 digits (NNNMMDD); FITEST etc. are not
            for (File x : dirs) if (x.getName().matches("\\d{8}") && (d == null || x.getName().compareTo(d.getName()) > 0)) d = x;
            if (d == null) d = dirs[dirs.length - 1];
            String[] n = d.list();
            if (n == null) return d.getName() + "/ unreadable";
            java.util.Arrays.sort(n);
            StringBuilder b = new StringBuilder(d.getName() + "/ " + n.length + " files:");
            for (int i = Math.max(0, n.length - 3); i < n.length; i++) b.append(' ').append(n[i]);
            return b.toString();
        } catch (Throwable t) { return "error " + t; }
    }

    /** Result of one shot: the JPEG file it wrote, if one appeared. */
    private static final class ShotResult { File file; }

    /**
     * One capture, run from a worker thread: takePicture on the UI thread, shutter held 2.5 s and released, the camera
     * closed and live view opened again (the only way this body saves the next shot), then the new file looked for by
     * name in every DCIM folder (the app clock is wrong). Nothing is written to the card meanwhile (see out()).
     */
    private ShotResult shoot(long timeoutMs) throws Exception {
        if (cameraEx == null) throw new IllegalStateException("live view not running");
        final java.util.Set<String> before = dcimJpegs();
        final ShotResult r = new ShotResult();
        final long t0 = System.currentTimeMillis();
        final Throwable[] fail = new Throwable[1];
        ui.post(new Runnable() { public void run() {
            try {
                if (cameraEx == null) throw new IllegalStateException("closed");
                cameraEx.getNormalCamera().takePicture(null, null, null);
                out("  takePicture +" + (System.currentTimeMillis() - t0) + " ms");
            } catch (Throwable t) { fail[0] = t; err("takePicture", t); }
        } });
        // Hold the shutter 2.5 s, release it, then close the camera and open live view again. On this body a
        // live-view session saves only its first shot (every probe and app log since probe 4); the probe builds
        // "worked" because the owner left and re-entered live view before each capture, which is what this does.
        ui.postDelayed(new Runnable() { public void run() {
            try { if (cameraEx != null) cameraEx.cancelTakePicture(); } catch (Throwable t) { err("release shutter", t); }
            out("  released +" + (System.currentTimeMillis() - t0) + " ms");
        } }, 2500);
        Thread.sleep(3000);
        restoreCamera();
        out("  camera closed +" + (System.currentTimeMillis() - t0) + " ms");
        startLive();
        out("  live view reopened +" + (System.currentTimeMillis() - t0) + " ms");
        if (fail[0] != null) throw new IllegalStateException("takePicture: " + fail[0].getMessage());
        long end = t0 + timeoutMs;
        while (System.currentTimeMillis() < end && r.file == null) {
            Thread.sleep(400);
            for (String path : dcimJpegs()) if (!before.contains(path)) { r.file = new File(path); break; }
        }
        if (r.file != null) {
            long len = -1;
            for (int i = 0; i < 20 && r.file.length() != len; i++) { len = r.file.length(); Thread.sleep(300); }
            out("  file " + r.file.getParentFile().getName() + "/" + r.file.getName() + " +"
                    + (System.currentTimeMillis() - t0) + " ms, " + len + " bytes");
        } else {
            out("  no new JPEG after " + timeoutMs + " ms; Android sees: " + dcimSummary());
        }
        return r;
    }

    /** JPEGs that are not plain negatives (positive captures, measurement shots): the engine skips them. */
    private void rememberSkip(String name) {
        try {
            File f = new File(card, "FILMINV/CAPTURED.TXT");
            f.getParentFile().mkdirs();
            FileWriter fw = new FileWriter(f, true);
            fw.write(name.toUpperCase() + "\n");
            fw.close();
        } catch (Throwable t) { err("remember capture", t); }
    }

    private java.util.Set<String> skipList() {
        java.util.Set<String> s = new java.util.HashSet<String>();
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(new File(card, "FILMINV/CAPTURED.TXT")));
            String line;
            while ((line = r.readLine()) != null) if (line.trim().length() > 0) s.add(line.trim().toUpperCase());
            r.close();
        } catch (Throwable ignored) {}
        return s;
    }

    // ================================================================== measurement shots
    private static final class Shot { int[] px; int w, h; }

    /**
     * Capture with the plain (identity) curve in the camera's own format (RAW+JPEG), the JPEG decoded at ~750 px
     * wide. Runs on a worker thread; the JPEG is listed in CAPTURED.TXT. No camera setting is changed: every
     * session that started with a JPEG-only measurement shot (0.13-0.17) saved nothing after it.
     */
    private Shot measureShot() throws Exception {
        if (cameraEx == null) throw new IllegalStateException("live view not running");
        capturing = true;
        refreshPills();
        try {
            measureCurve = true;
            applyCurve();
            Thread.sleep(400);                                    // let the curve and settings reach the sensor path
            ShotResult r = shoot(15000);
            File jpg = r.file;
            if (jpg == null) throw new IllegalStateException("no measurement JPEG appeared");
            rememberSkip(jpg.getName());
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(jpg.getAbsolutePath(), o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = Math.max(1, o.outWidth / 750);        // ~750 px wide whatever the picture size
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bm = BitmapFactory.decodeFile(jpg.getAbsolutePath(), o);
            if (bm == null) throw new IllegalStateException("could not decode " + jpg.getName());
            Shot s = new Shot();
            s.w = bm.getWidth(); s.h = bm.getHeight();
            s.px = new int[s.w * s.h];
            bm.getPixels(s.px, 0, s.w, 0, 0, s.w, s.h);
            bm.recycle();
            out("measure shot " + jpg.getName() + " " + s.w + "x" + s.h);
            return s;
        } finally {
            measureCurve = false;
            applyCurve();
            capturing = false;
            refreshPills();
        }
    }

    /** Median 8-bit code per channel in the center `frac` of the shot. */
    private static int[] centerMedian(Shot s, double frac) {
        int[][] h = new int[3][256];
        int x0 = (int) (s.w * (1 - frac) / 2), x1 = s.w - x0, y0 = (int) (s.h * (1 - frac) / 2), y1 = s.h - y0;
        int n = 0;
        for (int y = y0; y < y1; y += 2) for (int x = x0; x < x1; x += 2) {
            int c = s.px[y * s.w + x];
            h[0][(c >> 16) & 255]++; h[1][(c >> 8) & 255]++; h[2][c & 255]++; n++;
        }
        int[] m = new int[3];
        for (int ch = 0; ch < 3; ch++) {
            int acc = 0;
            for (int i = 0; i < 256; i++) { acc += h[ch][i]; if (acc * 2 >= n) { m[ch] = i; break; } }
        }
        return m;
    }

    private static double lin(int code) {
        double x = code / 255.0;
        return x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
    }

    /** ln(R/G), ln(B/G) of the film base in the center of a measurement shot. */
    private double[] baseRatios(Shot s, int[] codesOut) {
        int[] m = centerMedian(s, 0.4);
        System.arraycopy(m, 0, codesOut, 0, 3);
        double r = lin(Math.max(1, m[0])), g = lin(Math.max(1, m[1])), b = lin(Math.max(1, m[2]));
        return new double[]{Math.log(r / g), Math.log(b / g)};
    }

    private void resetWbRow() {
        if (temp == 5500 && shiftAB == 0 && shiftGM == 0) { wbActive = false; restoreWbOnly(); }
        else if (wbActive) applyWb();
    }

    private void setWbMired(double mired, int gm) {
        temp = (int) Math.max(2500, Math.min(9900, Math.round(1e6 / mired / 100.0) * 100));
        shiftGM = Math.max(-32, Math.min(32, gm));
        applyWb();
        refreshRow();
    }

    /**
     * Make the clear film base neutral with the camera's own white balance (color temperature + G–M shift),
     * measured from small captures: 3 shots to learn how the base color responds, then up to 2 correction
     * steps. Afterwards the base is gray before our curve, so the inverted orange mask no longer turns blue;
     * "Black" is set so the base itself maps to black.
     */
    private void calibrateBase() {
        showToast("Calibrating: clear film (base) in the center, don't move…");
        shiftAB = 0;
        double m0 = 1e6 / (wbActive ? temp : 5500);
        int g0 = wbActive ? shiftGM : 0;
        int[] codes = new int[3];
        try {
            setWbMired(m0, g0);
            double[] r0 = baseRatios(measureShot(), codes);
            setWbMired(m0 + 60, g0);
            double[] r1 = baseRatios(measureShot(), codes);
            setWbMired(m0, g0 + 8);
            double[] r2 = baseRatios(measureShot(), codes);
            double a = (r1[0] - r0[0]) / 60, c = (r1[1] - r0[1]) / 60;      // d(ratios)/d(mired)
            double bb = (r2[0] - r0[0]) / 8, d = (r2[1] - r0[1]) / 8;        // d(ratios)/d(G–M)
            double det = a * d - bb * c;
            out(String.format("calib r0=[%.3f %.3f] J=[[%.4f %.4f][%.4f %.4f]]", r0[0], r0[1], a, bb, c, d));
            if (Math.abs(det) < 1e-9) throw new IllegalStateException("white balance had no measurable effect");
            double mired = m0, gm = g0;
            double[] r = r0;
            for (int step = 0; step < 3; step++) {
                double dm = (-r[0] * d + r[1] * bb) / det, dg = (-a * r[1] + c * r[0]) / det;
                mired = Math.max(101, Math.min(400, mired + dm));
                gm = Math.max(-32, Math.min(32, gm + dg));
                setWbMired(mired, (int) Math.round(gm));
                r = baseRatios(measureShot(), codes);
                out(String.format("calib step %d: %dK G-M %d -> r=[%.3f %.3f] base codes %d %d %d",
                        step + 1, temp, shiftGM, r[0], r[1], codes[0], codes[1], codes[2]));
                if (Math.abs(r[0]) < 0.03 && Math.abs(r[1]) < 0.03) break;
            }
            int baseIn = Math.round(Math.max(codes[0], Math.max(codes[1], codes[2])) * 1023f / 255f);
            inHi = Math.max(inLo + 64, Math.min(1023, baseIn - 8));
            requestCurve();
            saveRoll();
            refreshRow();
            boolean good = Math.abs(r[0]) < 0.05 && Math.abs(r[1]) < 0.05;
            showToast((good ? "Base neutral: " : "Base close (check): ") + temp + " K, " + shiftLabel()
                    + String.format(" (off %.0f%%/%.0f%%)", 100 * Math.abs(r[0]), 100 * Math.abs(r[1])));
        } catch (Throwable t) {
            err("calibrate", t);
            showToast("Calibration failed: " + t.getMessage());
        }
    }

    /**
     * Black/white points from the current frame's picture area (film border excluded via analyzeFrame):
     * thinnest 0.5% of the negative -> black, densest 0.5% -> white. Plain-curve shot, so the codes are in
     * the curve's input domain (x 1023/255).
     */
    private void autoLevels() {
        showToast("Measuring levels…");
        try {
            Shot s = measureShot();
            NegEngine.Frame fr = NegEngine.analyzeFrame(s.px, s.w, s.h);
            int[] hist = new int[256];
            int n = 0;
            for (int y = fr.y0; y < fr.y1; y += 2) for (int x = fr.x0; x < fr.x1; x += 2) {
                int c = s.px[y * s.w + x];
                int l = (((c >> 16) & 255) * 2126 + ((c >> 8) & 255) * 7152 + (c & 255) * 722) / 10000;
                hist[l]++; n++;
            }
            int lo = 0, hi = 255, acc = 0;
            for (int i = 0; i < 256; i++) { acc += hist[i]; if (acc >= n * 0.005) { lo = i; break; } }
            acc = 0;
            for (int i = 255; i >= 0; i--) { acc += hist[i]; if (acc >= n * 0.005) { hi = i; break; } }
            inLo = Math.max(0, Math.round(lo * 1023f / 255f) - 4);
            inHi = Math.min(1023, Math.max(inLo + 64, Math.round(hi * 1023f / 255f) + 4));
            requestCurve();
            saveRoll();
            refreshRow();
            out("auto levels: frame " + fr + " -> in " + inLo + ".." + inHi);
            showToast("Levels set: " + inLo + "–" + inHi + (fr.borderFound ? " (border excluded)" : ""));
        } catch (Throwable t) {
            err("auto levels", t);
            showToast("Auto levels failed: " + t.getMessage());
        }
    }

    /** Film-edge tilt from a measurement shot (NegEngine.analyzeFrame), as a plain instruction. */
    private void checkAlignment() {
        showToast("Checking alignment…");
        try {
            Shot s = measureShot();
            NegEngine.Frame fr = NegEngine.analyzeFrame(s.px, s.w, s.h);
            out("alignment: " + fr);
            if (Double.isNaN(fr.tiltDeg)) {
                showToast("No film edge found: include some rebate (clear border) in the frame");
            } else if (Math.abs(fr.tiltDeg) < 0.15) {
                showToast(String.format("Straight (%.2f°)", fr.tiltDeg));
            } else {
                showToast(String.format("Film tilted %.2f° %s: rotate it %.2f° %s", Math.abs(fr.tiltDeg),
                        fr.tiltDeg > 0 ? "clockwise" : "counter-clockwise", Math.abs(fr.tiltDeg),
                        fr.tiltDeg > 0 ? "counter-clockwise" : "clockwise"));
            }
        } catch (Throwable t) {
            err("alignment", t);
            showToast("Alignment check failed: " + t.getMessage());
        }
    }

    // ================================================================== develop (in-camera positive, Mode A seed)
    /** Newest RAW+JPEG pair that is a plain negative -> /FILMINV/<same 8.3 name>.JPG, auto-cropped, 1/4 size. */
    private void developNewest() {
        File best = null;
        java.util.Set<String> skip = skipList();
        File[] sub = new File(card, "DCIM").listFiles();
        if (sub != null) for (File d : sub) {
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName();
                if (!n.toUpperCase().endsWith(".JPG") || skip.contains(n.toUpperCase())) continue;
                File arw = new File(d, n.substring(0, n.length() - 4) + ".ARW");
                if (arw.exists() && (best == null || f.lastModified() > best.lastModified())) best = f;
            }
        }
        if (best == null) { showToast("No RAW+JPEG negative found"); return; }
        showToast("Developing " + best.getName() + "…");
        try {
            long t0 = System.currentTimeMillis();
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = 4;
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bm = BitmapFactory.decodeFile(best.getAbsolutePath(), d);
            int w = bm.getWidth(), h = bm.getHeight();
            int[] px = new int[w * h];
            bm.getPixels(px, 0, w, 0, 0, w, h);
            bm.recycle();
            NegEngine.Frame fr = NegEngine.analyzeFrame(px, w, h);
            NegEngine.Profile p = new NegEngine.Profile();
            p.base = fr.base != null ? fr.base : fallbackBase(px);
            NegEngine.Histogram hist = new NegEngine.Histogram(p);
            hist.addArea(px, w, fr, 2);
            NegEngine.levelsFromHistogram(p, hist);
            NegEngine.apply(px, 0, px.length, NegEngine.buildLuts(p), p.film);
            Bitmap full = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            px = null;
            Bitmap out = Bitmap.createBitmap(full, fr.x0, fr.y0, fr.x1 - fr.x0, fr.y1 - fr.y0);   // auto-crop
            File dir = new File(card, "FILMINV");
            dir.mkdirs();
            File dst = new File(dir, best.getName().toUpperCase());
            FileOutputStream fo = new FileOutputStream(dst);
            try { out.compress(Bitmap.CompressFormat.JPEG, 92, fo); } finally { fo.close(); }
            if (out != full) out.recycle();
            full.recycle();
            out("developed " + best.getName() + " frame " + fr + " in " + (System.currentTimeMillis() - t0) + " ms");
            showToast("Saved FILMINV/" + dst.getName() + (Double.isNaN(fr.tiltDeg) ? "" : String.format(" (edge %.2f°)", fr.tiltDeg)));
        } catch (Throwable t) {
            err("develop", t);
            showToast("Develop failed: " + t.getMessage());
        }
    }

    private static double[] fallbackBase(int[] px) {
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
            base[ch] = lin(code);
        }
        return base;
    }

    // ================================================================== logging
    private final long logT0 = System.currentTimeMillis();

    /**
     * Log lines are kept in memory and appended to FIPROBE.TXT about once a second, each time opened, written and
     * closed, never while a shot is being taken. 0.12-0.18 kept the file open for the whole session: the camera
     * then saved the first shot of a session and nothing after it, Android never saw that file, and a battery pull
     * lost everything Android had written (Recipe Lab saw the same "no more shots until the app restarts" when it
     * touched the card with the camera open, and opens/closes its log per line).
     */
    private void out(final String s) {
        long ms = System.currentTimeMillis() - logT0;               // seconds since start: the app clock itself is wrong
        synchronized (logBuf) { logBuf.append(String.format(java.util.Locale.US, "[%6.1f] ", ms / 1000.0)).append(s).append('\n'); }
        ui.removeCallbacks(logFlusher);
        ui.postDelayed(logFlusher, 1000);
        ui.post(new Runnable() { public void run() {
            if (logText == null) return;
            logText.append(s + "\n");
            if (logText.length() > 20000) logText.setText(logText.getText().subSequence(10000, logText.length()));
        } });
    }

    private final Runnable logFlusher = new Runnable() { public void run() {
        if (capturing) { ui.postDelayed(this, 1000); return; }        // never touch the card during a shot
        new Thread(new Runnable() { public void run() { flushLog(false); } }).start();
    } };

    private void flushLog(boolean force) {
        String chunk;
        synchronized (logBuf) {
            if (logBuf.length() == 0 || (capturing && !force)) return;
            chunk = logBuf.toString();
            logBuf.setLength(0);
        }
        synchronized (logFileLock) {
            FileWriter w = null;
            try {
                w = new FileWriter(new File(card, "FIPROBE.TXT"), true);
                w.write(chunk);
            } catch (Throwable ignored) {
            } finally {
                if (w != null) try { w.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private final Object logFileLock = new Object();

    private void err(String what, Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        out("!! " + what + ": " + t + "\n" + sw.toString().trim());
    }
    @Override
    protected void logWarning(String what, Throwable t) { err(what, t); }

    private String now() {
        try {
            Calendar c = DateTime.getInstance().getCurrentTime();
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            f.setTimeZone(c.getTimeZone());
            return f.format(c.getTime());
        } catch (Throwable t) {
            return "?";
        }
    }

    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread th, Throwable t) {
                try {
                    err("CRASH in thread " + th.getName(), t);
                    flushLog(true);
                } catch (Throwable ignored) {}
                restoreCamera();
                unflipForExit();
                if (prev != null) prev.uncaughtException(th, t);
            }
        });
    }
}

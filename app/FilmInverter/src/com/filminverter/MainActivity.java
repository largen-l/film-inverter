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
    private PrintWriter log;
    private File card;

    // ------------------------------------------------------------------ views
    private SurfaceView surface;
    private OverlayViews.Guides guides;
    private TextView modePill, framePill, basePill, wbPill, toast, menuBtn, valueLabel, logText;
    private OverlayViews.CaptureButton captureBtn;
    private LinearLayout bar, chipRow, menuPanel;
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
        try { log = new PrintWriter(new FileWriter(new File(card, "FIPROBE.TXT"), true)); } catch (Exception e) { log = null; }
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
        applyTheme();
        applyWindowFlip();
        out("=== Film Inverter 0.14, " + now() + " === theme " + themes.get(themeIdx).name + ", " + themes.size() + " themes");
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
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        restoreCamera();
        out("=== exit ===");
        if (log != null) log.close();
        super.onDestroy();
    }

    public void surfaceCreated(SurfaceHolder h) { surfaceReady = true; if (!live) startLiveAsync(); }
    public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
    public void surfaceDestroyed(SurfaceHolder h) { surfaceReady = false; }

    // ================================================================== UI
    private int sp(float v) { return Math.round(v * themes.get(themeIdx).textScale); }

    private TextView pill(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setPadding(10, 4, 10, 4);
        return t;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        surface.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        guides = new OverlayViews.Guides(this);
        guides.setVisibility(guidesOn ? View.VISIBLE : View.GONE);
        root.addView(guides, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(12, 12, 12, 0);
        modePill = pill("POS"); framePill = pill("Live"); basePill = pill("Base: not set"); wbPill = pill("Camera WB");
        top.addView(modePill);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.leftMargin = 8;
        top.addView(framePill, gap);
        expPill = pill("");
        expPill.setVisibility(View.GONE);
        LinearLayout.LayoutParams gap3 = new LinearLayout.LayoutParams(-2, -2); gap3.leftMargin = 8;
        top.addView(expPill, gap3);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(basePill);
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(-2, -2); gap2.leftMargin = 8;
        top.addView(wbPill, gap2);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        toast = pill("");
        toast.setVisibility(View.GONE);
        toast.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tl.topMargin = 52; tl.leftMargin = 24; tl.rightMargin = 24;
        root.addView(toast, tl);

        // bottom bar: menu | chips + slider | capture
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(16, 0, 16, 0);
        menuBtn = new TextView(this);
        menuBtn.setText("Menu");                    // plain text: the camera's Android 4.1 font may lack ☰
        menuBtn.setTextSize(14);
        menuBtn.setGravity(Gravity.CENTER);
        menuBtn.setContentDescription("Menu");
        menuBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { toggleMenu(); } });
        bar.addView(menuBtn, new LinearLayout.LayoutParams(64, 52));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(14, 0, 14, 0);
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < ROW_NAMES.length; i++) {
            final int r = i;
            TextView c = new TextView(this);
            c.setText(ROW_NAMES[i]);
            c.setPadding(11, 6, 11, 6);
            c.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { selectRow(r); } });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.rightMargin = 6;
            chipRow.addView(c, cl);
            chips.add(c);
        }
        pageChip = new TextView(this);                      // switches color <-> exposure chips (Lens controls on)
        pageChip.setPadding(11, 6, 11, 6);
        pageChip.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            selectRow(row >= ROW_EXP ? ROW_BLACK : ROW_EXP); } });
        chipRow.addView(pageChip, new LinearLayout.LayoutParams(-2, -2));
        mid.addView(chipRow);
        LinearLayout sliderRow = new LinearLayout(this);
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        slider = new Slider(this);
        slider.setListener(new Slider.Listener() {
            public void onValue(int v, boolean fromUser) { setRowFromSlider(v); }
            public void onReset() { resetRow(); }
        });
        sliderRow.addView(slider, new LinearLayout.LayoutParams(0, 44, 1f));
        valueLabel = new TextView(this);
        valueLabel.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        valueLabel.setTypeface(Typeface.MONOSPACE);
        sliderRow.addView(valueLabel, new LinearLayout.LayoutParams(84, -2));
        mid.addView(sliderRow);
        bar.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));

        captureBtn = new OverlayViews.CaptureButton(this);
        captureBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onShutterAction(); } });
        bar.addView(captureBtn, new LinearLayout.LayoutParams(76, 76));
        root.addView(bar, new FrameLayout.LayoutParams(-1, 112, Gravity.BOTTOM));

        // menu panel (right side, above the bar)
        menuPanel = new LinearLayout(this);
        menuPanel.setOrientation(LinearLayout.VERTICAL);
        menuPanel.setPadding(8, 8, 8, 8);
        // in a ScrollView: the menu is taller than the space above the bar; styleMenu keeps the cursor in view
        menuScroll = new ScrollView(this);
        menuScroll.setVerticalScrollBarEnabled(true);
        menuScroll.addView(menuPanel, new FrameLayout.LayoutParams(-1, -2));
        menuScroll.setVisibility(View.GONE);
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(300, -2, Gravity.BOTTOM | Gravity.LEFT);
        ml.bottomMargin = 116; ml.leftMargin = 12; ml.topMargin = 8;
        root.addView(menuScroll, ml);

        logScroll = new ScrollView(this);
        logText = new TextView(this);
        logText.setTextSize(11);
        logText.setTextColor(Color.WHITE);
        logText.setBackgroundColor(0xd8000000);
        logScroll.addView(logText);
        logScroll.setVisibility(View.GONE);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-1, -1);
        ll.bottomMargin = 112;
        root.addView(logScroll, ll);

        setContentView(root);
        refreshRow();
        rebuildMenu();
    }

    private GradientDrawable shape(int fill, int stroke, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (stroke != 0) d.setStroke(1, stroke);
        d.setCornerRadius(radius);
        return d;
    }

    private void applyTheme() {
        Theme t = themes.get(themeIdx);
        bar.setBackgroundColor(t.bar);
        for (TextView p : new TextView[]{modePill, framePill, expPill, basePill, wbPill, toast}) {
            p.setTextColor(t.pillText);
            p.setTextSize(sp(13));
            p.setBackgroundDrawable(shape(t.pill, 0, 14));
        }
        menuBtn.setTextColor(t.text);
        menuBtn.setBackgroundDrawable(shape(0, t.chipBorder, t.radius));
        valueLabel.setTextColor(t.text);
        valueLabel.setTextSize(sp(15));
        slider.setColors(t.chipBorder, t.accent, t.text);
        captureBtn.setColor(t.text);
        guides.setColor(t.accent);
        menuPanel.setBackgroundDrawable(shape(t.bar, t.chipBorder, t.radius));
        styleChips();
        styleMenu();
    }

    private void styleChips() {
        Theme t = themes.get(themeIdx);
        boolean expPage = row >= ROW_EXP;
        for (int i = 0; i < chips.size(); i++) {
            TextView c = chips.get(i);
            boolean sel = i == row;
            c.setVisibility((i >= ROW_EXP) == expPage ? View.VISIBLE : View.GONE);
            c.setTextSize(sp(13));
            c.setTextColor(sel ? t.onAccent : t.textDim);
            c.setBackgroundDrawable(sel ? shape(t.accent, 0, Math.min(8, t.radius)) : shape(0, t.chipBorder, Math.min(8, t.radius)));
        }
        pageChip.setVisibility(lensControls ? View.VISIBLE : View.GONE);
        pageChip.setText(expPage ? "‹ Color" : "Exposure ›");
        pageChip.setTextSize(sp(13));
        pageChip.setTextColor(t.text);
        pageChip.setBackgroundDrawable(shape(0, t.chipBorder, Math.min(8, t.radius)));
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

    private String rowValue() {
        if (row >= ROW_EXP) return exposure.label(row - ROW_EXP);
        switch (row) {
            case ROW_BLACK: return String.valueOf(inHi);
            case ROW_WHITE: return String.valueOf(inLo);
            case ROW_GAMMA: return String.format("%.2f", gammaX100 / 100.0);
            case ROW_TEMP: return wbActive ? temp + "K" : "cam";
            case ROW_AB: return (shiftAB > 0 ? "B" : shiftAB < 0 ? "A" : "") + Math.abs(shiftAB);
            default: return (shiftGM > 0 ? "M" : shiftGM < 0 ? "G" : "") + Math.abs(shiftGM);
        }
    }

    private int[] sliderRange() {   // {max, value}
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
                styleChips();
            }
        });
        refreshPills();
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
        ui.post(new Runnable() { public void run() { valueLabel.setText(rowValue()); } });
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
            default: wbActive = false; temp = 5500; shiftAB = 0; shiftGM = 0; restoreWbOnly(); break;
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
            refreshRow(); } });
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
        int scan = e.getScanCode();
        if (isMenuOrBack(scan) || keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BACK) { toggleMenu(); return true; }
        if (isEnter(scan) || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (menuOpen) activateMenu();
            else if (!busy) { positive = !positive; requestCurve(); refreshPills(); }
            return true;
        }
        if (isShutter(scan)) { onShutterAction(); return true; }
        if (isHalfPress(scan)) {                            // autofocus only with Lens controls on (manual lens: nothing to do)
            if (lensControls && exposure.electronicLens() && cameraEx != null && !busy && !capturing && e.getRepeatCount() == 0) {
                try { cameraEx.getNormalCamera().autoFocus(null); } catch (Throwable t) { err("autofocus", t); }
            }
            return true;
        }
        if (isUp(scan) || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (menuOpen) { menuSel = (menuSel + menuItems.size() - 1) % menuItems.size(); styleMenu(); } else selectRow(row - 1);
            return true;
        }
        if (isDown(scan) || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (menuOpen) { menuSel = (menuSel + 1) % menuItems.size(); styleMenu(); } else selectRow(row + 1);
            return true;
        }
        if (isNext(scan) || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { if (!menuOpen) stepRow(+1); return true; }
        if (isPrev(scan) || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { if (!menuOpen) stepRow(-1); return true; }
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
        } catch (Throwable ignored) {}
    }

    private void savePrefs() {
        try {
            getSharedPreferences("fi", MODE_PRIVATE).edit().putInt("flip2", flipMode).putBoolean("guides", guidesOn)
                    .putInt("delay", delaySec).putBoolean("silent", silent).putInt("theme", themeIdx)
                    .putBoolean("lens", lensControls).commit();
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
            out("live view started, " + now());
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
                ShotResult r = shoot(false, 30000);
                out("JPEG from capture: " + (r.file == null ? "none (RAW only?)" : r.file.getName()));
                if (pos && r.file != null) rememberSkip(r.file.getName());
                showToast(r.file == null ? (r.shutter ? "Captured (no JPEG found)" : "Capture failed: the shutter did not fire")
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

    /** Result of one shot: the JPEG file it wrote (if any is seen) and the JPEG bytes (if asked for). */
    private static final class ShotResult { File file; byte[] data; boolean shutter, callback; }

    /**
     * One capture, run from a worker thread. Camera calls go to the UI thread (the thread that opened the camera),
     * the shutter is "released" 300 ms later (Sony waits for that, see probe5-8.md), then this waits for the JPEG
     * callback and for a new file to appear under DCIM and stop growing. Matching by file name, not time: the app
     * clock is wrong. Preview restarts only once the camera is done, so the next shot is not started mid-save.
     */
    private ShotResult shoot(final boolean wantData, long timeoutMs) throws Exception {
        if (cameraEx == null) throw new IllegalStateException("live view not running");
        final java.util.Set<String> before = dcimJpegs();
        final ShotResult r = new ShotResult();
        final long t0 = System.currentTimeMillis();
        final Throwable[] fail = new Throwable[1];
        ui.post(new Runnable() { public void run() {
            try {
                if (cameraEx == null) throw new IllegalStateException("closed");
                Camera.ShutterCallback sc = new Camera.ShutterCallback() { public void onShutter() {
                    r.shutter = true; out("  shutter +" + (System.currentTimeMillis() - t0) + " ms"); } };
                Camera.PictureCallback jc = !wantData ? null : new Camera.PictureCallback() {
                    public void onPictureTaken(byte[] data, Camera c) {
                        out("  JPEG callback +" + (System.currentTimeMillis() - t0) + " ms, "
                                + (data == null ? "no data" : data.length + " bytes"));
                        synchronized (r) { r.data = data; r.callback = true; }
                    } };
                cameraEx.getNormalCamera().takePicture(sc, null, jc);
            } catch (Throwable t) { fail[0] = t; }
        } });
        ui.postDelayed(new Runnable() { public void run() {
            try { if (cameraEx != null) cameraEx.cancelTakePicture(); } catch (Throwable t) { err("release shutter", t); } } }, 300);
        try {
            long end = t0 + timeoutMs;
            while (System.currentTimeMillis() < end && r.file == null) {
                Thread.sleep(400);
                if (fail[0] != null) throw new IllegalStateException("takePicture: " + fail[0].getMessage());
                for (String path : dcimJpegs()) if (!before.contains(path)) { r.file = new File(path); break; }
            }
            if (r.file != null) {
                long len = -1;
                for (int i = 0; i < 20 && r.file.length() != len; i++) { len = r.file.length(); Thread.sleep(300); }
                out("  file " + r.file.getParentFile().getName() + "/" + r.file.getName() + " +"
                        + (System.currentTimeMillis() - t0) + " ms, " + len + " bytes");
            } else {
                File[] dirs = new File(card, "DCIM").listFiles();
                out("  no new JPEG after " + timeoutMs + " ms (shutter " + r.shutter + ", callback " + r.callback
                        + ", DCIM folders " + (dirs == null ? "?" : String.valueOf(dirs.length)) + ")");
            }
            if (wantData) { long e2 = System.currentTimeMillis() + 3000; while (!r.callback && System.currentTimeMillis() < e2) Thread.sleep(100); }
            Thread.sleep(300);
        } finally {
            final Object done = new Object();
            final boolean[] ok = new boolean[1];
            ui.post(new Runnable() { public void run() {
                try { if (cameraEx != null) cameraEx.getNormalCamera().startPreview(); } catch (Throwable t) { err("restart preview", t); }
                synchronized (done) { ok[0] = true; done.notifyAll(); }
            } });
            synchronized (done) { if (!ok[0]) done.wait(3000); }
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
     * Small JPEG-only capture with the plain (identity) curve, decoded at 1/4 (~750x500). Runs on a worker
     * thread. Camera settings for the shot are set and put back; the file is listed in CAPTURED.TXT.
     */
    private Shot measureShot() throws Exception {
        if (cameraEx == null) throw new IllegalStateException("live view not running");
        Camera cam = cameraEx.getNormalCamera();
        Camera.Parameters p0 = cam.getParameters();
        String fmt = p0.get("storage-fmt"), size = p0.get("picture-size"), q = p0.get("jpeg-quality");
        capturing = true;
        refreshPills();
        try {
            setParams("storage-fmt", "jpeg", "picture-size", "3008x2000", "jpeg-quality", "25");
            measureCurve = true;
            applyCurve();
            Thread.sleep(400);                                    // let the curve and settings reach the sensor path
            ShotResult r = shoot(true, 30000);
            File jpg = r.file;
            if (jpg == null && r.data == null)
                throw new IllegalStateException(r.shutter ? "no measurement JPEG appeared" : "the shutter did not fire");
            if (jpg != null) rememberSkip(jpg.getName());
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            byte[] data = r.data;
            r.data = null;
            Bitmap bm = data != null ? BitmapFactory.decodeByteArray(data, 0, data.length, o)
                    : BitmapFactory.decodeFile(jpg.getAbsolutePath(), o);
            data = null;
            if (bm == null) throw new IllegalStateException("could not decode the measurement JPEG");
            if (jpg == null) jpg = new File("(callback data)");
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
            List<String> kv = new ArrayList<String>();
            if (fmt != null) { kv.add("storage-fmt"); kv.add(fmt); }
            if (size != null) { kv.add("picture-size"); kv.add(size); }
            if (q != null) { kv.add("jpeg-quality"); kv.add(q); }
            setParams(kv.toArray(new String[kv.size()]));
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
    private void out(final String s) {
        if (log != null) { log.println(s); log.flush(); }
        ui.post(new Runnable() { public void run() {
            if (logText == null) return;
            logText.append(s + "\n");
            if (logText.length() > 20000) logText.setText(logText.getText().subSequence(10000, logText.length()));
        } });
    }

    private void err(String what, Throwable t) {
        out("!! " + what + ": " + t);
        if (log != null) { t.printStackTrace(log); log.flush(); }
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
                    PrintWriter w = log != null ? log : new PrintWriter(new FileWriter(new File(card, "FIPROBE.TXT"), true));
                    w.println("!! CRASH in thread " + th.getName() + ": " + t);
                    t.printStackTrace(w);
                    w.flush();
                } catch (Throwable ignored) {}
                restoreCamera();
                unflipForExit();
                if (prev != null) prev.uncaughtException(th, t);
            }
        });
    }
}

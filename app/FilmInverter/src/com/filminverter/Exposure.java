package com.filminverter;

import android.hardware.Camera;
import android.os.Handler;
import android.util.Pair;

import com.sony.scalar.hardware.CameraEx;

import java.util.ArrayList;
import java.util.List;

/**
 * Exposure and lens controls for electronic lenses (Menu › Lens controls), the way the camera's own
 * screens do it: aperture and shutter speed are stepped with CameraEx.increment/decrementAperture and
 * ...ShutterSpeed (the same thing the dials do), ISO goes through ParametersModifier.setISOSensitivity,
 * exposure compensation through the standard Camera.Parameters, autofocus on a shutter half-press.
 *
 * Off by default: with a fully manual (adapted) lens the camera has no electronic aperture, so none of
 * this applies. Like the camera's dials, f-number, shutter speed, ISO and exposure compensation stay as set
 * after the app exits (they are the camera's own exposure settings, not something the app borrows).
 *
 * NOT YET TESTED ON THE CAMERA. Unknowns, all logged so the first run answers them:
 *  - the unit of the aperture ints (assumed f-number x 100, e.g. 560 = f/5.6),
 *  - which way increment* moves (learned at run time from the change listeners),
 *  - the element type of getSupportedISOSensitivities() (parsed from toString; 0 = Auto assumed).
 * Aperture only moves in A/M and shutter speed only in S/M (the mode dial is physical; apps can't turn it):
 * if a step does nothing the user is told which mode to use.
 */
final class Exposure {

    interface Host {
        void log(String s);
        void toast(String s);
        void changed();                   // values or ranges changed: refresh the row/pill
    }

    static final int F = 0, SHUTTER = 1, ISO = 2, EV = 3;
    static final String[] NAMES = {"f/", "Shutter", "ISO", "EV"};

    private final Host host;
    private final Handler ui;
    private CameraEx cam;

    // last known values (-1: unknown)
    private int aperture = -1, apMin = -1, apMax = -1;             // f-number x 100 (assumed)
    private int ssN = -1, ssD = -1, ssMinN = -1, ssMinD = -1, ssMaxN = -1, ssMaxD = -1;
    private int iso = -1, autoIso = -1;
    private final List<Integer> isoList = new ArrayList<Integer>();
    private int evIdx, evMin, evMax;
    private float evStep;
    private String lensName = "";
    private boolean electronicLens;

    // stepping toward a slider target, one step at a time, with the direction learned from the listener
    private int apSign = +1, ssSign = -1;                           // guesses: increment = larger f-number / faster
    private int target = -1, targetKind = -1, tries, stuck;
    private int lastBefore;

    Exposure(Host host, Handler ui) { this.host = host; this.ui = ui; }

    // ------------------------------------------------------------------ lifecycle
    /**
     * Called with live view running. Always reads the lens info (logged; used for the "electronic lens found"
     * hint). With `full` it also reads the current exposure values and installs the change listeners.
     */
    void attach(CameraEx c, boolean full) {
        electronicLens = false;
        lensName = "";
        try {
            CameraEx.LensInfo li = c.getLensInfo();
            if (li != null) {
                lensName = li.LensName == null ? "" : li.LensName.trim();
                electronicLens = lensName.length() > 0 && !"other".equals(li.LensType);
                host.log("lens: name='" + li.LensName + "' type=" + li.LensType + " id=" + li.LensId + " ver=" + li.LensVersion
                        + " fixedAperture=" + li.FixedAperture
                        + (li.MinFValue != null ? " minF=" + li.MinFValue.wide + "/" + li.MinFValue.tele : "")
                        + (li.MaxFValue != null ? " maxF=" + li.MaxFValue.wide + "/" + li.MaxFValue.tele : "")
                        + (li.FocalLength != null ? " focal=" + li.FocalLength.wide + "/" + li.FocalLength.tele : ""));
                if (li.MinFValue != null && li.MaxFValue != null && li.MinFValue.wide > 0) {
                    apMin = li.MinFValue.wide; apMax = li.MaxFValue.wide;
                }
            } else host.log("lens: no lens info (adapted/manual lens?)");
        } catch (Throwable t) { host.log("lens info failed: " + t); }
        if (!full) return;
        cam = c;
        aperture = -1; ssN = -1; ssD = -1; iso = -1; autoIso = -1;
        try {
            c.setApertureChangeListener(new CameraEx.ApertureChangeListener() {
                public void onApertureChange(CameraEx.ApertureInfo a, CameraEx x) {
                    int before = aperture;
                    aperture = a.currentAperture;
                    if (a.currentAvailableMin > 0) apMin = a.currentAvailableMin;
                    if (a.currentAvailableMax > 0) apMax = a.currentAvailableMax;
                    host.log("aperture " + aperture + " (" + apMin + ".." + apMax + ")");
                    onMoved(F, before, aperture);
                    host.changed();
                }
            });
            c.setShutterSpeedChangeListener(new CameraEx.ShutterSpeedChangeListener() {
                public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo s, CameraEx x) {
                    int before = ssPos();
                    ssN = s.currentShutterSpeed_n; ssD = s.currentShutterSpeed_d;
                    if (s.currentAvailableMin_d > 0) { ssMinN = s.currentAvailableMin_n; ssMinD = s.currentAvailableMin_d; }
                    if (s.currentAvailableMax_d > 0) { ssMaxN = s.currentAvailableMax_n; ssMaxD = s.currentAvailableMax_d; }
                    host.log("shutter " + ssN + "/" + ssD + " (" + ssMinN + "/" + ssMinD + ".." + ssMaxN + "/" + ssMaxD + ")");
                    onMoved(SHUTTER, before, ssPos());
                    host.changed();
                }
            });
            c.setAutoISOSensitivityListener(new CameraEx.AutoISOSensitivityListener() {
                public void onChanged(int v, CameraEx x) { autoIso = v; host.changed(); }
            });
        } catch (Throwable t) { host.log("exposure listeners failed: " + t); }
        readParams();
    }

    /** Before the camera is released: drop the listeners and any stepping in progress. */
    void detach() {
        ui.removeCallbacks(stepper);
        target = -1;
        if (cam == null) return;
        try {
            cam.setApertureChangeListener(null);
            cam.setShutterSpeedChangeListener(null);
            cam.setAutoISOSensitivityListener(null);
        } catch (Throwable t) { host.log("exposure detach: " + t); }
        cam = null;
    }

    private void readParams() {
        if (cam == null) return;
        try {
            Camera.Parameters p = cam.getNormalCamera().getParameters();
            CameraEx.ParametersModifier m = cam.createParametersModifier(p);
            try { if (aperture < 0) aperture = m.getAperture(); } catch (Throwable t) { host.log("getAperture: " + t); }
            try {
                Pair<?, ?> s = m.getShutterSpeed();
                if (s != null && ssD < 0) { ssN = ((Number) s.first).intValue(); ssD = ((Number) s.second).intValue(); }
            } catch (Throwable t) { host.log("getShutterSpeed: " + t); }
            try { iso = m.getISOSensitivity(); } catch (Throwable t) { host.log("getISOSensitivity: " + t); }
            isoList.clear();
            try {
                List<?> l = m.getSupportedISOSensitivities();
                if (l != null) for (Object o : l) {
                    try { isoList.add(o instanceof Number ? ((Number) o).intValue() : Integer.parseInt(o.toString().trim())); }
                    catch (NumberFormatException ignored) {}
                }
                host.log("ISO list " + l);
            } catch (Throwable t) { host.log("ISO list: " + t); }
            evMin = p.getMinExposureCompensation(); evMax = p.getMaxExposureCompensation();
            evStep = p.getExposureCompensationStep(); evIdx = p.getExposureCompensation();
            host.log("exposure: aperture=" + aperture + " shutter=" + ssN + "/" + ssD + " iso=" + iso
                    + " ev=" + evIdx + " (" + evMin + ".." + evMax + " x" + evStep + ")");
        } catch (Throwable t) { host.log("read exposure: " + t); }
    }

    // ------------------------------------------------------------------ queries
    boolean electronicLens() { return electronicLens; }
    String lensName() { return lensName; }

    String label(int kind) {
        switch (kind) {
            case F: return aperture > 0 ? "f/" + fmt(aperture / 100.0) : "f/–";
            case SHUTTER: return shutterLabel(ssN, ssD);
            case ISO: return iso == 0 ? (autoIso > 0 ? "A" + autoIso : "Auto") : iso > 0 ? String.valueOf(iso) : "–";
            default: return evStep == 0 ? "–" : (evIdx > 0 ? "+" : "") + fmt(evIdx * evStep);
        }
    }

    /** One line for the status pill. */
    String summary() { return label(F) + " · " + label(SHUTTER) + " · ISO " + label(ISO) + (evIdx != 0 ? " · " + label(EV) : ""); }

    /** {max, value} for the slider, in thirds of a stop for f/ and shutter. */
    int[] range(int kind) {
        switch (kind) {
            case F: {
                int lo = apMin > 0 ? apMin : 140, hi = apMax > lo ? apMax : 2200;
                int max = Math.max(1, thirdsF(hi, lo));
                return new int[]{max, aperture > 0 ? clamp(thirdsF(aperture, lo), 0, max) : max / 2};
            }
            case SHUTTER: {
                int max = Math.max(1, ssThirds(ssMaxN, ssMaxD, 30, 1));
                return new int[]{max, ssD > 0 ? clamp(ssPos(), 0, max) : max / 2};
            }
            case ISO: return new int[]{Math.max(1, isoList.size() - 1), Math.max(0, isoList.indexOf(iso))};
            default: return new int[]{Math.max(1, evMax - evMin), evIdx - evMin};
        }
    }

    // ------------------------------------------------------------------ changes
    /** Slider moved to `pos` (from range()). */
    void setFromSlider(int kind, int pos) {
        if (cam == null) return;
        switch (kind) {
            case F: case SHUTTER:
                target = pos; targetKind = kind; tries = 0; stuck = 0;
                ui.removeCallbacks(stepper);
                ui.post(stepper);
                break;
            case ISO:
                if (pos >= 0 && pos < isoList.size()) setIso(isoList.get(pos));
                break;
            default:
                setEv(evMin + pos);
        }
    }

    /** One click of a key or dial. */
    void step(int kind, int dir) {
        if (cam == null) return;
        int[] r = range(kind);
        setFromSlider(kind, clamp(r[1] + dir, 0, r[0]));
    }

    /** Double-tap: ISO Auto, EV 0; f/ and shutter have no "auto" here (that is the mode dial). */
    void reset(int kind) {
        if (kind == ISO) { if (isoList.contains(0)) setIso(0); }
        else if (kind == EV) setEv(0);
        else host.toast(kind == F ? "Aperture: use A mode for automatic" : "Shutter: use A or P mode for automatic");
    }

    private void setIso(int v) {
        try {
            Camera c = cam.getNormalCamera();
            Camera.Parameters p = c.getParameters();
            cam.createParametersModifier(p).setISOSensitivity(v);
            c.setParameters(p);
            iso = cam.createParametersModifier(c.getParameters()).getISOSensitivity();
            host.log("set ISO " + v + (iso != v ? " (READBACK " + iso + ")" : ""));
            if (iso != v) host.toast("ISO not changed (mode dial in Auto/Scene?)");
        } catch (Throwable t) { host.log("set ISO " + v + ": " + t); host.toast("ISO not supported here"); }
        host.changed();
    }

    private void setEv(int idx) {
        try {
            Camera c = cam.getNormalCamera();
            Camera.Parameters p = c.getParameters();
            p.setExposureCompensation(clamp(idx, evMin, evMax));
            c.setParameters(p);
            evIdx = c.getParameters().getExposureCompensation();
            host.log("set EV index " + idx + (evIdx != idx ? " (READBACK " + evIdx + ")" : ""));
        } catch (Throwable t) { host.log("set EV " + idx + ": " + t); }
        host.changed();
    }

    /** Steps aperture/shutter one click at a time toward `target`, waiting for the change listener in between. */
    private final Runnable stepper = new Runnable() {
        public void run() {
            if (cam == null || target < 0) return;
            int cur = range(targetKind)[1];
            if (cur == target || tries >= 40) { target = -1; return; }
            if (stuck >= 2) {
                host.toast(targetKind == F ? "Aperture won't move: set the mode dial to A or M (needs an electronic lens)"
                        : "Shutter won't move: set the mode dial to S or M");
                target = -1;
                return;
            }
            int want = target > cur ? +1 : -1;                     // +1: larger f-number / longer exposure
            boolean inc = want * (targetKind == F ? apSign : ssSign) > 0;
            lastBefore = cur;
            tries++;
            stuck++;                                                // cleared by onMoved when the listener reports
            try {
                if (targetKind == F) { if (inc) cam.incrementAperture(); else cam.decrementAperture(); }
                else { if (inc) cam.incrementShutterSpeed(); else cam.decrementShutterSpeed(); }
            } catch (Throwable t) { host.log("step: " + t); target = -1; return; }
            ui.postDelayed(this, 180);
        }
    };

    /** Listener feedback: learn which way increment goes, and that the control is alive. */
    private void onMoved(int kind, int before, int after) {
        if (kind != targetKind || target < 0 || before == after || before < 0) return;
        stuck = 0;
        int wanted = target > lastBefore ? +1 : -1, got = after > before ? +1 : -1;
        if (wanted != got) {
            if (kind == F) apSign = -apSign; else ssSign = -ssSign;
            host.log((kind == F ? "aperture" : "shutter") + " increment direction learned: sign " + (kind == F ? apSign : ssSign));
        }
    }

    // ------------------------------------------------------------------ helpers
    private int ssPos() { return ssD > 0 ? ssThirds(ssN, ssD, ssMinN, ssMinD) : -1; }

    /** thirds of a stop from the fastest available speed (n0/d0, default 1/4000) to n/d. */
    private static int ssThirds(int n, int d, int n0, int d0) {
        if (n <= 0 || d <= 0) { n = 30; d = 1; }
        if (n0 <= 0 || d0 <= 0) { n0 = 1; d0 = 4000; }
        return (int) Math.round(3 * Math.log(((double) n / d) / ((double) n0 / d0)) / Math.log(2));
    }

    /** thirds of a stop between two f-numbers (x100). */
    private static int thirdsF(int f, int f0) { return (int) Math.round(6 * Math.log((double) f / f0) / Math.log(2)); }

    static String shutterLabel(int n, int d) {
        if (n <= 0 || d <= 0) return "–";
        if (n >= d) { double s = (double) n / d; return fmt(s) + "\""; }
        return "1/" + Math.round((double) d / n);
    }

    private static String fmt(double v) {
        return Math.abs(v - Math.round(v)) < 0.05 ? String.valueOf(Math.round(v)) : String.format("%.1f", v);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}

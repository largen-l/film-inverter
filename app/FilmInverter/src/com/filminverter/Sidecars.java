package com.filminverter;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Inversion settings for raw editors, written next to an ARW so the editor opens it as a positive.
 * No Android imports: the desktop test (test/SidecarsDesktopTest.java) renders the same files with darktable-cli and
 * rawtherapee-cli.
 *
 * All three start from the same fact: the ARW's own white balance is the one Calibrate film base set, so the clear
 * film base is neutral in each editor's "as shot" rendering. What each needs is where the base sits in its own
 * pipeline (BASE_* below, measured on this camera with the editors' command-line tools) and the per-channel contrast of
 * C-41 film, which the camera's single tone curve can't do:
 *   Lightroom / Camera Raw  NAME.xmp      As Shot WB + per-channel inverted point curves (black = base)
 *   darktable               NAME.ARW.xmp  negadoctor (its negative module) with Dmin = base
 *   RawTherapee (5.9+)      NAME.ARW.pp3  Film Negative with the base as reference input
 */
public final class Sidecars {
    public static final int LIGHTROOM = 1, DARKTABLE = 2, RAWTHERAPEE = 4;

    /** Inputs, from the roll's base calibration. */
    public static final class Params {
        /** Film base RGB codes in the camera's plain-curve measurement JPEG (white balance applied). */
        public int[] baseCode = new int[3];
        public String film = "";
        public int temp, tint;
        /** C-41 contrast per channel relative to green (RawTherapee's defaults, also used for the others). */
        public double redRatio = 1.36, greenExp = 1.5, blueRatio = 0.86;
    }

    // Where the base sits in each editor, as a factor on its linear value in the camera JPEG (sRGB decode).
    // Tuned on the a6500 with darktable 5.6.2 / RawTherapee 5.13 (see docs/sidecars.md); Lightroom: untested estimate.
    public static double DT_BASE = 1.0, RT_BASE = 1.0, LR_BASE = 1.0;
    /** Lightroom: density range from base to the white point, as a linear ratio (1.6 D). */
    public static double LR_RANGE = 0.025;

    public static String describe(int mode) {
        if (mode == 0) return "off";
        List<String> s = new ArrayList<String>();
        if ((mode & LIGHTROOM) != 0) s.add("Lightroom");
        if ((mode & DARKTABLE) != 0) s.add("darktable");
        if ((mode & RAWTHERAPEE) != 0) s.add("RawTherapee");
        if (s.size() == 3) return "all three";
        StringBuilder b = new StringBuilder();
        for (String x : s) b.append(b.length() > 0 ? " + " : "").append(x);
        return b.toString();
    }

    /**
     * Writes the selected sidecars next to `arw`; returns their names. The camera only creates 8.3 names, so only
     * Lightroom's NAME.XMP has its final name; darktable (NAME.ARW.xmp) and RawTherapee (NAME.ARW.pp3, the only name
     * it reads) get NAME.DTX / NAME.PP3, renamed after copying by `filminverter sidecars <folder>`.
     */
    public static List<String> write(File arw, int mode, Params p) throws Exception {
        List<String> out = new ArrayList<String>();
        String n = arw.getName(), stem = n.substring(0, n.lastIndexOf('.'));
        if ((mode & LIGHTROOM) != 0) out.add(save(new File(arw.getParentFile(), stem + ".XMP"), lightroom(p)));
        if ((mode & DARKTABLE) != 0) out.add(save(new File(arw.getParentFile(), stem + ".DTX"), darktable(p)));
        if ((mode & RAWTHERAPEE) != 0) out.add(save(new File(arw.getParentFile(), stem + ".PP3"), rawtherapee(p)));
        return out;
    }

    private static String save(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(s.getBytes("UTF-8")); } finally { o.close(); }
        return f.getName();
    }

    static double lin(int code) {
        double x = Math.max(1, code) / 255.0;
        return x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
    }

    static double enc(double v) {
        v = Math.max(0, Math.min(1, v));
        return v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
    }

    /** Linear base per channel as the camera measured it (neutral after calibration, but kept per channel). */
    static double[] baseLin(Params p) {
        return new double[]{lin(p.baseCode[0]), lin(p.baseCode[1]), lin(p.baseCode[2])};
    }

    static double[] exps(Params p) {
        return new double[]{p.greenExp * p.redRatio, p.greenExp, p.greenExp * p.blueRatio};
    }

    private static String f(double v) { return String.format(Locale.US, "%.6f", v); }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ------------------------------------------------------------------ Lightroom / Camera Raw
    /**
     * Point curves in Lightroom's (gamma-encoded) curve space: a power law y = x^-p keeps its exponent through any
     * gamma encoding, so each channel gets y = 255 * ((xb / x)^p - 1) / ((xb / xw)^p - 1): base xb -> 0 (black),
     * xw (LR_RANGE denser than the base) -> 255. The master curve stays linear.
     */
    public static String lightroom(Params p) {
        double[] b = baseLin(p), e = exps(p);
        StringBuilder curves = new StringBuilder();
        String[] names = {"Red", "Green", "Blue"};
        for (int c = 0; c < 3; c++) {
            double xb = 255 * enc(b[c] * LR_BASE), xw = 255 * enc(b[c] * LR_BASE * LR_RANGE);
            double top = Math.pow(xb / xw, e[c]) - 1;
            curves.append("   <crs:ToneCurvePV2012").append(names[c]).append(">\n    <rdf:Seq>\n");
            List<int[]> pts = new ArrayList<int[]>();
            pts.add(new int[]{0, 255});
            int steps = 12;
            for (int i = 0; i <= steps; i++) {
                double x = xw + (xb - xw) * i / (double) steps;
                double y = 255 * (Math.pow(xb / x, e[c]) - 1) / top;
                int xi = (int) Math.round(x), yi = (int) Math.round(Math.max(0, Math.min(255, y)));
                if (xi > pts.get(pts.size() - 1)[0] && xi < 255) pts.add(new int[]{xi, yi});
            }
            pts.add(new int[]{255, 0});
            for (int[] q : pts) curves.append("     <rdf:li>").append(q[0]).append(", ").append(q[1]).append("</rdf:li>\n");
            curves.append("    </rdf:Seq>\n   </crs:ToneCurvePV2012").append(names[c]).append(">\n");
        }
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Film Inverter\">\n"
            + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
            + "  <rdf:Description rdf:about=\"\"\n"
            + "    xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\"\n"
            + "    xmlns:dc=\"http://purl.org/dc/elements/1.1/\"\n"
            + "   crs:Version=\"15.0\"\n"
            + "   crs:ProcessVersion=\"11.0\"\n"
            + "   crs:WhiteBalance=\"As Shot\"\n"
            + "   crs:CameraProfile=\"Adobe Standard\"\n"
            + "   crs:Exposure2012=\"0\" crs:Contrast2012=\"0\" crs:Highlights2012=\"0\" crs:Shadows2012=\"0\"\n"
            + "   crs:Whites2012=\"0\" crs:Blacks2012=\"0\"\n"
            + "   crs:ToneCurveName2012=\"Custom\"\n"
            + "   crs:HasSettings=\"True\">\n"
            + "   <crs:ToneCurvePV2012>\n    <rdf:Seq>\n     <rdf:li>0, 0</rdf:li>\n     <rdf:li>255, 255</rdf:li>\n    </rdf:Seq>\n   </crs:ToneCurvePV2012>\n"
            + curves
            + "   <dc:description>\n    <rdf:Alt>\n     <rdf:li xml:lang=\"x-default\">Film Inverter: " + esc(p.film.length() > 0 ? p.film : "negative")
            + ", base " + p.baseCode[0] + "/" + p.baseCode[1] + "/" + p.baseCode[2] + "</rdf:li>\n    </rdf:Alt>\n   </dc:description>\n"
            + "  </rdf:Description>\n </rdf:RDF>\n</x:xmpmeta>\n";
    }

    // ------------------------------------------------------------------ darktable
    /** darktable's own "no blending" parameters (blend version 13, from src/common/exif.cc). */
    private static final String NO_BLEND = "gz11eJxjYGBgkGAAgRNODGiAEV0AJ2iwh+CRyscOAAdeGQQ=";

    /** negadoctor params v2 (src/iop/negadoctor.c): int film_stock, float Dmin[4], wb_high[4], wb_low[4], D_max, offset, black, gamma, soft_clip, exposure. */
    static String negadoctorHex(double[] dmin, double dMax, double offset, double black, double gamma, double softClip, double exposure) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(4 + 12 * 4 + 6 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.putInt(1);                                                   // DT_FILMSTOCK_COLOR
        for (int i = 0; i < 3; i++) bb.putFloat((float) dmin[i]);
        bb.putFloat(1f);
        for (int k = 0; k < 2; k++) { for (int i = 0; i < 4; i++) bb.putFloat(1f); }   // wb_high, wb_low
        bb.putFloat((float) dMax).putFloat((float) offset).putFloat((float) black).putFloat((float) gamma)
          .putFloat((float) softClip).putFloat((float) exposure);
        StringBuilder s = new StringBuilder();
        for (byte x : bb.array()) s.append(String.format("%02x", x & 255));
        return s.toString();
    }

    public static String darktable(Params p) {
        double[] b = baseLin(p), d = new double[3];
        for (int c = 0; c < 3; c++) d[c] = Math.max(0.00001, Math.min(1.5, b[c] * DT_BASE));
        String hex = negadoctorHex(d, DT_DMAX, DT_OFFSET, DT_BLACK, DT_GAMMA, DT_SOFTCLIP, DT_EXPOSURE);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"XMP Core 4.4.0-Exiv2\">\n"
            + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
            + "  <rdf:Description rdf:about=\"\"\n"
            + "    xmlns:darktable=\"http://darktable.sf.net/\"\n"
            + "   darktable:xmp_version=\"4\"\n"
            + "   darktable:auto_presets_applied=\"0\"\n"
            + "   darktable:iop_order_version=\"4\"\n"
            + "   darktable:history_end=\"1\">\n"
            + "   <darktable:history>\n    <rdf:Seq>\n     <rdf:li\n"
            + "      darktable:num=\"0\"\n      darktable:operation=\"negadoctor\"\n      darktable:enabled=\"1\"\n"
            + "      darktable:modversion=\"2\"\n      darktable:params=\"" + hex + "\"\n"
            + "      darktable:multi_name=\"\"\n      darktable:multi_name_hand_edited=\"0\"\n      darktable:multi_priority=\"0\"\n"
            + "      darktable:blendop_version=\"13\"\n      darktable:blendop_params=\"" + NO_BLEND + "\"/>\n"
            + "    </rdf:Seq>\n   </darktable:history>\n  </rdf:Description>\n </rdf:RDF>\n</x:xmpmeta>\n";
    }

    // negadoctor print settings (darktable defaults unless tuned)
    public static double DT_DMAX = 2.046, DT_OFFSET = -0.05, DT_BLACK = 0.0755, DT_GAMMA = 4.0, DT_SOFTCLIP = 0.75, DT_EXPOSURE = 0.9245;

    // ------------------------------------------------------------------ RawTherapee
    /** Film Negative (procparams 353): base as reference input in working space (0..65535), mapped to a near-black gray. */
    public static String rawtherapee(Params p) {
        double[] b = baseLin(p);
        String in = f(b[0] * RT_BASE * 65535) + ";" + f(b[1] * RT_BASE * 65535) + ";" + f(b[2] * RT_BASE * 65535) + ";";
        String o = f(RT_OUT);
        return "[Version]\nAppVersion=5.13\nVersion=353\n\n"
            + "[Film Negative]\nEnabled=true\n"
            + "RedRatio=" + f(p.redRatio) + "\nGreenExponent=" + f(p.greenExp) + "\nBlueRatio=" + f(p.blueRatio) + "\n"
            + "ColorSpace=1\nRefInput=" + in + "\nRefOutput=" + o + ";" + o + ";" + o + ";\n";
    }

    /** Output value of the base in RawTherapee (its own base picker uses 65535/512). */
    public static double RT_OUT = 65535.0 / 512;
}

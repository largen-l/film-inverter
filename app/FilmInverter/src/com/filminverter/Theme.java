package com.filminverter;

import android.graphics.Color;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * UI theme: a layout (one of the eight UI concepts, see MainActivity's layout builders) plus colors, corner
 * radius and text size. All layouts are static Android views over the hardware live view: switching rebuilds
 * the views once and costs nothing per frame.
 *
 * Themes come from the built-in list below plus every *.JSN file in /FILMINV/THEMES/ on the card (8.3 file
 * names only: the camera refuses longer ones). The desktop tool's Themes tab makes those files. JSON keys
 * (all optional except name; colors as "#RRGGBB" or "#AARRGGBB"):
 *   name, layout, accent, onAccent, text, textDim, bar, pill, pillText, chipBorder, radius (dp-ish px), textScale
 */
public final class Theme {
    /** Layout ids, in the order of the concepts; a theme file may use any of them. */
    public static final String[] LAYOUTS = {"minimal", "camera", "material", "glass", "enlarger", "lighttable", "scope", "safelight"};

    public String name;
    public String layout = "minimal";
    public int accent, onAccent, text, textDim, bar, pill, pillText, chipBorder;
    public int radius;
    public float textScale = 1f;
    public String source = "built-in";

    private Theme(String layout, String name, String accent, String onAccent, String text, String textDim, String bar,
                  String pill, String pillText, String chipBorder, int radius) {
        this(name, accent, onAccent, text, textDim, bar, pill, pillText, chipBorder, radius);
        this.layout = layout;
    }

    private Theme(String name, String accent, String onAccent, String text, String textDim, String bar,
                  String pill, String pillText, String chipBorder, int radius) {
        this.name = name;
        this.accent = Color.parseColor(accent);
        this.onAccent = Color.parseColor(onAccent);
        this.text = Color.parseColor(text);
        this.textDim = Color.parseColor(textDim);
        this.bar = Color.parseColor(bar);
        this.pill = Color.parseColor(pill);
        this.pillText = Color.parseColor(pillText);
        this.chipBorder = Color.parseColor(chipBorder);
        this.radius = radius;
    }

    /** The eight UI concepts (docs: UI concepts canvas), each with its own layout and palette. */
    public static List<Theme> builtIns() {
        return new ArrayList<Theme>(Arrays.asList(
            new Theme("minimal", "Minimal", "#f2c14e", "#0b0b0b", "#f2f2f2", "#b8b8b8", "#c7080808", "#8c000000", "#f2f2f2", "#3a3a3a", 12),
            new Theme("camera", "Camera", "#ff8a3d", "#000000", "#ececec", "#bdbdbd", "#e6000000", "#f0141414", "#ececec", "#2a2a2a", 6),
            new Theme("material", "Material", "#82d5c8", "#00201b", "#e3e3e3", "#bec9c6", "#eb1d2321", "#d9111414", "#e3e3e3", "#5b6563", 20),
            new Theme("glass", "Glass", "#ffd60a", "#111111", "#ffffff", "#d0d0d0", "#9e1c1c1e", "#8c1e1e1e", "#ffffff", "#4dffffff", 24),
            new Theme("enlarger", "Enlarger", "#ff3b2e", "#fff3f1", "#e6e6e6", "#a8a8a8", "#f0262829", "#ff1a1714", "#e9c84a", "#5a5d61", 6),
            new Theme("lighttable", "Light table", "#e0443c", "#ffffff", "#f3e3b5", "#b8a888", "#e6151210", "#f03b3122", "#e8a44a", "#2a2118", 3),
            new Theme("scope", "Scope", "#52e08a", "#050505", "#d9d9d9", "#8a8a8a", "#e6050505", "#b3000000", "#52e08a", "#2c2c2c", 0),
            new Theme("safelight", "Safelight", "#ff4a3d", "#000000", "#ff4a3d", "#c0392b", "#f0000000", "#ff000000", "#ff4a3d", "#ff4a3d", 14)
        ));
    }

    private static String layoutOf(String s) {
        if (s != null) for (String l : LAYOUTS) if (l.equalsIgnoreCase(s.trim())) return l;
        return "minimal";
    }

    /** Built-ins plus valid theme files from the card; bad files are reported in `errors` and skipped. */
    public static List<Theme> all(File dir, List<String> errors) {
        List<Theme> list = builtIns();
        File[] fs = dir.listFiles();
        if (fs == null) return list;
        Arrays.sort(fs);
        for (File f : fs) {
            if (!f.getName().toUpperCase().endsWith(".JSN")) continue;
            try {
                list.add(fromJson(readAll(f), f.getName()));
            } catch (Throwable t) {
                errors.add(f.getName() + ": " + t.getMessage());
            }
        }
        return list;
    }

    static Theme fromJson(String json, String source) throws Exception {
        JSONObject o = new JSONObject(json);
        String layout = layoutOf(o.optString("layout", "minimal"));
        Theme base = builtIns().get(0);                      // colors left out come from that layout's built-in
        for (Theme b : builtIns()) if (b.layout.equals(layout)) base = b;
        Theme t = new Theme(o.getString("name"),
                o.optString("accent", hex(base.accent)), o.optString("onAccent", hex(base.onAccent)),
                o.optString("text", hex(base.text)), o.optString("textDim", hex(base.textDim)),
                o.optString("bar", hex(base.bar)), o.optString("pill", hex(base.pill)),
                o.optString("pillText", o.optString("text", hex(base.pillText))),
                o.optString("chipBorder", hex(base.chipBorder)), o.optInt("radius", base.radius));
        t.layout = layout;
        t.textScale = (float) Math.max(0.7, Math.min(1.6, o.optDouble("textScale", 1.0)));
        t.source = source;
        return t;
    }

    private static String hex(int c) { return String.format("#%08x", c); }

    private static String readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024)];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new String(b, 0, n, "UTF-8");
        } finally {
            in.close();
        }
    }
}

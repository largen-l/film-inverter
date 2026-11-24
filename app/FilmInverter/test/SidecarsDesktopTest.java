import com.filminverter.Sidecars;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Writes the camera's sidecars for one ARW under the names the desktop editors read, so they can be rendered with
 * darktable-cli / rawtherapee-cli and compared (tool: tune loop in docs/sidecars.md).
 *
 *   javac -d build/sidecars src/com/filminverter/Sidecars.java test/SidecarsDesktopTest.java
 *   java -cp build/sidecars SidecarsDesktopTest <file.ARW> <baseR> <baseG> <baseB> [NAME=value ...]
 *
 * NAME is any public tunable of Sidecars (DT_BASE, RT_BASE, LR_BASE, LR_RANGE, DT_GAMMA, ...).
 * Writes <file.ARW>.xmp (darktable), <file.ARW>.pp3 (RawTherapee) and <file>.xmp (Lightroom).
 */
public class SidecarsDesktopTest {
    public static void main(String[] a) throws Exception {
        File arw = new File(a[0]);
        Sidecars.Params p = new Sidecars.Params();
        for (int c = 0; c < 3; c++) p.baseCode[c] = Integer.parseInt(a[1 + c]);
        double wbR = 0, wbB = 0;
        for (int i = 4; i < a.length; i++) {
            String[] kv = a[i].split("=", 2);
            if (kv[0].equals("wb")) { String[] w = kv[1].split(","); wbR = Double.parseDouble(w[0]); wbB = Double.parseDouble(w[1]); }
            else if (kv[0].equals("redRatio")) p.redRatio = Double.parseDouble(kv[1]);
            else if (kv[0].equals("greenExp")) p.greenExp = Double.parseDouble(kv[1]);
            else if (kv[0].equals("blueRatio")) p.blueRatio = Double.parseDouble(kv[1]);
            else Sidecars.class.getField(kv[0]).setDouble(null, Double.parseDouble(kv[1]));
        }
        if (System.getProperty("camera") != null) {                // exactly what the camera writes (8.3 names)
            System.out.println(Sidecars.write(arw, Sidecars.LIGHTROOM | Sidecars.DARKTABLE | Sidecars.RAWTHERAPEE, p));
            return;
        }
        String n = arw.getName(), stem = n.substring(0, n.lastIndexOf('.'));
        String dt = Sidecars.darktable(p);
        save(new File(arw.getParentFile(), n + ".xmp"), wbR > 0 ? Sidecars.darktableWithWb(dt, wbR, wbB) : dt);
        save(new File(arw.getParentFile(), n + ".pp3"), Sidecars.rawtherapee(p));
        save(new File(arw.getParentFile(), stem + ".xmp"), Sidecars.lightroom(p));
    }

    private static void save(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(s.getBytes("UTF-8")); } finally { o.close(); }
    }
}

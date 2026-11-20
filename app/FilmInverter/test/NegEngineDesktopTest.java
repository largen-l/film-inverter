import com.filminverter.engine.NegEngine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Desktop check that the Java LUT engine matches engine/negengine.py.
 *
 *   javac -d build src/com/filminverter/engine/NegEngine.java test/NegEngineDesktopTest.java
 *   java -cp build NegEngineDesktopTest <roll folder> auto [python positives folder]
 *   java -cp build NegEngineDesktopTest <roll folder> <base x0> <base y0> <base x1> <base y1> [python positives folder]
 *
 * "auto" = base and picture area from NegEngine.analyzeFrame (as invert_roll.py does by default);
 * otherwise the base is sampled from the given pixel rectangle on the first frame.
 * Levels always come from each frame's picture area, as in negengine.calibrate_roll.
 * Input: *.JPG or *.PNG. Writes <roll>/positives_java/*_POS.png and, if a Python output folder is given,
 * prints the mean absolute difference per frame (0..255 scale) and fails above 3.
 */
public class NegEngineDesktopTest {
    public static void main(String[] a) throws Exception {
        File roll = new File(a[0]);
        boolean auto = a[1].equalsIgnoreCase("auto");
        int argPy = auto ? 2 : 5;
        File pyOut = a.length > argPy ? new File(a[argPy]) : null;

        File[] files = roll.listFiles();
        java.util.Arrays.sort(files);
        List<File> frames = new ArrayList<File>();
        for (File f : files) {
            String n = f.getName().toUpperCase();
            if (n.endsWith(".JPG") || n.endsWith(".PNG")) frames.add(f);
        }

        NegEngine.Profile p = new NegEngine.Profile();
        List<NegEngine.Frame> info = new ArrayList<NegEngine.Frame>();
        List<double[]> bases = new ArrayList<double[]>();
        for (File f : frames) {
            BufferedImage im = ImageIO.read(f);
            int[] px = im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth());
            NegEngine.Frame fi = NegEngine.analyzeFrame(px, im.getWidth(), im.getHeight());
            System.out.println(f.getName() + " frame " + fi);
            info.add(fi);
            if (fi.base != null) bases.add(fi.base);
        }
        if (auto) {
            if (bases.isEmpty()) throw new IllegalStateException("no film border found: pass a base rectangle");
            p.base = new double[3];
            for (int ch = 0; ch < 3; ch++) {                       // median over frames, like invert_roll.py
                double[] v = new double[bases.size()];
                for (int i = 0; i < v.length; i++) v[i] = bases.get(i)[ch];
                java.util.Arrays.sort(v);
                p.base[ch] = v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
            }
        } else {
            BufferedImage first = ImageIO.read(frames.get(0));
            int[] fp = first.getRGB(0, 0, first.getWidth(), first.getHeight(), null, 0, first.getWidth());
            p.base = NegEngine.sampleBase(fp, first.getWidth(), Integer.parseInt(a[1]), Integer.parseInt(a[2]),
                    Integer.parseInt(a[3]), Integer.parseInt(a[4]));
        }

        NegEngine.Histogram h = new NegEngine.Histogram(p);
        for (int i = 0; i < frames.size(); i++) {
            BufferedImage im = ImageIO.read(frames.get(i));
            int[] px = im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth());
            h.addArea(px, im.getWidth(), info.get(i), 1);
        }
        NegEngine.levelsFromHistogram(p, h);
        int[][] lut = NegEngine.buildLuts(p);
        System.out.println(NegEngine.toJson(p));

        File out = new File(roll, "positives_java");
        out.mkdirs();
        double worst = 0;
        for (File f : frames) {
            BufferedImage im = ImageIO.read(f);
            int w = im.getWidth(), ht = im.getHeight();
            int[] px = im.getRGB(0, 0, w, ht, null, 0, w);
            long t = System.nanoTime();
            NegEngine.apply(px, 0, px.length, lut, p.film);
            double ms = (System.nanoTime() - t) / 1e6;
            BufferedImage o = new BufferedImage(w, ht, BufferedImage.TYPE_INT_RGB);
            o.setRGB(0, 0, w, ht, px, 0, w);
            String stem = f.getName().replaceAll("\\.[^.]+$", "");
            ImageIO.write(o, "png", new File(out, stem + "_POS.png"));
            String line = String.format("%s apply %.1f ms", f.getName(), ms);
            if (pyOut != null) {
                BufferedImage ref = ImageIO.read(new File(pyOut, stem + "_POS.JPG"));
                int[] rp = ref.getRGB(0, 0, w, ht, null, 0, w);
                double sum = 0;
                for (int i = 0; i < px.length; i++)
                    for (int s = 0; s <= 16; s += 8)
                        sum += Math.abs(((px[i] >> s) & 255) - ((rp[i] >> s) & 255));
                double mad = sum / (px.length * 3.0);
                worst = Math.max(worst, mad);
                line += String.format("  |java-python| = %.2f", mad);
            }
            System.out.println(line);
        }
        if (pyOut != null && worst > 3.0) {
            System.err.println("FAIL: Java engine deviates from Python reference");
            System.exit(1);
        }
        System.out.println("OK");
    }
}

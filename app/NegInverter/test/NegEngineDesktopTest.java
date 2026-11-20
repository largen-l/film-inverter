import com.autofilmscan.engine.NegEngine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Desktop check that the Java LUT engine matches engine/negengine.py.
 *
 *   javac -d build src/com/autofilmscan/engine/NegEngine.java test/NegEngineDesktopTest.java
 *   java -cp build NegEngineDesktopTest <roll folder> <base x0> <base y0> <base x1> <base y1> [python positives folder]
 *
 * Writes <roll>/positives_java/*_POS.png and, if a Python output folder is given,
 * prints the mean absolute difference per frame (0..255 scale) and fails above 3.
 */
public class NegEngineDesktopTest {
    public static void main(String[] a) throws Exception {
        File roll = new File(a[0]);
        int bx0 = Integer.parseInt(a[1]), by0 = Integer.parseInt(a[2]);
        int bx1 = Integer.parseInt(a[3]), by1 = Integer.parseInt(a[4]);
        File pyOut = a.length > 5 ? new File(a[5]) : null;

        File[] files = roll.listFiles();
        java.util.Arrays.sort(files);
        List<File> frames = new ArrayList<File>();
        for (File f : files) if (f.getName().toUpperCase().endsWith(".JPG")) frames.add(f);

        NegEngine.Profile p = new NegEngine.Profile();
        BufferedImage first = ImageIO.read(frames.get(0));
        int[] fp = first.getRGB(0, 0, first.getWidth(), first.getHeight(), null, 0, first.getWidth());
        p.base = NegEngine.sampleBase(fp, first.getWidth(), bx0, by0, bx1, by1);

        NegEngine.Histogram h = new NegEngine.Histogram(p);
        for (File f : frames) {
            BufferedImage im = ImageIO.read(f);
            int[] px = im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth());
            h.add(px, 0, px.length, 1);
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

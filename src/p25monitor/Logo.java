package p25monitor;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.Locale;

/**
 * The TrunkDeck logo (1.4.5): a scanner console with a waveform screen, antenna and signal arcs on a dark navy rounded
 * square. Vector shapes on a 1024-unit grid, painted with Java2D (window icons, header) and exported as SVG; outside the
 * rounded square is fully transparent. Small sizes use simplified art so they stay legible.
 */
public final class Logo {
    private Logo() {}

    public enum Detail { FULL, MEDIUM, SMALL }
    public static Detail detailFor(int px) { return px >= 96 ? Detail.FULL : px >= 40 ? Detail.MEDIUM : Detail.SMALL; }

    /** One drawing step: a shape filled (stroke <= 0) or stroked, with a colour or a vertical gradient. */
    record Op(Shape shape, Color c1, Color c2, double y1, double y2, double stroke, boolean roundCap) {}

    static final Color CY = new Color(0x22E3F7), WAVE = new Color(0x3FF0FC), AMB = new Color(0xE4931E);
    static Color a(Color c, double alpha) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(alpha * 255)); }

    static List<Op> ops(Detail d) {
        List<Op> o = new ArrayList<>();
        boolean full = d == Detail.FULL, small = d == Detail.SMALL;
        // background rounded square (everything else stays inside it)
        o.add(fillG(new RoundRectangle2D.Double(10, 12, 1003, 997, 400, 400), new Color(0x0D1E3E), new Color(0x071630), 12, 1009));
        if (full) o.add(stroke(new RoundRectangle2D.Double(12, 14, 999, 993, 396, 396), a(Color.WHITE, 0.05), 4, false));
        // antenna + signal arcs
        double aw = small ? 56 : 32;
        RoundRectangle2D ant = new RoundRectangle2D.Double(337 - aw / 2, 128, aw, 182, aw, aw);
        if (full) o.add(stroke(ant, a(CY, 0.22), 20, true));
        o.add(fill(ant, small ? CY : new Color(0x173557)));
        if (!small) o.add(stroke(ant, CY, 8, true));
        o.add(fill(new RoundRectangle2D.Double(310, 298, 56, 38, 12, 12), new Color(0x142340)));
        double w1 = small ? 34 : 18, w2 = small ? 30 : 16;
        Arc2D inner = new Arc2D.Double(373 - 57, 205 - 57, 114, 114, -52, 104, Arc2D.OPEN);
        Arc2D outer = new Arc2D.Double(399 - 104, 214 - 104, 208, 208, -54, 108, Arc2D.OPEN);
        if (small) inner = new Arc2D.Double(373 - 75, 214 - 75, 150, 150, -50, 100, Arc2D.OPEN);
        if (full) { o.add(stroke(inner, a(CY, 0.2), w1 + 14, true)); o.add(stroke(outer, a(CY, 0.2), w2 + 14, true)); }
        o.add(stroke(inner, CY, w1, true));
        if (!small) o.add(stroke(outer, CY, w2, true));
        else o.add(stroke(new Arc2D.Double(373 - 150, 214 - 150, 300, 300, -48, 96, Arc2D.OPEN), CY, w2, true));
        // console body (dark lip below for depth)
        o.add(fill(new RoundRectangle2D.Double(185, 352, 654, 482, 96, 96), new Color(0x0A172F)));
        o.add(fillG(new RoundRectangle2D.Double(185, 330, 654, 474, 96, 96), new Color(0x1F3155), new Color(0x162848), 330, 804));
        if (!small) o.add(stroke(new RoundRectangle2D.Double(187, 332, 650, 470, 92, 92), new Color(0x2B3E66), 4, false));
        // screen
        o.add(fill(new RoundRectangle2D.Double(214, 364, 596, 262, 64, 64), new Color(0x000F24)));
        o.add(fillG(new RoundRectangle2D.Double(226, 376, 572, 238, 52, 52), new Color(0x0B1F42), new Color(0x061430), 376, 614));
        // waveform: centre line + symmetric bars
        double[] halves = {96, 72, 52, 64, 46, 54, 36, 40, 26, 22, 14, 10};
        if (!small) o.add(stroke(new Line2D.Double(250, 497, 774, 497), WAVE, full ? 4 : 10, true));
        int nSide = small ? 3 : d == Detail.MEDIUM ? 6 : 11;
        double step = small ? 64 : d == Detail.MEDIUM ? 34 : 18.5, bw = small ? 34 : d == Detail.MEDIUM ? 16 : 7;
        List<Shape> bars = new ArrayList<>();
        for (int i = -nSide; i <= nSide; i++) {
            int k = Math.abs(i);
            double hh = small ? new double[]{96, 70, 48, 28}[k] : d == Detail.MEDIUM ? new double[]{96, 70, 58, 46, 34, 22, 12}[k] : halves[k];
            double x = 512 + i * step;
            bars.add(new Line2D.Double(x, 497 - hh, x, 497 + hh));
        }
        if (full) for (Shape b : bars) o.add(stroke(b, a(WAVE, 0.18), bw + 12, true));
        for (Shape b : bars) o.add(stroke(b, WAVE, bw, true));
        // controls
        if (small) {
            o.add(fill(new RoundRectangle2D.Double(226, 652, 150, 80, 40, 40), new Color(0x0C90A7)));
            o.add(fill(new Ellipse2D.Double(452 - 46, 692 - 46, 92, 92), AMB));
            o.add(fill(new RoundRectangle2D.Double(600, 652, 200, 80, 30, 30), AMB));
        } else {
            o.add(fill(new RoundRectangle2D.Double(230, 650, 90, 36, 36, 36), new Color(0x0C90A7)));
            o.add(fill(new RoundRectangle2D.Double(230, 703, 90, 36, 36, 36), new Color(0x0C90A7)));
            o.add(fill(new Ellipse2D.Double(403 - 46, 692 - 46, 92, 92), new Color(0x06162D)));
            o.add(fillG(new Ellipse2D.Double(403 - 36, 692 - 36, 72, 72), new Color(0xC98320), new Color(0x9A5C17), 656, 728));
            Path2D dia = new Path2D.Double(); dia.moveTo(493, 678); dia.lineTo(507, 692); dia.lineTo(493, 706); dia.lineTo(479, 692); dia.closePath();
            if (full) o.add(fill(dia, new Color(0xF7A51E)));
            o.add(fill(new Ellipse2D.Double(592 - 46, 692 - 46, 92, 92), new Color(0x06162D)));
            o.add(fill(new Ellipse2D.Double(592 - 40, 692 - 40, 80, 80), new Color(0x131F37)));
            if (full) o.add(stroke(new Ellipse2D.Double(592 - 40, 692 - 40, 80, 80), new Color(0x22314F), 4, false));
            o.add(stroke(new Arc2D.Double(592 - 24, 692 - 24, 48, 48, 95, 80, Arc2D.OPEN), CY, full ? 7 : 12, true));
            o.add(fill(new RoundRectangle2D.Double(668, 650, 138, 86, 32, 32), new Color(0x06162D)));
            o.add(fill(new RoundRectangle2D.Double(678, 660, 118, 66, 24, 24), AMB));
            if (full) for (int i = -1; i <= 1; i++) o.add(fill(new Ellipse2D.Double(512 + i * 39 - 6, 792 - 6, 12, 12), new Color(0x1CDEF7)));
        }
        return o;
    }

    static Op fill(Shape s, Color c) { return new Op(s, c, null, 0, 0, 0, false); }
    static Op fillG(Shape s, Color c1, Color c2, double y1, double y2) { return new Op(s, c1, c2, y1, y2, 0, false); }
    static Op stroke(Shape s, Color c, double w, boolean round) { return new Op(s, c, null, 0, 0, w, round); }

    /** Paints the logo into the square (x, y, size); antialiased, transparent outside the rounded square. */
    public static void paint(Graphics2D g0, double x, double y, double size, Detail d) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.translate(x, y);
        g.scale(size / 1024.0, size / 1024.0);
        for (Op op : ops(d)) {
            g.setPaint(op.c2() == null ? op.c1() : new GradientPaint(0, (float) op.y1(), op.c1(), 0, (float) op.y2(), op.c2()));
            if (op.stroke() > 0) { g.setStroke(new BasicStroke((float) op.stroke(), op.roundCap() ? BasicStroke.CAP_ROUND : BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)); g.draw(op.shape()); }
            else g.fill(op.shape());
        }
        g.dispose();
    }

    /** The logo as an image of size x size pixels (transparent corners). */
    public static BufferedImage image(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        paint(g, 0, 0, size, detailFor(size));
        g.dispose();
        return img;
    }

    /** The full-detail logo as SVG (scalable icon for Linux desktops). */
    public static String svg() {
        StringBuilder defs = new StringBuilder(), body = new StringBuilder();
        int gi = 0;
        for (Op op : ops(Detail.FULL)) {
            String paint;
            if (op.c2() != null) {
                String id = "g" + gi++;
                defs.append(String.format(Locale.ROOT, "<linearGradient id=\"%s\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"%.1f\" x2=\"0\" y2=\"%.1f\"><stop offset=\"0\" stop-color=\"%s\"/><stop offset=\"1\" stop-color=\"%s\"/></linearGradient>",
                        id, op.y1(), op.y2(), hex(op.c1()), hex(op.c2())));
                paint = "url(#" + id + ")";
            } else paint = hex(op.c1());
            String alpha = op.c1().getAlpha() < 255 ? String.format(Locale.ROOT, " %s-opacity=\"%.3f\"", op.stroke() > 0 ? "stroke" : "fill", op.c1().getAlpha() / 255.0) : "";
            body.append("<path d=\"").append(path(op.shape())).append('"');
            if (op.stroke() > 0) body.append(String.format(Locale.ROOT, " fill=\"none\" stroke=\"%s\" stroke-width=\"%.1f\" stroke-linecap=\"%s\" stroke-linejoin=\"round\"", paint, op.stroke(), op.roundCap() ? "round" : "butt"));
            else body.append(" fill=\"").append(paint).append('"');
            body.append(alpha).append("/>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1024\" height=\"1024\" viewBox=\"0 0 1024 1024\">\n<title>TrunkDeck</title>\n<defs>"
                + defs + "</defs>\n" + body + "</svg>\n";
    }

    static String hex(Color c) { return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }

    static String path(Shape s) {
        StringBuilder sb = new StringBuilder();
        double[] p = new double[6];
        for (PathIterator it = s.getPathIterator(null); !it.isDone(); it.next()) {
            int t = it.currentSegment(p);
            switch (t) {
                case PathIterator.SEG_MOVETO -> sb.append(String.format(Locale.ROOT, "M%.2f %.2f", p[0], p[1]));
                case PathIterator.SEG_LINETO -> sb.append(String.format(Locale.ROOT, "L%.2f %.2f", p[0], p[1]));
                case PathIterator.SEG_QUADTO -> sb.append(String.format(Locale.ROOT, "Q%.2f %.2f %.2f %.2f", p[0], p[1], p[2], p[3]));
                case PathIterator.SEG_CUBICTO -> sb.append(String.format(Locale.ROOT, "C%.2f %.2f %.2f %.2f %.2f %.2f", p[0], p[1], p[2], p[3], p[4], p[5]));
                case PathIterator.SEG_CLOSE -> sb.append('Z');
            }
        }
        return sb.toString();
    }

    /** Writes icons: trunkdeck-N.png for each size, trunkdeck.svg and trunkdeck.ico (PNG entries 16-256). Usage: Logo outDir */
    public static void main(String[] a) throws Exception {
        java.nio.file.Path out = java.nio.file.Paths.get(a[0]);
        java.nio.file.Files.createDirectories(out);
        int[] sizes = {16, 24, 32, 48, 64, 128, 256, 512};
        Map<Integer, byte[]> png = new LinkedHashMap<>();
        for (int s : sizes) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image(s), "png", b);
            png.put(s, b.toByteArray());
            java.nio.file.Files.write(out.resolve("trunkdeck-" + s + ".png"), b.toByteArray());
        }
        java.nio.file.Files.writeString(out.resolve("trunkdeck.svg"), svg());
        // ICO with PNG-compressed images (Windows Vista and later), 16-256
        int[] ico = {16, 24, 32, 48, 64, 128, 256};
        java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(6 + 16 * ico.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.putShort((short) 0).putShort((short) 1).putShort((short) ico.length);
        int off = 6 + 16 * ico.length;
        for (int s : ico) {
            byte[] d = png.get(s);
            h.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32).putInt(d.length).putInt(off);
            off += d.length;
        }
        f.write(h.array());
        for (int s : ico) f.write(png.get(s));
        java.nio.file.Files.write(out.resolve("trunkdeck.ico"), f.toByteArray());
        System.out.println("icons written to " + out);
    }
}

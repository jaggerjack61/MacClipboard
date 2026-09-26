import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Draws the app icon (a clipboard over a fanned stack of sheets, on the macOS icon
 * grid) and writes packaging/icon.icns plus the in-app copy used by Settings.
 *
 * <p>Usage, from the project root on macOS: {@code java scripts/GenerateAppIcon.java}</p>
 */
public final class GenerateAppIcon {

    private static final int S = 1024;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        BufferedImage master = draw();

        Path iconset = Files.createTempDirectory("clipboard").resolve("icon.iconset");
        Files.createDirectories(iconset);
        for (int size : new int[] {16, 32, 128, 256, 512}) {
            ImageIO.write(scale(master, size), "png", iconset.resolve("icon_" + size + "x" + size + ".png").toFile());
            ImageIO.write(scale(master, size * 2), "png",
                    iconset.resolve("icon_" + size + "x" + size + "@2x.png").toFile());
        }
        Process iconutil = new ProcessBuilder("iconutil", "-c", "icns", iconset.toString(),
                "-o", "packaging/icon.icns").inheritIO().start();
        if (iconutil.waitFor() != 0) {
            throw new IllegalStateException("iconutil failed");
        }
        ImageIO.write(scale(master, 256), "png", new File("src/main/resources/ui/app-icon.png"));
        ImageIO.write(master, "png", iconset.getParent().resolve("icon-1024.png").toFile());
        System.out.println("Wrote packaging/icon.icns and src/main/resources/ui/app-icon.png ("
                + iconset.getParent() + ")");
    }

    private static BufferedImage draw() {
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(img);

        // macOS grid: 824 pt body centered on a 1024 canvas, with a soft drop shadow.
        Shape body = squircle(100, 100, 824);
        shadow(img, body, new Color(0, 0, 0, 90), 28, 12);
        g.setPaint(new GradientPaint(100, 100, new Color(0x5B, 0xB0, 0xFF), 924, 924, new Color(0x3B, 0x4C, 0xF5)));
        g.fill(body);
        // Gentle top sheen and a hairline rim keep the plate from looking flat.
        g.setClip(body);
        g.setPaint(new GradientPaint(0, 100, new Color(255, 255, 255, 46), 0, 560, new Color(255, 255, 255, 0)));
        g.fillRect(0, 0, S, S);
        g.setClip(null);
        g.setStroke(new BasicStroke(4f));
        g.setColor(new Color(255, 255, 255, 40));
        g.draw(squircle(102, 102, 820));

        // Fanned sheets behind the board read as "history".
        RoundRectangle2D sheet = new RoundRectangle2D.Double(292, 262, 440, 520, 64, 64);
        drawSheet(img, g, sheet, -11, new Color(255, 255, 255, 120));
        drawSheet(img, g, sheet, 7, new Color(255, 255, 255, 170));

        // The board.
        RoundRectangle2D board = new RoundRectangle2D.Double(292, 262, 440, 520, 64, 64);
        shadow(img, board, new Color(10, 20, 90, 80), 26, 16);
        g.setPaint(new GradientPaint(0, 262, Color.WHITE, 0, 782, new Color(0xEE, 0xF2, 0xFA)));
        g.fill(board);

        // History rows: badge + line, the middle one selected.
        int[] ys = {420, 530, 640};
        int[] widths = {196, 236, 164};
        for (int i = 0; i < ys.length; i++) {
            boolean selected = i == 1;
            int y = ys[i];
            if (selected) {
                g.setColor(new Color(0xDD, 0xE9, 0xFF));
                g.fill(new RoundRectangle2D.Double(318, y - 42, 388, 84, 30, 30));
            }
            g.setColor(selected ? new Color(0x35, 0x7B, 0xF7) : new Color(0xD5, 0xDD, 0xEA));
            g.fill(new RoundRectangle2D.Double(346, y - 24, 48, 48, 16, 16));
            g.setColor(selected ? new Color(0x35, 0x7B, 0xF7) : new Color(0xC6, 0xD0, 0xE0));
            g.fill(new RoundRectangle2D.Double(418, y - 12, widths[i], 24, 24, 24));
        }

        // Metal clip.
        RoundRectangle2D clip = new RoundRectangle2D.Double(392, 212, 240, 104, 40, 40);
        shadow(img, clip, new Color(10, 20, 60, 110), 12, 8);
        g.setPaint(new GradientPaint(0, 212, new Color(0x4A, 0x51, 0x6E), 0, 316, new Color(0x25, 0x29, 0x3D)));
        g.fill(clip);
        g.setPaint(new GradientPaint(0, 214, new Color(255, 255, 255, 70), 0, 262, new Color(255, 255, 255, 0)));
        g.fill(new RoundRectangle2D.Double(396, 215, 232, 50, 36, 36));
        g.setColor(new Color(0x1A, 0x1D, 0x2C));
        g.fill(new Ellipse2D.Double(490, 238, 44, 44));
        g.setColor(new Color(0x5B, 0xB0, 0xFF));
        g.fill(new Ellipse2D.Double(498, 246, 28, 28));

        g.dispose();
        return img;
    }

    private static void drawSheet(BufferedImage img, Graphics2D g, RoundRectangle2D sheet, double degrees, Color fill) {
        Shape rotated = AffineTransform.getRotateInstance(Math.toRadians(degrees),
                sheet.getCenterX(), sheet.getCenterY() + 60).createTransformedShape(sheet);
        shadow(img, rotated, new Color(10, 20, 90, 45), 20, 10);
        g.setColor(fill);
        g.fill(rotated);
    }

    /** The macOS icon plate: a rounded square with a ~22.5% corner radius. */
    private static Shape squircle(double x, double y, double size) {
        double arc = size * 0.45;
        return new RoundRectangle2D.Double(x, y, size, size, arc, arc);
    }

    /** Paints a blurred copy of the shape under whatever is drawn next. */
    private static void shadow(BufferedImage target, Shape shape, Color color, int blur, int offsetY) {
        BufferedImage layer = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(layer);
        g.setColor(color);
        g.translate(0, offsetY);
        g.fill(shape);
        g.dispose();
        for (int pass = 0; pass < 3; pass++) {
            layer = boxBlur(layer, blur / 3 * 2 + 1, true);
            layer = boxBlur(layer, blur / 3 * 2 + 1, false);
        }
        Graphics2D t = target.createGraphics();
        t.setComposite(AlphaComposite.SrcOver);
        t.drawImage(layer, 0, 0, null);
        t.dispose();
    }

    private static BufferedImage boxBlur(BufferedImage src, int size, boolean horizontal) {
        float[] weights = new float[size];
        java.util.Arrays.fill(weights, 1f / size);
        Kernel kernel = horizontal ? new Kernel(size, 1, weights) : new Kernel(1, size, weights);
        return new ConvolveOp(kernel, ConvolveOp.EDGE_ZERO_FILL, null).filter(src, null);
    }

    /** Halves repeatedly with bilinear filtering: much cleaner than one big downscale. */
    private static BufferedImage scale(BufferedImage src, int size) {
        BufferedImage current = src;
        while (current.getWidth() / 2 >= size) {
            current = resize(current, current.getWidth() / 2);
        }
        return current.getWidth() == size ? current : resize(current, size);
    }

    private static BufferedImage resize(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(out);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    private static Graphics2D graphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }
}

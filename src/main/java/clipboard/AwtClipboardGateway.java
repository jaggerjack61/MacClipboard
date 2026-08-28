package clipboard;

import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import javax.imageio.ImageIO;
import model.ClipboardContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AWT-backed {@link ClipboardGateway} for macOS. Reads plain + rich text and images;
 * writes plain text, rich text (HTML) and images back to the system clipboard.
 *
 * <p>Guards against the clipboard being temporarily owned by another application by
 * catching the associated exceptions and returning {@link Optional#empty()}.</p>
 */
public final class AwtClipboardGateway implements ClipboardGateway, ClipboardOwner {

    private static final Logger LOG = LoggerFactory.getLogger(AwtClipboardGateway.class);

    private static final DataFlavor HTML_FLAVOR = createHtmlFlavor();

    @Override
    public Optional<ClipboardSnapshot> read() {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable contents;
        try {
            contents = clipboard.getContents(this);
        } catch (IllegalStateException e) {
            LOG.debug("Clipboard unavailable during read", e);
            return Optional.empty();
        }
        if (contents == null) {
            return Optional.empty();
        }
        try {
            if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                Object img = contents.getTransferData(DataFlavor.imageFlavor);
                if (img instanceof java.awt.Image awtImage) {
                    return Optional.of(readImage(awtImage));
                }
            }
            String text = readText(contents);
            String html = readHtml(contents);
            if (html != null && text != null) {
                return Optional.of(new ClipboardSnapshot(ClipboardContentType.RICH_TEXT, text, html, null, null, 0, 0));
            }
            if (text != null) {
                return Optional.of(new ClipboardSnapshot(ClipboardContentType.TEXT, text, null, null, null, 0, 0));
            }
        } catch (UnsupportedFlavorException | IOException e) {
            LOG.debug("Clipboard flavor read failed", e);
        }
        return Optional.empty();
    }

    @Override
    public void write(ClipboardSnapshot snapshot) {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable transferable = switch (snapshot.contentType()) {
            case IMAGE -> new ImageSelection(snapshot.image());
            case RICH_TEXT -> new HtmlSelection(snapshot.text(), snapshot.html());
            default -> new StringSelection(snapshot.text() == null ? "" : snapshot.text());
        };
        try {
            clipboard.setContents(transferable, this);
        } catch (IllegalStateException e) {
            LOG.warn("Failed to write clipboard contents (another app owns it)", e);
        }
    }

    @Override
    public void lostOwnership(Clipboard clipboard, Transferable contents) {
        // We intentionally do not take the clipboard back: another app copied.
    }

    private static ClipboardSnapshot readImage(Image awtImage) throws IOException {
        BufferedImage full = toBufferedImage(awtImage);
        byte[] png = encodePng(full);
        BufferedImage thumb = scaleTo(full, 72);
        byte[] thumbPng = encodePng(thumb);
        return new ClipboardSnapshot(ClipboardContentType.IMAGE, null, null, png, thumbPng,
                full.getWidth(), full.getHeight());
    }

    private static String readText(Transferable t) {
        try {
            if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                Object value = t.getTransferData(DataFlavor.stringFlavor);
                return value instanceof String s ? s : null;
            }
        } catch (UnsupportedFlavorException | IOException e) {
            LOG.trace("text read failed", e);
        }
        return null;
    }

    private static String readHtml(Transferable t) {
        try {
            if (HTML_FLAVOR != null && t.isDataFlavorSupported(HTML_FLAVOR)) {
                Object value = t.getTransferData(HTML_FLAVOR);
                return htmlToString(value);
            }
        } catch (UnsupportedFlavorException | IOException e) {
            LOG.trace("html read failed", e);
        }
        return null;
    }

    private static String htmlToString(Object value) throws IOException {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (value instanceof InputStream in) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (value instanceof java.io.Reader reader) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
        return null;
    }

    private static DataFlavor createHtmlFlavor() {
        try {
            return new DataFlavor("text/html;class=java.lang.String");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("PNG writer unavailable");
        }
        return out.toByteArray();
    }

    private static BufferedImage toBufferedImage(Image image) {
        if (image instanceof BufferedImage b) {
            return b;
        }
        java.awt.image.PixelGrabber grabber = new java.awt.image.PixelGrabber(
                image, 0, 0, -1, -1, true);
        try {
            if (!grabber.grabPixels()) {
                throw new IOException("Could not read image from clipboard");
            }
            int w = grabber.getWidth();
            int h = grabber.getHeight();
            int[] pixels = (int[]) grabber.getPixels();
            BufferedImage converted = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            converted.setRGB(0, 0, w, h, pixels, 0, w);
            return converted;
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert AWT image", e);
        }
    }

    private static BufferedImage scaleTo(BufferedImage src, int maxDim) {
        int w = src.getWidth();
        int h = src.getHeight();
        double factor = Math.min(1.0, (double) maxDim / Math.max(w, h));
        if (factor >= 1.0) {
            return src;
        }
        int nw = Math.max(1, (int) Math.round(w * factor));
        int nh = Math.max(1, (int) Math.round(h * factor));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    /** Decodes stored PNG bytes back into an AWT image for writing to the clipboard. */
    public static BufferedImage decode(byte[] png) {
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode stored PNG", e);
        }
    }
}

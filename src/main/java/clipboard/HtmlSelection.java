package clipboard;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Provides both a plain-text and an HTML flavor so that pasting into rich-text
 * aware applications preserves formatting, while simple targets still get text.
 */
final class HtmlSelection implements Transferable {

    private static final DataFlavor HTML_FLAVOR = htmlFlavor();

    private final String text;
    private final byte[] htmlBytes;

    HtmlSelection(String text, String html) {
        this.text = text == null ? "" : text;
        this.htmlBytes = (html == null ? "" : html).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[]{DataFlavor.stringFlavor, HTML_FLAVOR};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return DataFlavor.stringFlavor.equals(flavor) || HTML_FLAVOR.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (DataFlavor.stringFlavor.equals(flavor)) {
            return text;
        }
        if (HTML_FLAVOR.equals(flavor)) {
            return htmlBytes;
        }
        throw new UnsupportedFlavorException(flavor);
    }

    private static DataFlavor htmlFlavor() {
        try {
            // Match the standard HTML clipboard flavor used by AWT readers.
            return new DataFlavor("text/html;class=[B;charset=" + StandardCharsets.UTF_8 + ";");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}

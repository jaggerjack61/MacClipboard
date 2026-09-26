package clipboard;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * Provides both a plain-text and an HTML flavor so that pasting into rich-text
 * aware applications preserves formatting, while simple targets still get text.
 */
final class HtmlSelection implements Transferable {

    static final DataFlavor HTML_FLAVOR = DataFlavor.selectionHtmlFlavor;

    private final String text;
    private final String html;

    HtmlSelection(String text, String html) {
        this.text = text == null ? "" : text;
        this.html = html == null ? "" : html;
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
            return html;
        }
        throw new UnsupportedFlavorException(flavor);
    }

}

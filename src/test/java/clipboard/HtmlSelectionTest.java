package clipboard;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HtmlSelectionTest {
    @Test
    void restoredRichTextCanBeReadBackWithoutLosingFormatting() throws Exception {
        var selection = new HtmlSelection("café", "<b>café</b>");
        assertEquals("café", selection.getTransferData(DataFlavor.stringFlavor));
        assertEquals("<b>café</b>", AwtClipboardGateway.readHtml(selection));
        for (DataFlavor flavor : selection.getTransferDataFlavors()) {
            assertTrue(flavor.getRepresentationClass().isInstance(selection.getTransferData(flavor)));
        }
        assertThrows(UnsupportedFlavorException.class, () -> selection.getTransferData(DataFlavor.imageFlavor));
    }

    @Test
    void readsNativeHtmlStreamsUsingTheirDeclaredCharsetAndClosesThem() throws Exception {
        DataFlavor flavor = new DataFlavor("text/html;class=java.io.InputStream;charset=UTF-16LE");
        boolean[] closed = {false};
        var stream = new ByteArrayInputStream("<i>café 🙂</i>".getBytes(StandardCharsets.UTF_16LE)) {
            @Override public void close() { closed[0] = true; }
        };
        Transferable nativeHtml = new Transferable() {
            public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{flavor}; }
            public boolean isDataFlavorSupported(DataFlavor f) { return flavor.equals(f); }
            public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                if (!isDataFlavorSupported(f)) throw new UnsupportedFlavorException(f);
                return stream;
            }
        };
        assertEquals("<i>café 🙂</i>", AwtClipboardGateway.readHtml(nativeHtml));
        assertTrue(closed[0]);
    }
}

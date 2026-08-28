package clipboard;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Exposes PNG bytes as an image flavor for writing to the system clipboard.
 */
final class ImageSelection implements Transferable {

    private final byte[] png;

    ImageSelection(byte[] png) {
        this.png = png;
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[]{DataFlavor.imageFlavor};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return DataFlavor.imageFlavor.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
        if (!isDataFlavorSupported(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        BufferedImage image = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) {
            throw new IOException("Could not decode PNG for clipboard write");
        }
        return image;
    }
}

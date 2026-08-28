package clipboard;

import model.ClipboardContentType;

/**
 * Platform-independent snapshot of clipboard content, decoupled from AWT/JavaFX
 * so clipboard history logic can be tested without a real system clipboard.
 */
public record ClipboardSnapshot(
        ClipboardContentType contentType,
        String text,
        String html,
        byte[] image,       // PNG bytes when the clipboard holds an image
        byte[] thumbnail,   // small PNG preview of {@code image}
        int imageWidth,
        int imageHeight
) {

    public boolean isEmpty() {
        return contentType == ClipboardContentType.UNKNOWN;
    }

    public static ClipboardSnapshot text(String text, String html) {
        return new ClipboardSnapshot(
                html == null ? ClipboardContentType.TEXT : ClipboardContentType.RICH_TEXT,
                text, html, null, null, 0, 0);
    }

    public static ClipboardSnapshot image(byte[] png, byte[] thumbnail, int w, int h) {
        return new ClipboardSnapshot(ClipboardContentType.IMAGE, null, null, png, thumbnail, w, h);
    }
}

package model;

/** List metadata only; full text, HTML and image payloads are fetched on selection. */
public record ClipboardPreview(long id, ClipboardContentType contentType, String preview,
                               byte[] thumbnail, long timestamp, boolean pinned, Long collectionId) {

    public static ClipboardPreview from(ClipboardItem item) {
        return new ClipboardPreview(item.id(), item.contentType(), item.preview(),
                item.thumbnail(), item.timestamp(), item.pinned(), item.collectionId());
    }
}

package model;

/**
 * One entry in the clipboard history.
 *
 * <p>Text based entries populate {@code textContent} (and optionally {@code htmlContent});
 * image entries populate {@code image} (PNG bytes) and a small pre-scaled {@code thumbnail}
 * so the UI never has to decode full size images eagerly.</p>
 */
public record ClipboardItem(
        long id,
        ClipboardContentType contentType,
        String hash,
        String preview,
        String textContent,
        String htmlContent,
        byte[] image,
        byte[] thumbnail,
        long timestamp,
        boolean pinned
) {

    public static Builder builder() {
        return new Builder();
    }

    public boolean hasImage() {
        return image != null && image.length > 0;
    }

    public ClipboardItem withPinned(boolean newPinned) {
        return toBuilder().pinned(newPinned).build();
    }

    public ClipboardItem withTimestamp(long newTimestamp) {
        return toBuilder().timestamp(newTimestamp).build();
    }

    public ClipboardItem withId(long newId) {
        return toBuilder().id(newId).build();
    }

    public Builder toBuilder() {
        return new Builder()
                .id(id)
                .contentType(contentType)
                .hash(hash)
                .preview(preview)
                .textContent(textContent)
                .htmlContent(htmlContent)
                .image(image)
                .thumbnail(thumbnail)
                .timestamp(timestamp)
                .pinned(pinned);
    }

    /** Content-free representation, safe for logging. Never includes clipboard data. */
    @Override
    public String toString() {
        int size = hasImage() ? image.length : (textContent == null ? 0 : textContent.length());
        String hashPrefix = hash == null ? "null" : hash.substring(0, Math.min(8, hash.length()));
        return "ClipboardItem{id=" + id + ", type=" + contentType + ", size=" + size
                + ", timestamp=" + timestamp + ", pinned=" + pinned + ", hash=" + hashPrefix + '}';
    }

    public static final class Builder {
        private long id = -1;
        private ClipboardContentType contentType = ClipboardContentType.UNKNOWN;
        private String hash;
        private String preview;
        private String textContent;
        private String htmlContent;
        private byte[] image;
        private byte[] thumbnail;
        private long timestamp = System.currentTimeMillis();
        private boolean pinned;

        public Builder id(long id) {
            this.id = id;
            return this;
        }

        public Builder contentType(ClipboardContentType contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder hash(String hash) {
            this.hash = hash;
            return this;
        }

        public Builder preview(String preview) {
            this.preview = preview;
            return this;
        }

        public Builder textContent(String textContent) {
            this.textContent = textContent;
            return this;
        }

        public Builder htmlContent(String htmlContent) {
            this.htmlContent = htmlContent;
            return this;
        }

        public Builder image(byte[] image) {
            this.image = image;
            return this;
        }

        public Builder thumbnail(byte[] thumbnail) {
            this.thumbnail = thumbnail;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder pinned(boolean pinned) {
            this.pinned = pinned;
            return this;
        }

        public ClipboardItem build() {
            return new ClipboardItem(id, contentType, hash, preview, textContent, htmlContent,
                    image, thumbnail, timestamp, pinned);
        }
    }
}

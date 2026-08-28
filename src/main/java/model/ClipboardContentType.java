package model;

/**
 * The kind of content a {@link ClipboardItem} holds.
 */
public enum ClipboardContentType {
    TEXT("Text"),
    RICH_TEXT("Rich text"),
    IMAGE("Image"),
    UNKNOWN("Unknown");

    private final String label;

    ClipboardContentType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

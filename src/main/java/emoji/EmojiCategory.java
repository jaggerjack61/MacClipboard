package emoji;

import java.util.Arrays;
import java.util.List;

/**
 * Display categories shown as tabs in the Emoji picker. Ordered: Recently Used first,
 * then the standard Unicode groups.
 */
public enum EmojiCategory {
    RECENTLY_USED("Recently Used", "\uD83D\uDD53"),
    SMILEYS("Smileys & Emotion", "\uD83D\uDE00"),
    PEOPLE("People & Body", "\uD83D\uDC4B"),
    ANIMALS("Animals & Nature", "\uD83D\uDC3E"),
    FOOD("Food & Drink", "\uD83C\uDF54"),
    ACTIVITIES("Activities", "\u26BD"),
    TRAVEL("Travel & Places", "\uD83D\uDE97"),
    OBJECTS("Objects", "\uD83D\uDCA1"),
    SYMBOLS("Symbols", "\u2764\uFE0F"),
    FLAGS("Flags", "\uD83D\uDEA9");

    private final String label;
    private final String icon;

    EmojiCategory(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public String label() {
        return label;
    }

    public String icon() {
        return icon;
    }

    /** The static display order (Recently Used first). */
    public static List<EmojiCategory> ordered() {
        return Arrays.asList(values());
    }
}

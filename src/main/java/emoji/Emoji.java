package emoji;

import java.util.List;

/**
 * A single Unicode emoji with its display character, CLDR name, category label and
 * search keywords. Immutable record.
 */
public record Emoji(
        String character,
        String name,
        String category,
        List<String> keywords,
        boolean hasSkinToneVariants
) {

    public Emoji {
        keywords = List.copyOf(keywords);
    }

    /** Lower-cased searchable haystack built from name + keywords. */
    public String searchable() {
        return (name + " " + String.join(" ", keywords)).toLowerCase();
    }
}

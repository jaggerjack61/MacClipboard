package emoji;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the offline Unicode emoji dataset bundled as a TSV resource. The dataset is
 * generated at build time from Unicode + gemoji sources (see scripts/build_emoji_dataset.py)
 * so no network access is ever required at runtime.
 */
public final class EmojiRepository {

    public static final String RESOURCE = "/emoji/emojis.tsv";
    /** Category label used for entries that are searchable but not shown in the grid. */
    public static final String HIDDEN_CATEGORY = "hidden";

    private final List<Emoji> allEmojis;
    private final Map<EmojiCategory, List<Emoji>> byCategory;

    public EmojiRepository() {
        this(EmojiRepository.class.getResourceAsStream(RESOURCE));
    }

    public EmojiRepository(InputStream stream) {
        if (stream == null) {
            throw new IllegalStateException("Emoji dataset resource missing: " + RESOURCE
                    + " — run scripts/build_emoji_dataset.py to generate it.");
        }
        List<Emoji> loaded = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Emoji e = parseLine(line);
                if (e != null) {
                    loaded.add(e);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read emoji dataset", e);
        }
        this.allEmojis = List.copyOf(loaded);
        this.byCategory = groupByCategory(this.allEmojis);
    }

    private static Emoji parseLine(String line) {
        if (line.isBlank()) {
            return null;
        }
        String[] parts = line.split("\t", -1);
        if (parts.length < 5) {
            return null;
        }
        String character = parts[0];
        String name = parts[1];
        String category = parts[2];
        List<String> keywords = parts[4].isBlank()
                ? List.of()
                : Arrays.stream(parts[4].split("\\|")).filter(s -> !s.isBlank()).toList();
        boolean tones = parts.length > 5 && "1".equals(parts[5]);
        return new Emoji(character, name, category, keywords, tones);
    }

    private static Map<EmojiCategory, List<Emoji>> groupByCategory(List<Emoji> emojis) {
        Map<EmojiCategory, List<Emoji>> map = new LinkedHashMap<>();
        for (Emoji emoji : emojis) {
            if (HIDDEN_CATEGORY.equals(emoji.category())) {
                continue;
            }
            EmojiCategory cat = matchCategory(emoji.category());
            if (cat != null) {
                map.computeIfAbsent(cat, k -> new ArrayList<>()).add(emoji);
            }
        }
        return map;
    }

    /** Maps a dataset category label (or subgroup label) to an {@link EmojiCategory}. */
    public static EmojiCategory matchCategory(String datasetCategory) {
        if (datasetCategory == null) {
            return null;
        }
        String lower = datasetCategory.toLowerCase();
        for (EmojiCategory c : EmojiCategory.ordered()) {
            if (c == EmojiCategory.RECENTLY_USED) {
                continue;
            }
            if (c.label().equalsIgnoreCase(datasetCategory) || lower.startsWith(c.name().toLowerCase())) {
                return c;
            }
        }
        return null;
    }

    public List<Emoji> all() {
        return allEmojis;
    }

    public List<Emoji> byCategory(EmojiCategory category) {
        return byCategory.getOrDefault(category, List.of());
    }
}

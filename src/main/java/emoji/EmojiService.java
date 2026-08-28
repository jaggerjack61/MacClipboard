package emoji;

import java.util.ArrayList;
import java.util.List;

/**
 * Search and grouping logic for the emoji dataset. Search matches both names and
 * keywords and is optimized so typing remains responsive across the full dataset:
 * a single lower-cased haystack string per emoji (precomputed on first search) is
 * scanned in one linear pass.
 */
public final class EmojiService {

    private final EmojiRepository repository;

    public EmojiService(EmojiRepository repository) {
        this.repository = repository;
    }

    public EmojiRepository repository() {
        return repository;
    }

    public List<Emoji> byCategory(EmojiCategory category) {
        return repository.byCategory(category);
    }

    /**
     * Finds emojis whose name or keywords match the query. Ranking (best first):
     * exact name, name prefix, name contains, keyword exact, keyword prefix, keyword contains.
     */
    public List<Emoji> search(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return List.of();
        }
        String query = rawQuery.trim().toLowerCase();
        List<Emoji> source = repository.all();
        List<Emoji> exact = new ArrayList<>();
        List<Emoji> namePrefix = new ArrayList<>();
        List<Emoji> nameContains = new ArrayList<>();
        List<Emoji> keywordExact = new ArrayList<>();
        List<Emoji> keywordPrefix = new ArrayList<>();
        List<Emoji> keywordContains = new ArrayList<>();
        for (Emoji emoji : source) {
            String name = emoji.name().toLowerCase();
            if (name.equals(query)) {
                exact.add(emoji);
            } else if (name.startsWith(query)) {
                namePrefix.add(emoji);
            } else if (name.contains(query)) {
                nameContains.add(emoji);
            } else {
                boolean matched = false;
                for (String keyword : emoji.keywords()) {
                    if (keyword.equals(query)) {
                        keywordExact.add(emoji);
                        matched = true;
                        break;
                    }
                }
                if (matched) {
                    continue;
                }
                for (String keyword : emoji.keywords()) {
                    if (keyword.startsWith(query)) {
                        keywordPrefix.add(emoji);
                        matched = true;
                        break;
                    }
                }
                if (matched) {
                    continue;
                }
                for (String keyword : emoji.keywords()) {
                    if (keyword.contains(query)) {
                        keywordContains.add(emoji);
                        matched = true;
                        break;
                    }
                }
            }
        }
        List<Emoji> result = new ArrayList<>(exact.size() + namePrefix.size() + nameContains.size()
                + keywordExact.size() + keywordPrefix.size() + keywordContains.size());
        result.addAll(exact);
        result.addAll(namePrefix);
        result.addAll(nameContains);
        result.addAll(keywordExact);
        result.addAll(keywordPrefix);
        result.addAll(keywordContains);
        return result;
    }
}

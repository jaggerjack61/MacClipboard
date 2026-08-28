package emoji;

import config.ApplicationSettings;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tracks recently used emojis and exposes them ordered by a ranking that combines
 * usage frequency with recency. When the configured maximum is exceeded, the lowest
 * ranked entries are dropped. Feature can be disabled via settings.
 */
public final class RecentEmojiService {

    /** Half-life (ms) used to decay older usage in the ranking score. */
    private static final long RECENCY_HALF_LIFE = 3L * 24 * 60 * 60 * 1000;

    private final RecentEmojiRepository repository;
    private final ApplicationSettings settings;

    public RecentEmojiService(RecentEmojiRepository repository, ApplicationSettings settings) {
        this.repository = repository;
        this.settings = settings;
    }

    public void record(String character) {
        if (!settings.rememberRecentEmojis() || character == null || character.isEmpty()) {
            return;
        }
        repository.recordUse(character, System.currentTimeMillis());
        trimIfNeeded();
    }

    public List<Emoji> recent(List<Emoji> catalog, int limit) {
        if (!settings.rememberRecentEmojis()) {
            return List.of();
        }
        Map<String, RecentEmojiEntry> byChar = repository.entries().stream()
                .collect(Collectors.toMap(RecentEmojiEntry::character, Function.identity(), (a, b) -> a));
        long now = System.currentTimeMillis();
        return catalog.stream()
                .filter(e -> byChar.containsKey(e.character()))
                .sorted(Comparator.comparingDouble((Emoji e) -> -score(byChar.get(e.character()), now)))
                .limit(limit)
                .toList();
    }

    public List<String> recentCharacters(int limit) {
        if (!settings.rememberRecentEmojis()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        return repository.entries().stream()
                .sorted(Comparator.comparingDouble((RecentEmojiEntry e) -> -score(e, now)))
                .limit(limit)
                .map(RecentEmojiEntry::character)
                .toList();
    }

    private void trimIfNeeded() {
        int max = settings.maxRecentEmojis();
        List<RecentEmojiEntry> entries = repository.entries();
        if (entries.size() <= max) {
            return;
        }
        long now = System.currentTimeMillis();
        entries.stream()
                .sorted(Comparator.comparingDouble((RecentEmojiEntry e) -> -score(e, now)))
                .skip(max)
                .map(RecentEmojiEntry::character)
                .forEach(c -> repository.remove(List.of(c)));
    }

    /**
     * Frequency + recency score. Uses exponential decay on recency so a single recent
     * use ranks above an old frequent use, and repeated use still accumulates.
     */
    static double score(RecentEmojiEntry entry, long now) {
        double recency = Math.pow(0.5, (now - entry.lastUsed()) / (double) RECENCY_HALF_LIFE);
        return entry.count() * (0.5 + recency);
    }

    /** Pure helper for tests: rank a set of entries best-first. */
    public static List<RecentEmojiEntry> rank(List<RecentEmojiEntry> entries, long now) {
        return entries.stream()
                .sorted(Comparator.comparingDouble((RecentEmojiEntry e) -> -score(e, now)))
                .toList();
    }
}

package emoji;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import config.ApplicationSettings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repository.SettingsStore;

class RecentEmojiServiceTest {

    private ApplicationSettings settings;
    private InMemoryRecentEmojiRepository repository;
    private RecentEmojiService service;

    @BeforeEach
    void setUp() {
        settings = new ApplicationSettings(new MapStore());
        repository = new InMemoryRecentEmojiRepository();
        service = new RecentEmojiService(repository, settings);
    }

    @Test
    void recordsUsage() {
        service.record("😀");
        service.record("😀");
        service.record("🔥");
        Map<String, Integer> counts = counts();
        assertEquals(2, counts.get("😀"));
        assertEquals(1, counts.get("🔥"));
    }

    @Test
    void respectsRememberToggle() {
        settings.setRememberRecentEmojis(false);
        service.record("😀");
        assertTrue(service.recentCharacters(10).isEmpty());
    }

    @Test
    void recencyBreaksTiesWhenFrequencyEqual() {
        long now = System.currentTimeMillis();
        List<RecentEmojiEntry> entries = List.of(
                new RecentEmojiEntry("old", 2, now - 40L * 24 * 3600 * 1000),
                new RecentEmojiEntry("fresh", 2, now));
        List<RecentEmojiEntry> ranked = RecentEmojiService.rank(entries, now);
        // Same frequency: the more recent one ranks higher.
        assertEquals("fresh", ranked.get(0).character());
    }

    @Test
    void highFrequencyStillRanksHighWhenOld() {
        long now = System.currentTimeMillis();
        List<RecentEmojiEntry> entries = List.of(
                new RecentEmojiEntry("workhorse", 50, now - 60L * 24 * 3600 * 1000),
                new RecentEmojiEntry("oneoff", 1, now));
        List<RecentEmojiEntry> ranked = RecentEmojiService.rank(entries, now);
        assertEquals("workhorse", ranked.get(0).character());
    }

    @Test
    void rankingFavorsRecentFrequentOverOldRare() {
        long now = System.currentTimeMillis();
        List<RecentEmojiEntry> entries = List.of(
                new RecentEmojiEntry("recent", 2, now),
                new RecentEmojiEntry("rare", 1, now));
        List<RecentEmojiEntry> ranked = RecentEmojiService.rank(entries, now);
        assertEquals("recent", ranked.get(0).character());
    }

    @Test
    void trimsToMaximum() {
        settings.setMaxRecentEmojis(3);
        for (int i = 0; i < 20; i++) {
            service.record("e" + i);
        }
        assertTrue(repository.entries().size() <= 3,
                "entries " + repository.entries().size());
    }

    @Test
    void mostFrequentlyUsedSurvivesTrim() {
        settings.setMaxRecentEmojis(2);
        // "hot" used a lot and recently; "cold" used once long ago.
        service.record("hot");
        service.record("hot");
        service.record("hot");
        service.record("cold");
        List<String> recent = service.recentCharacters(5);
        assertTrue(recent.contains("hot"));
    }

    @Test
    void persistsThroughSqliteReopen() {
        java.nio.file.Path tmp;
        try {
            tmp = java.nio.file.Files.createTempDirectory("emojirecent");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        java.nio.file.Path db = tmp.resolve("r.db");
        try (repository.Database d = new repository.Database(db)) {
            RecentEmojiRepository r = new SqliteRecentEmojiRepository(d.connection());
            r.recordUse("🚀", System.currentTimeMillis());
            r.recordUse("🚀", System.currentTimeMillis());
            assertEquals(2, r.entries().get(0).count());
        }
        try (repository.Database d = new repository.Database(db)) {
            RecentEmojiRepository r = new SqliteRecentEmojiRepository(d.connection());
            assertEquals(1, r.entries().size());
            assertEquals(2, r.entries().get(0).count());
            r.remove(List.of("🚀"));
            assertTrue(r.entries().isEmpty());
        }
    }

    private Map<String, Integer> counts() {
        return repository.entries().stream()
                .collect(java.util.stream.Collectors.toMap(RecentEmojiEntry::character, RecentEmojiEntry::count));
    }

    private static final class MapStore implements SettingsStore {
        private final Map<String, String> data = new java.util.HashMap<>();

        @Override
        public Map<String, String> loadAll() {
            return Map.copyOf(data);
        }

        @Override
        public void save(String key, String value) {
            data.put(key, value);
        }

        @Override
        public void delete(String key) {
            data.remove(key);
        }
    }
}

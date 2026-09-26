package clipboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import config.ApplicationSettings;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import model.ClipboardContentType;
import model.ClipboardItem;
import model.ClipboardPreview;
import model.HistoryFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repository.InMemoryClipboardRepository;
import repository.SettingsStore;

class ClipboardServiceTest {

    private ClipboardService service;
    private InMemoryClipboardRepository repository;
    private ApplicationSettings settings;

    @BeforeEach
    void setUp() {
        repository = new InMemoryClipboardRepository();
        settings = new ApplicationSettings(new MapSettingsStore());
        service = new ClipboardService(repository, settings);
    }

    @Test
    void ingestsTextSnapshot() {
        Optional<ClipboardItem> item = service.ingest(ClipboardSnapshot.text("hello world", null));
        assertTrue(item.isPresent());
        assertEquals(ClipboardContentType.TEXT, item.get().contentType());
        assertEquals("hello world", item.get().textContent());
    }

    @Test
    void detectsRichText() {
        Optional<ClipboardItem> item = service.ingest(ClipboardSnapshot.text("bold", "<b>bold</b>"));
        assertTrue(item.isPresent());
        assertEquals(ClipboardContentType.RICH_TEXT, item.get().contentType());
    }

    @Test
    void ignoresDuplicateOfLatest() {
        service.ingest(ClipboardSnapshot.text("same", null));
        Optional<ClipboardItem> second = service.ingest(ClipboardSnapshot.text("same", null));
        assertTrue(second.isEmpty());
        assertEquals(1, repository.count());
    }

    @Test
    void ignoresEmptySnapshots() {
        assertTrue(service.ingest(null).isEmpty());
        assertTrue(service.ingest(new ClipboardSnapshot(ClipboardContentType.UNKNOWN,
                null, null, null, null, 0, 0)).isEmpty());
        assertEquals(0, repository.count());
    }

    @Test
    void recopyOfOlderItemMovesItToTopWithoutDuplicate() {
        service.ingest(ClipboardSnapshot.text("first", null));
        ClipboardItem second = service.ingest(ClipboardSnapshot.text("second", null)).orElseThrow();
        service.ingest(ClipboardSnapshot.text("third", null));

        // Copying "first" again should move it to top, not create a duplicate.
        ClipboardSnapshot again = ClipboardSnapshot.text("first", null);
        Optional<ClipboardItem> recorded = service.ingest(again);

        List<ClipboardItem> history = service.history("");
        assertEquals(3, history.size());
        assertEquals("first", history.get(0).textContent());
        assertEquals(1L, history.stream().filter(i -> i.textContent().equals("first")).count());
        assertEquals(second.id(), repository.findByHash(ClipboardHasher.hash(
                ClipboardSnapshot.text("second", null))).orElseThrow().id());
    }

    @Test
    void enforcesHistoryLimitDeletingOldestUnpinned() {
        settings.setMaxHistory(10);
        for (int i = 0; i < 24; i++) {
            service.ingest(ClipboardSnapshot.text("item " + i, null));
        }
        assertEquals(10, repository.count());
        List<ClipboardItem> history = service.history("");
        // Newest-first, and oldest entries were dropped.
        assertEquals("item 23", history.get(0).textContent());
        assertEquals("item 14", history.get(9).textContent());
    }

    @Test
    void pinnedItemsSurviveHistoryLimit() {
        settings.setMaxHistory(10);
        ClipboardItem important = service.ingest(ClipboardSnapshot.text("keep me", null)).orElseThrow();
        assertTrue(service.togglePin(important.id()));

        for (int i = 0; i < 20; i++) {
            service.ingest(ClipboardSnapshot.text("noise " + i, null));
        }

        long pinnedCount = repository.findRecent("", 100).stream().filter(ClipboardItem::pinned).count();
        assertEquals(1, pinnedCount);
        assertTrue(repository.findRecent("", 100).stream()
                .anyMatch(i -> i.textContent().equals("keep me")));
    }

    @Test
    void deletesIndividualItems() {
        ClipboardItem item = service.ingest(ClipboardSnapshot.text("doomed", null)).orElseThrow();
        assertTrue(service.delete(item.id()));
        assertTrue(service.history("").stream().noneMatch(i -> i.id() == item.id()));
    }

    @Test
    void clearsUnpinnedOnly() {
        ClipboardItem pinned = service.ingest(ClipboardSnapshot.text("pinned", null)).orElseThrow();
        service.togglePin(pinned.id());
        service.ingest(ClipboardSnapshot.text("temp", null));

        int removed = service.clearUnpinned();
        assertEquals(1, removed);
        assertEquals(1, service.count());
        assertTrue(service.history("").get(0).pinned());
    }

    @Test
    void searchesTextEntriesCaseInsensitively() {
        service.ingest(ClipboardSnapshot.text("SELECT * FROM users", null));
        service.ingest(ClipboardSnapshot.text("hello world", null));

        List<ClipboardItem> found = service.history("select");
        assertEquals(1, found.size());
        assertEquals("SELECT * FROM users", found.get(0).textContent());
    }

    @Test
    void retentionDeletesOldUnpinnedItems() {
        settings.setRetentionDays(1);
        ClipboardItem old = service.ingest(ClipboardSnapshot.text("ancient", null)).orElseThrow();
        // Move the item 3 days into the past, then run the retention sweep.
        repository.touch(old.id(), System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000);

        int removed = service.applyRetention();
        assertEquals(1, removed);
        assertFalse(service.history("").stream().anyMatch(i -> i.textContent().equals("ancient")));
    }

    @Test
    void retentionKeepsPinnedOldItems() {
        settings.setRetentionDays(1);
        ClipboardItem old = service.ingest(ClipboardSnapshot.text("ancient pinned", null)).orElseThrow();
        service.togglePin(old.id());
        repository.touch(old.id(), System.currentTimeMillis() - 5L * 24 * 60 * 60 * 1000);

        int removed = service.applyRetention();
        assertEquals(0, removed);
    }

    @Test
    void previewTruncatesLongText() {
        String longText = "x".repeat(1000);
        ClipboardItem item = service.ingest(ClipboardSnapshot.text(longText, null)).orElseThrow();
        assertTrue(item.preview().length() <= 221);
        assertTrue(item.preview().endsWith("…"));
    }

    @Test
    void imageItemsRecordDimensionsInPreview() {
        ClipboardItem item = service.ingest(
                ClipboardSnapshot.image(new byte[]{1, 2, 3}, new byte[]{4}, 800, 600)).orElseThrow();
        assertEquals(ClipboardContentType.IMAGE, item.contentType());
        assertEquals("Image 800×600", item.preview());
    }

    @Test
    void unchangedClipboardStaysDeletedButCanBeCopiedAgainAfterAChange() {
        var snapshot = ClipboardSnapshot.text("deleted", null);
        var item = service.ingest(snapshot).orElseThrow();
        service.delete(item.id());
        assertTrue(service.ingest(snapshot).isEmpty());
        assertEquals(0, service.count());
        service.ingest(ClipboardSnapshot.text("another copy", null));
        assertTrue(service.ingest(snapshot).isPresent());
        assertEquals(2, service.count());
    }

    @Test
    void unchangedClipboardStaysCleared() {
        var snapshot = ClipboardSnapshot.text("clear me", null);
        service.ingest(snapshot);
        service.clearUnpinned();
        assertTrue(service.ingest(snapshot).isEmpty());
        assertEquals(0, service.count());
        var next = ClipboardSnapshot.text("clear this too", null);
        service.ingest(next);
        service.clearAll();
        assertTrue(service.ingest(next).isEmpty());
        assertEquals(0, service.count());
    }

    @Test
    void clearingImmediatelyAfterRestartDoesNotRecaptureThePreviousLatestItem() {
        var snapshot = ClipboardSnapshot.text("previous session", null);
        service.ingest(snapshot);
        var restarted = new ClipboardService(repository, settings);
        restarted.clearAll();
        assertTrue(restarted.ingest(snapshot).isEmpty());
        assertEquals(0, restarted.count());
    }

    @Test
    void richTextVariantsAreStoredAndRestoredIndependently() {
        var bold = ClipboardSnapshot.text("hello", "<b>hello</b>");
        var italic = ClipboardSnapshot.text("hello", "<i>hello</i>");
        var first = service.ingest(bold).orElseThrow();
        service.ingest(italic).orElseThrow();
        assertEquals(2, service.count());
        assertEquals(italic, ClipboardService.toSnapshot(service.history("").getFirst()));
        assertEquals(first.id(), service.ingest(bold).orElseThrow().id());
        assertEquals(2, service.count());
    }

    @Test
    void pinnedItemsComeFirstWithoutBreakingDedupe() {
        var old = service.ingest(ClipboardSnapshot.text("pinned old", null)).orElseThrow();
        service.togglePin(old.id());
        var latest = ClipboardSnapshot.text("unpinned latest", null);
        service.ingest(latest);
        assertEquals(old.id(), service.history("").getFirst().id());
        assertTrue(service.ingest(latest).isEmpty());
        var recopy = service.ingest(ClipboardSnapshot.text("pinned old", null)).orElseThrow();
        assertEquals(old.id(), recopy.id());
        assertTrue(service.ingest(ClipboardSnapshot.text("pinned old", null)).isEmpty());
    }

    /** Simple in-memory SettingsStore (separate from repositories under test). */
    @Test
    void collectionNamesMustBeNonBlankAndUniqueIgnoringCase() {
        var work = service.createCollection("  Work  ");
        assertEquals("Work", work.name(), "names are trimmed");
        assertTrue(service.collectionNameProblem("   ", null).isPresent());
        assertTrue(service.collectionNameProblem("WORK", null).isPresent());
        assertFalse(service.collectionNameProblem("WORK", work.id()).isPresent(), "renaming to itself is fine");
        assertFalse(service.collectionNameProblem("Recipes \uD83C\uDF5D / 2026!", null).isPresent());
        assertThrows(IllegalArgumentException.class, () -> service.createCollection("work"));
        var other = service.createCollection("Other");
        assertThrows(IllegalArgumentException.class, () -> service.renameCollection(other.id(), "Work"));
        assertTrue(service.renameCollection(other.id(), "Personal"));
    }

    @Test
    void itemsInCollectionsSurviveLimitsClearingAndRecopy() {
        settings.setMaxHistory(10);
        var recipes = service.createCollection("Recipes");
        ClipboardItem kept = service.ingest(ClipboardSnapshot.text("pasta sauce", null)).orElseThrow();
        assertTrue(service.moveToCollection(kept.id(), recipes.id()));
        for (int i = 0; i < 30; i++) {
            service.ingest(ClipboardSnapshot.text("filler " + i, null));
        }
        service.clearUnpinned();
        assertEquals(List.of(kept.id()), service.previews("", HistoryFilter.collection(recipes.id()))
                .stream().map(ClipboardPreview::id).toList());
        // Copying it again moves it to the top without leaving the collection.
        service.ingest(ClipboardSnapshot.text("other", null));
        service.ingest(ClipboardSnapshot.text("pasta sauce", null));
        assertEquals(recipes.id(), service.findById(kept.id()).orElseThrow().collectionId());
        // Unpinning removes it from the collection.
        service.togglePin(kept.id());
        assertTrue(service.previews("", HistoryFilter.collection(recipes.id())).isEmpty());
    }

    private static final class MapSettingsStore implements SettingsStore {
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

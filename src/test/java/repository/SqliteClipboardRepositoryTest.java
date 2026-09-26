package repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import model.ClipboardCollection;
import model.ClipboardContentType;
import model.HistoryFilter;
import model.ClipboardItem;
import clipboard.ClipboardHasher;
import clipboard.ClipboardSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteClipboardRepositoryTest {

    @TempDir
    Path dir;

    @Test
    void persistsAcrossReopen() {
        Path file = dir.resolve("clip.db");
        ClipboardItem stored;
        try (Database db = new Database(file)) {
            SqliteClipboardRepository repo = new SqliteClipboardRepository(db.connection());
            stored = repo.insert(item("hello persisted", "h1", false));
            assertTrue(stored.id() > 0);
        }
        try (Database db = new Database(file)) {
            SqliteClipboardRepository repo = new SqliteClipboardRepository(db.connection());
            Optional<ClipboardItem> found = repo.findByHash("h1");
            assertTrue(found.isPresent());
            assertEquals("hello persisted", found.get().textContent());
            assertEquals(stored.id(), found.get().id());
        }
    }

    @Test
    void storesImageAndThumbnailBlobs() {
        try (Database db = new Database(dir.resolve("img.db"))) {
            SqliteClipboardRepository repo = new SqliteClipboardRepository(db.connection());
            ClipboardItem img = ClipboardItem.builder()
                    .contentType(ClipboardContentType.IMAGE)
                    .hash("h-img")
                    .preview("Image 2x2")
                    .image(new byte[]{1, 2, 3, 4, 5})
                    .thumbnail(new byte[]{9, 8})
                    .timestamp(System.currentTimeMillis())
                    .build();
            ClipboardItem stored = repo.insert(img);
            ClipboardItem loaded = repo.findById(stored.id()).orElseThrow();
            assertTrue(loaded.hasImage());
            assertEquals(5, loaded.image().length);
            assertEquals(2, loaded.thumbnail().length);
        }
    }

    @Test
    void searchesTextCaseInsensitivelyWithSpecialChars() {
        try (Database db = new Database(dir.resolve("search.db"))) {
            SqliteClipboardRepository repo = new SqliteClipboardRepository(db.connection());
            repo.insert(item("SELECT * FROM users", "a", false));
            repo.insert(item("100% pure 100% text", "b", false));
            repo.insert(item("hello", "c", false));

            assertEquals(1, repo.findRecent("select", 50).size());
            // % must be treated literally, not as a wildcard
            assertEquals(1, repo.findRecent("100%", 50).size());
            assertEquals(3, repo.findRecent("", 50).size());
        }
    }

    @Test
    void pinAndDeleteAndLimits() {
        try (Database db = new Database(dir.resolve("limit.db"))) {
            SqliteClipboardRepository repo = new SqliteClipboardRepository(db.connection());
            ClipboardItem pinned = repo.insert(item("keep", "p", true));
            for (int i = 0; i < 30; i++) {
                repo.insert(item("noise " + i, "n" + i, false));
            }
            assertTrue(pinned.pinned());
            int deleted = repo.enforceLimit(10);
            assertEquals(20, deleted);
            List<ClipboardItem> all = repo.findRecent("", 100);
            assertTrue(all.stream().anyMatch(i -> i.textContent().equals("keep")));
            assertEquals(11, all.size());
            // pinned first in ordering
            assertTrue(all.get(0).pinned());

            assertTrue(repo.setPinned(all.get(0).id(), false));
            // nothing older than epoch
            assertEquals(0, repo.deleteOlderThan(1));
            // everything non-pinned older than max -> all 11
            assertEquals(11, repo.deleteOlderThan(Long.MAX_VALUE));
            assertEquals(0, repo.count());
        }
    }

    @Test
    void clearUnpinnedKeepsPinned() {
        try (Database db = new Database(dir.resolve("clear.db"))) {
            SqliteClipboardRepository repo = new SqliteClipboardRepository(db.connection());
            repo.insert(item("pin me", "1", true));
            repo.insert(item("drop me", "2", false));
            assertEquals(1, repo.deleteUnpinned());
            assertEquals(1, repo.count());
            assertFalse(repo.findRecent("drop", 10).stream().findAny().isPresent());
        }
    }

    @Test
    void previewsPreserveSearchOrderingAndThumbnailsWhileFullPayloadRemainsAvailable() {
        try (Database db = new Database(dir.resolve("previews.db"))) {
            var repo = new SqliteClipboardRepository(db.connection());
            var image = repo.insert(ClipboardItem.builder().contentType(ClipboardContentType.IMAGE)
                    .hash("image").preview("Image 1920x1080").image(new byte[4 * 1024 * 1024])
                    .thumbnail(new byte[]{1, 2, 3}).timestamp(1).pinned(true).build());
            repo.insert(item("100% searchable", "text", false));
            var previews = repo.findPreviews("", 10);
            assertEquals(2, previews.size());
            assertEquals(image.id(), previews.getFirst().id());
            assertArrayEquals(image.thumbnail(), previews.getFirst().thumbnail());
            assertEquals(image.preview(), previews.getFirst().preview());
            assertEquals(1, repo.findPreviews("100%", 10).size());
            assertEquals(1, repo.findPreviews("", 1).size());
            assertArrayEquals(image.image(), repo.findById(previews.getFirst().id()).orElseThrow().image());
        }
    }

    @Test
    void upgradeRehashesExistingRichTextWithoutLosingIdsPinsOrPayloads() throws Exception {
        Path file = dir.resolve("upgrade.db");
        var snapshot = ClipboardSnapshot.text("hello", "<b>hello</b>");
        long id;
        try (Database db = new Database(file)) {
            var repo = new SqliteClipboardRepository(db.connection());
            id = repo.insert(ClipboardItem.builder().contentType(ClipboardContentType.RICH_TEXT)
                    .textContent(snapshot.text()).htmlContent(snapshot.html()).hash("legacy-hash")
                    .pinned(true).timestamp(1234).build()).id();
            try (var statement = db.connection().createStatement()) {
                statement.execute("PRAGMA user_version = 1");
            }
        }
        for (int reopen = 0; reopen < 2; reopen++) {
            try (Database db = new Database(file)) {
                var repo = new SqliteClipboardRepository(db.connection());
                var upgraded = repo.findByHash(ClipboardHasher.hash(snapshot)).orElseThrow();
                assertEquals(id, upgraded.id());
                assertTrue(upgraded.pinned());
                assertEquals(1234, upgraded.timestamp());
                assertEquals(snapshot.html(), upgraded.htmlContent());
                assertTrue(repo.findByHash("legacy-hash").isEmpty());
                assertEquals(1, repo.count());
            }
        }
    }

    @Test
    void memoryAndSqliteAgreeOnPinOrderingAndChronologicalLatestHash() {
        try (Database db = new Database(dir.resolve("ordering.db"))) {
            for (ClipboardRepository repo : List.of(new InMemoryClipboardRepository(),
                    new SqliteClipboardRepository(db.connection()))) {
                var pinnedOld = repo.insert(item("pinned old", "old", true).withTimestamp(1));
                var unpinnedNew = repo.insert(item("unpinned new", "new", false).withTimestamp(3));
                var pinnedMiddle = repo.insert(item("pinned middle", "middle", true).withTimestamp(2));
                assertEquals(List.of(pinnedMiddle.id(), pinnedOld.id(), unpinnedNew.id()),
                        repo.findRecent("", 10).stream().map(ClipboardItem::id).toList());
                assertEquals("new", repo.latestHash().orElseThrow());
                repo.touch(pinnedOld.id(), 4);
                assertEquals("old", repo.latestHash().orElseThrow());
                assertEquals(pinnedOld.id(), repo.findPreviews("", 10).getFirst().id());
            }
        }
    }

    @Test
    void memoryAndSqliteAgreeOnCollections() {
        try (Database db = new Database(dir.resolve("collections.db"))) {
            for (ClipboardRepository repo : List.of(new InMemoryClipboardRepository(),
                    new SqliteClipboardRepository(db.connection()))) {
                var work = repo.createCollection("Work");
                var misc = repo.createCollection("Misc \u2728 & odds_ends");
                var a = repo.insert(item("alpha", "a", false).withTimestamp(1));
                var b = repo.insert(item("beta", "b", false).withTimestamp(2));
                var c = repo.insert(item("gamma", "c", true).withTimestamp(3));

                assertTrue(repo.setCollection(a.id(), work.id()));
                assertTrue(repo.setCollection(b.id(), work.id()));
                var filed = repo.findById(a.id()).orElseThrow();
                assertTrue(filed.pinned(), "filing an item pins it");
                assertEquals(work.id(), filed.collectionId());

                assertEquals(List.of(b.id(), a.id()), ids(repo.findPreviews("", HistoryFilter.collection(work.id()), 10)));
                assertEquals(List.of(a.id()), ids(repo.findPreviews("alp", HistoryFilter.collection(work.id()), 10)));
                assertEquals(List.of(c.id(), b.id(), a.id()), ids(repo.findPreviews("", HistoryFilter.PINNED, 10)));
                assertEquals(work.id(), repo.findPreviews("", HistoryFilter.ALL, 10).stream()
                        .filter(p -> p.id() == a.id()).findFirst().orElseThrow().collectionId());
                assertEquals(List.of(new ClipboardCollection(work.id(), "Work", 2),
                        new ClipboardCollection(misc.id(), "Misc \u2728 & odds_ends", 0)), repo.findCollections());

                assertTrue(repo.renameCollection(work.id(), "Job"));
                assertEquals("Job", repo.findCollections().getFirst().name());

                // Unpinning takes an item out of its collection.
                repo.setPinned(b.id(), false);
                assertEquals(List.of(a.id()), ids(repo.findPreviews("", HistoryFilter.collection(work.id()), 10)));
                assertTrue(repo.findById(b.id()).orElseThrow().collectionId() == null);

                // Deleting a collection keeps its items, still pinned.
                assertTrue(repo.deleteCollection(work.id()));
                var orphan = repo.findById(a.id()).orElseThrow();
                assertTrue(orphan.pinned());
                assertTrue(orphan.collectionId() == null);
                assertEquals(List.of(misc.id()), repo.findCollections().stream().map(ClipboardCollection::id).toList());
                assertEquals(1, repo.deleteUnpinned());
                assertEquals(2, repo.count());
                repo.deleteAll();
                repo.deleteCollection(misc.id());
            }
        }
    }

    @Test
    void collectionsSurviveReopenAndUpgradeKeepsExistingItems() throws Exception {
        Path file = dir.resolve("collections-reopen.db");
        long itemId;
        long collectionId;
        try (Database db = new Database(file)) {
            var repo = new SqliteClipboardRepository(db.connection());
            itemId = repo.insert(item("keep me", "k", false)).id();
            collectionId = repo.createCollection("Keepers").id();
            repo.setCollection(itemId, collectionId);
            try (var statement = db.connection().createStatement()) {
                statement.execute("PRAGMA user_version = 2"); // rerunning the migration must be harmless
            }
        }
        try (Database db = new Database(file)) {
            var repo = new SqliteClipboardRepository(db.connection());
            assertEquals(collectionId, repo.findById(itemId).orElseThrow().collectionId());
            assertEquals("Keepers", repo.findCollections().getFirst().name());
        }
    }

    private static List<Long> ids(List<model.ClipboardPreview> previews) {
        return previews.stream().map(model.ClipboardPreview::id).toList();
    }

    private static ClipboardItem item(String text, String hash, boolean pinned) {
        return ClipboardItem.builder()
                .contentType(ClipboardContentType.TEXT)
                .hash(hash)
                .preview(text)
                .textContent(text)
                .timestamp(System.currentTimeMillis())
                .pinned(pinned)
                .build();
    }
}

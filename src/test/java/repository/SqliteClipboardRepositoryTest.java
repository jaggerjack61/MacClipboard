package repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import model.ClipboardContentType;
import model.ClipboardItem;
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

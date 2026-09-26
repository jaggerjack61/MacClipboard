package repository;

import java.util.List;
import java.util.Optional;
import model.ClipboardCollection;
import model.ClipboardItem;
import model.ClipboardPreview;
import model.HistoryFilter;

/**
 * Storage abstraction for clipboard history.
 *
 * <p>Keeping this behind an interface lets unit tests run without SQLite or the
 * real macOS clipboard, and lets the app switch between persistent and in-memory
 * modes based on the "store history between restarts" setting.</p>
 */
public interface ClipboardRepository {

    /** Inserts a new entry and returns it with its generated id (or the same id if already present). */
    ClipboardItem insert(ClipboardItem item);

    Optional<ClipboardItem> findByHash(String hash);

    Optional<ClipboardItem> findById(long id);

    /**
     * Most recent entries, pinned first, then newest first. When {@code query} is non-blank
     * only text entries whose content matches (case-insensitive) are returned.
     */
    List<ClipboardItem> findRecent(String query, int limit);

    /** Same ordering/filtering as findRecent, without materializing full payloads. */
    default List<ClipboardPreview> findPreviews(String query, int limit) {
        return findPreviews(query, HistoryFilter.ALL, limit);
    }

    /** Previews restricted to all items, pinned items, or one collection. */
    List<ClipboardPreview> findPreviews(String query, HistoryFilter filter, int limit);

    /** Returns the hash of the most recently stored entry, used for cheap duplicate detection. */
    Optional<String> latestHash();

    /** Unpinning also takes the item out of its collection. */
    boolean setPinned(long id, boolean pinned);

    /**
     * Files an item under a collection, which pins it; {@code null} leaves it pinned but in
     * no collection.
     */
    boolean setCollection(long id, Long collectionId);

    /** Collections in creation order, each with its item count. */
    List<ClipboardCollection> findCollections();

    ClipboardCollection createCollection(String name);

    boolean renameCollection(long id, String name);

    /** Deletes the collection only; its items stay pinned. */
    boolean deleteCollection(long id);

    /** Moves an existing entry to the top of the history by updating its timestamp. */
    boolean touch(long id, long newTimestamp);

    boolean delete(long id);

    int deleteUnpinned();

    int deleteAll();

    /** Deletes the oldest non-pinned items so that at most {@code maxUnpinned} unpinned items remain. */
    int enforceLimit(int maxUnpinned);

    /** Deletes non-pinned items older than {@code olderThanMillis}. */
    int deleteOlderThan(long olderThanMillis);

    long count();
}

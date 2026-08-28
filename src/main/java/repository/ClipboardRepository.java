package repository;

import java.util.List;
import java.util.Optional;
import model.ClipboardItem;

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

    /** Returns the hash of the most recently stored entry, used for cheap duplicate detection. */
    Optional<String> latestHash();

    boolean setPinned(long id, boolean pinned);

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

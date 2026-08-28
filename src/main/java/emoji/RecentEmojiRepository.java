package emoji;

import java.util.List;

/**
 * Stores emoji usage counts and last-used timestamps. Implementations: SQLite for
 * persistence, and an in-memory version used in tests / when emoji memory is disabled.
 */
public interface RecentEmojiRepository {

    /** All stored entries (unspecified order). */
    List<RecentEmojiEntry> entries();

    void recordUse(String character, long timestamp);

    void remove(List<String> characters);

    void clear();
}

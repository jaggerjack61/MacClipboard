package emoji;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Volatile {@link RecentEmojiRepository} used when "remember recently used emojis"
 * is disabled and in unit tests.
 */
public final class InMemoryRecentEmojiRepository implements RecentEmojiRepository {

    private final Map<String, long[]> data = new LinkedHashMap<>(); // character -> {count, lastUsed}

    @Override
    public synchronized List<RecentEmojiEntry> entries() {
        List<RecentEmojiEntry> result = new ArrayList<>();
        data.forEach((c, v) -> result.add(new RecentEmojiEntry(c, (int) v[0], v[1])));
        return result;
    }

    @Override
    public synchronized void recordUse(String character, long timestamp) {
        long[] v = data.get(character);
        if (v == null) {
            data.put(character, new long[]{1, timestamp});
        } else {
            v[0]++;
            v[1] = Math.max(v[1], timestamp);
        }
    }

    @Override
    public synchronized void remove(List<String> characters) {
        characters.forEach(data::remove);
    }

    @Override
    public synchronized void clear() {
        data.clear();
    }
}

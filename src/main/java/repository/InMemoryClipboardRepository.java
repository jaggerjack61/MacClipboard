package repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import model.ClipboardCollection;
import model.ClipboardItem;
import model.ClipboardPreview;
import model.HistoryFilter;

/**
 * Volatile {@link ClipboardRepository} used when "store history between restarts" is
 * disabled, and by unit tests. Behaviour mirrors {@link SqliteClipboardRepository}.
 */
public final class InMemoryClipboardRepository implements ClipboardRepository {

    private final List<ClipboardItem> items = new ArrayList<>();
    private final Map<Long, String> collections = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1);
    private final AtomicLong collectionSequence = new AtomicLong(1);

    @Override
    public synchronized ClipboardItem insert(ClipboardItem item) {
        ClipboardItem stored = item.withId(sequence.getAndIncrement());
        items.add(stored);
        return stored;
    }

    @Override
    public synchronized Optional<ClipboardItem> findByHash(String hash) {
        return items.stream()
                .filter(i -> i.hash() != null && i.hash().equals(hash))
                .max(Comparator.comparingLong(ClipboardItem::timestamp)
                        .thenComparingLong(ClipboardItem::id));
    }

    @Override
    public synchronized Optional<ClipboardItem> findById(long id) {
        return items.stream().filter(i -> i.id() == id).findFirst();
    }

    @Override
    public synchronized List<ClipboardItem> findRecent(String query, int limit) {
        boolean filtered = query != null && !query.isBlank();
        String needle = filtered ? query.trim().toLowerCase() : null;
        return sortedStream()
                .filter(i -> !filtered || (i.textContent() != null
                        && i.textContent().toLowerCase().contains(needle)))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public synchronized Optional<String> latestHash() {
        return items.stream().max(Comparator.comparingLong(ClipboardItem::timestamp)
                .thenComparingLong(ClipboardItem::id)).map(ClipboardItem::hash);
    }

    @Override
    public synchronized List<ClipboardPreview> findPreviews(String query, HistoryFilter filter, int limit) {
        boolean filtered = query != null && !query.isBlank();
        String needle = filtered ? query.trim().toLowerCase() : null;
        Stream<ClipboardItem> stream = filter.kind() == HistoryFilter.Kind.ALL ? sortedStream()
                : items.stream().sorted(Comparator.comparingLong(ClipboardItem::timestamp)
                        .thenComparingLong(ClipboardItem::id).reversed());
        return stream
                .filter(i -> switch (filter.kind()) {
                    case ALL -> true;
                    case PINNED -> i.pinned();
                    case COLLECTION -> i.collectionId() != null && i.collectionId() == filter.collectionId();
                })
                .filter(i -> !filtered || (i.textContent() != null
                        && i.textContent().toLowerCase().contains(needle)))
                .limit(Math.max(1, limit))
                .map(ClipboardPreview::from)
                .toList();
    }

    @Override
    public synchronized boolean setPinned(long id, boolean pinned) {
        return replace(id, item -> pinned ? item.withPinned(true) : item.withPinned(false).withCollection(null));
    }

    @Override
    public synchronized boolean setCollection(long id, Long collectionId) {
        return replace(id, item -> item.withPinned(true).withCollection(collectionId));
    }

    @Override
    public synchronized List<ClipboardCollection> findCollections() {
        return collections.entrySet().stream()
                .map(e -> new ClipboardCollection(e.getKey(), e.getValue(), (int) items.stream()
                        .filter(i -> e.getKey().equals(i.collectionId())).count()))
                .toList();
    }

    @Override
    public synchronized ClipboardCollection createCollection(String name) {
        long id = collectionSequence.getAndIncrement();
        collections.put(id, name);
        return new ClipboardCollection(id, name, 0);
    }

    @Override
    public synchronized boolean renameCollection(long id, String name) {
        return collections.replace(id, name) != null;
    }

    @Override
    public synchronized boolean deleteCollection(long id) {
        if (collections.remove(id) == null) {
            return false;
        }
        items.replaceAll(i -> Long.valueOf(id).equals(i.collectionId()) ? i.withCollection(null) : i);
        return true;
    }

    @Override
    public synchronized boolean touch(long id, long newTimestamp) {
        return replace(id, item -> item.withTimestamp(newTimestamp));
    }

    @Override
    public synchronized boolean delete(long id) {
        return items.removeIf(i -> i.id() == id);
    }

    @Override
    public synchronized int deleteUnpinned() {
        int before = items.size();
        items.removeIf(i -> !i.pinned());
        return before - items.size();
    }

    @Override
    public synchronized int deleteAll() {
        int before = items.size();
        items.clear();
        return before;
    }

    @Override
    public synchronized int enforceLimit(int maxUnpinned) {
        List<ClipboardItem> unpinned = items.stream()
                .filter(i -> !i.pinned())
                .sorted(Comparator.comparingLong(ClipboardItem::timestamp)
                        .thenComparingLong(ClipboardItem::id).reversed())
                .toList();
        int removed = 0;
        for (int i = Math.max(0, maxUnpinned); i < unpinned.size(); i++) {
            delete(unpinned.get(i).id());
            removed++;
        }
        return removed;
    }

    @Override
    public synchronized int deleteOlderThan(long olderThanMillis) {
        int before = items.size();
        items.removeIf(i -> !i.pinned() && i.timestamp() < olderThanMillis);
        return before - items.size();
    }

    @Override
    public synchronized long count() {
        return items.size();
    }

    private Stream<ClipboardItem> sortedStream() {
        return items.stream().sorted(Comparator
                .comparing(ClipboardItem::pinned).reversed()
                .thenComparing(Comparator.comparingLong(ClipboardItem::timestamp)
                        .thenComparingLong(ClipboardItem::id).reversed()));
    }

    private boolean replace(long id, java.util.function.UnaryOperator<ClipboardItem> updater) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id() == id) {
                items.set(i, updater.apply(items.get(i)));
                return true;
            }
        }
        return false;
    }
}

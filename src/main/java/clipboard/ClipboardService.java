package clipboard;

import config.ApplicationSettings;
import java.util.List;
import java.util.Optional;
import model.ClipboardContentType;
import model.ClipboardItem;
import repository.ClipboardRepository;

/**
 * Platform-independent clipboard history logic: duplicate detection, history limit,
 * pinning, deletion, search, retention and restoration. Deliberately knows nothing
 * about AWT or JavaFX so it can be exercised in unit tests.
 */
public final class ClipboardService {

    private static final int PREVIEW_MAX_CHARS = 220;

    private final ClipboardRepository repository;
    private final ApplicationSettings settings;
    /** Monotonic timestamp source so ordering is stable even for burst copies. */
    private long lastTimestamp;

    public ClipboardService(ClipboardRepository repository, ApplicationSettings settings) {
        this.repository = repository;
        this.settings = settings;
    }

    private synchronized long nextTimestamp() {
        long now = System.currentTimeMillis();
        lastTimestamp = Math.max(now, lastTimestamp + 1);
        return lastTimestamp;
    }

    /**
     * Records a clipboard snapshot unless it duplicates the most recent stored entry.
     *
     * @return the created/updated item, or empty when the snapshot was a duplicate
     */
    public synchronized Optional<ClipboardItem> ingest(ClipboardSnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return Optional.empty();
        }
        String hash = ClipboardHasher.hash(snapshot);
        Optional<String> latestHash = repository.latestHash();
        if (latestHash.filter(hash::equals).isPresent()) {
            return Optional.empty();
        }
        // If an identical item already exists deeper in history (re-copy of old item),
        // move it to the top instead of creating a duplicate.
        Optional<ClipboardItem> existing = repository.findByHash(hash);
        if (existing.isPresent()) {
            long now = nextTimestamp();
            repository.touch(existing.get().id(), now);
            applyLimits();
            return Optional.of(existing.get().withTimestamp(now));
        }

        ClipboardItem item = ClipboardItem.builder()
                .contentType(snapshot.contentType())
                .hash(hash)
                .preview(previewFor(snapshot))
                .textContent(snapshot.text())
                .htmlContent(snapshot.html())
                .image(snapshot.image())
                .thumbnail(snapshot.thumbnail())
                .timestamp(nextTimestamp())
                .build();
        ClipboardItem stored = repository.insert(item);
        applyLimits();
        return Optional.of(stored);
    }

    public synchronized void copyToClipboard(ClipboardItem item, ClipboardGateway gateway) {
        gateway.write(toSnapshot(item));
        repository.touch(item.id(), System.currentTimeMillis());
        applyLimits();
    }

    public List<ClipboardItem> history(String query) {
        return repository.findRecent(query, historyWindow());
    }

    /** Pinned and recent entries share one list; show a generous window and let the UI scroll. */
    private int historyWindow() {
        return Math.max(50, settings.maxHistory() + 50);
    }

    public synchronized boolean togglePin(long id) {
        return repository.findById(id)
                .map(item -> repository.setPinned(id, !item.pinned()))
                .orElse(false);
    }

    public boolean delete(long id) {
        return repository.delete(id);
    }

    public int clearUnpinned() {
        return repository.deleteUnpinned();
    }

    public int clearAll() {
        return repository.deleteAll();
    }

    public Optional<ClipboardItem> findById(long id) {
        return repository.findById(id);
    }

    public long count() {
        return repository.count();
    }

    /** Enforces both the configurable history size and the retention period. */
    public synchronized void applyLimits() {
        repository.enforceLimit(settings.maxHistory());
        applyRetention();
    }

    public synchronized int applyRetention() {
        int days = settings.retentionDays();
        if (days <= 0) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);
        return repository.deleteOlderThan(cutoff);
    }

    public static ClipboardSnapshot toSnapshot(ClipboardItem item) {
        return switch (item.contentType()) {
            case IMAGE -> ClipboardSnapshot.image(item.image(), item.thumbnail(), 0, 0);
            case RICH_TEXT -> ClipboardSnapshot.text(item.textContent(), item.htmlContent());
            default -> ClipboardSnapshot.text(item.textContent(), null);
        };
    }

    private static String previewFor(ClipboardSnapshot snapshot) {
        if (snapshot.contentType() == ClipboardContentType.IMAGE) {
            if (snapshot.imageWidth() > 0) {
                return "Image " + snapshot.imageWidth() + "\u00d7" + snapshot.imageHeight();
            }
            return "Image";
        }
        String text = snapshot.text() == null ? "" : snapshot.text();
        String flattened = text.strip().replaceAll("\\s+", " ");
        if (flattened.length() <= PREVIEW_MAX_CHARS) {
            return flattened;
        }
        return flattened.substring(0, PREVIEW_MAX_CHARS) + "\u2026";
    }
}

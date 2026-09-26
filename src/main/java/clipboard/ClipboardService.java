package clipboard;

import config.ApplicationSettings;
import java.util.List;
import java.util.Optional;
import model.ClipboardCollection;
import model.ClipboardContentType;
import model.ClipboardItem;
import model.ClipboardPreview;
import model.HistoryFilter;
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
    /** Observation state must survive deletion/clearing of the stored history. */
    private String lastObservedHash;

    public ClipboardService(ClipboardRepository repository, ApplicationSettings settings) {
        this.repository = repository;
        this.settings = settings;
        this.lastObservedHash = repository.latestHash().orElse(null);
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
        return ingest(snapshot, false);
    }

    /** A new native revision permits an explicit re-copy after the user deleted an item. */
    synchronized Optional<ClipboardItem> ingest(ClipboardSnapshot snapshot, boolean clipboardChanged) {
        if (snapshot == null || snapshot.isEmpty()) {
            return Optional.empty();
        }
        String hash = ClipboardHasher.hash(snapshot);
        if (!clipboardChanged && hash.equals(lastObservedHash)) {
            return Optional.empty();
        }
        Optional<String> latestHash = repository.latestHash();
        if (latestHash.filter(hash::equals).isPresent()) {
            lastObservedHash = hash;
            return Optional.empty();
        }
        // If an identical item already exists deeper in history (re-copy of old item),
        // move it to the top instead of creating a duplicate.
        Optional<ClipboardItem> existing = repository.findByHash(hash);
        if (existing.isPresent()) {
            long now = nextTimestamp();
            repository.touch(existing.get().id(), now);
            applyLimits();
            lastObservedHash = hash;
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
        lastObservedHash = hash;
        return Optional.of(stored);
    }

    public synchronized void copyToClipboard(ClipboardItem item, ClipboardGateway gateway) {
        gateway.write(toSnapshot(item));
        repository.touch(item.id(), nextTimestamp());
        applyLimits();
        lastObservedHash = item.hash();
    }

    public List<ClipboardItem> history(String query) {
        return repository.findRecent(query, historyWindow());
    }

    public List<ClipboardPreview> previews(String query) {
        return previews(query, HistoryFilter.ALL);
    }

    public List<ClipboardPreview> previews(String query, HistoryFilter filter) {
        return repository.findPreviews(query, filter, historyWindow());
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

    // ---- Collections --------------------------------------------------------------

    public List<ClipboardCollection> collections() {
        return repository.findCollections();
    }

    /**
     * Why {@code name} cannot be used for a collection (blank, or already taken by another
     * collection, ignoring case), or empty when it is fine. {@code renaming} is the id of
     * the collection being renamed, or null when creating one.
     */
    public Optional<String> collectionNameProblem(String name, Long renaming) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            return Optional.of("Enter a name");
        }
        boolean taken = repository.findCollections().stream()
                .anyMatch(c -> c.name().equalsIgnoreCase(clean) && !Long.valueOf(c.id()).equals(renaming));
        return taken ? Optional.of("A collection with that name already exists") : Optional.empty();
    }

    /** @throws IllegalArgumentException when {@link #collectionNameProblem} reports one */
    public synchronized ClipboardCollection createCollection(String name) {
        collectionNameProblem(name, null).ifPresent(problem -> {
            throw new IllegalArgumentException(problem);
        });
        return repository.createCollection(name.strip());
    }

    /** @throws IllegalArgumentException when {@link #collectionNameProblem} reports one */
    public synchronized boolean renameCollection(long id, String name) {
        collectionNameProblem(name, id).ifPresent(problem -> {
            throw new IllegalArgumentException(problem);
        });
        return repository.renameCollection(id, name.strip());
    }

    /** Removes the collection; the items it held stay pinned. */
    public synchronized boolean deleteCollection(long id) {
        return repository.deleteCollection(id);
    }

    /** Files an item under a collection (pinning it), or with null keeps it pinned in none. */
    public synchronized boolean moveToCollection(long itemId, Long collectionId) {
        return repository.setCollection(itemId, collectionId);
    }

    public synchronized boolean delete(long id) {
        return repository.delete(id);
    }

    public synchronized int clearUnpinned() {
        return repository.deleteUnpinned();
    }

    public synchronized int clearAll() {
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

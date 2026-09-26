package model;

/** Which slice of the history a list shows: everything, pinned items, or one collection. */
public record HistoryFilter(Kind kind, long collectionId) {

    public enum Kind { ALL, PINNED, COLLECTION }

    public static final HistoryFilter ALL = new HistoryFilter(Kind.ALL, 0);
    public static final HistoryFilter PINNED = new HistoryFilter(Kind.PINNED, 0);

    public static HistoryFilter collection(long id) {
        return new HistoryFilter(Kind.COLLECTION, id);
    }
}

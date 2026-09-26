package model;

/** A user-named group of pinned clipboard items, with how many items it holds. */
public record ClipboardCollection(long id, String name, int itemCount) {
}

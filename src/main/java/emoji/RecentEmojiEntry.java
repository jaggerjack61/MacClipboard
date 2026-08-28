package emoji;

/**
 * A stored recent-emoji record: which emoji, how often used, and when last used.
 */
public record RecentEmojiEntry(String character, int count, long lastUsed) {
}

package ui;

/**
 * Formats timestamps as compact relative strings, e.g. "10 seconds ago".
 */
public final class RelativeTime {

    private RelativeTime() {
    }

    public static String format(long timestampMillis) {
        return format(timestampMillis, System.currentTimeMillis());
    }

    public static String format(long timestampMillis, long nowMillis) {
        long diff = Math.max(0, nowMillis - timestampMillis);
        long seconds = diff / 1000;
        if (seconds < 10) {
            return "just now";
        }
        if (seconds < 60) {
            return seconds + " seconds ago";
        }
        long minutes = seconds / 60;
        if (minutes == 1) {
            return "1 minute ago";
        }
        if (minutes < 60) {
            return minutes + " minutes ago";
        }
        long hours = minutes / 60;
        if (hours == 1) {
            return "1 hour ago";
        }
        if (hours < 24) {
            return hours + " hours ago";
        }
        long days = hours / 24;
        if (days == 1) {
            return "yesterday";
        }
        if (days < 7) {
            return days + " days ago";
        }
        return java.time.Instant.ofEpochMilli(timestampMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d"));
    }
}

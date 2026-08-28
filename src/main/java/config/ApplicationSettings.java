package config;

import java.util.List;
import java.util.Map;
import repository.SettingsStore;

/**
 * Typed view over the persisted {@link SettingsStore}. Values fall back to defaults
 * when not present. This is the single source of truth for user preferences and is
 * read by the UI, clipboard monitor, paste and hotkey services.
 */
public final class ApplicationSettings {

    public static final int DEFAULT_MAX_HISTORY = 100;
    public static final String DEFAULT_SHORTCUT = "MAC+SHIFT+V";
    public static final int DEFAULT_MAX_RECENT_EMOJIS = 60;

    private static final String K_MAX_HISTORY = "max_history";
    private static final String K_RETENTION_DAYS = "retention_days";
    private static final String K_LAUNCH_AT_LOGIN = "launch_at_login";
    private static final String K_SHORTCUT = "global_shortcut";
    private static final String K_AUTO_PASTE = "auto_paste";
    private static final String K_PERSIST = "persist_history";
    private static final String K_REMEMBER_EMOJIS = "remember_recent_emojis";
    private static final String K_MAX_RECENT_EMOJIS = "max_recent_emojis";
    private static final String K_MONITORING = "monitoring_enabled";
    private static final String K_IGNORED_APPS = "ignored_apps";
    private static final String K_POLL_INTERVAL = "poll_interval_ms";

    private final SettingsStore store;
    private final Map<String, String> cache;

    public ApplicationSettings(SettingsStore store) {
        this.store = store;
        this.cache = new java.util.HashMap<>(store.loadAll());
    }

    public int maxHistory() {
        return getInt(K_MAX_HISTORY, DEFAULT_MAX_HISTORY);
    }

    public void setMaxHistory(int value) {
        putInt(K_MAX_HISTORY, Math.max(10, value));
    }

    /** Retention window in days. 0 means no time-based retention. */
    public int retentionDays() {
        return getInt(K_RETENTION_DAYS, 0);
    }

    public void setRetentionDays(int days) {
        putInt(K_RETENTION_DAYS, Math.max(0, days));
    }

    public boolean launchAtLogin() {
        return getBool(K_LAUNCH_AT_LOGIN, false);
    }

    public void setLaunchAtLogin(boolean value) {
        putBool(K_LAUNCH_AT_LOGIN, value);
    }

    public String globalShortcut() {
        return get(K_SHORTCUT, DEFAULT_SHORTCUT);
    }

    public void setGlobalShortcut(String value) {
        put(K_SHORTCUT, value);
    }

    public boolean autoPaste() {
        return getBool(K_AUTO_PASTE, true);
    }

    public void setAutoPaste(boolean value) {
        putBool(K_AUTO_PASTE, value);
    }

    public boolean persistHistory() {
        return getBool(K_PERSIST, true);
    }

    public void setPersistHistory(boolean value) {
        putBool(K_PERSIST, value);
    }

    public boolean rememberRecentEmojis() {
        return getBool(K_REMEMBER_EMOJIS, true);
    }

    public void setRememberRecentEmojis(boolean value) {
        putBool(K_REMEMBER_EMOJIS, value);
    }

    public int maxRecentEmojis() {
        return getInt(K_MAX_RECENT_EMOJIS, DEFAULT_MAX_RECENT_EMOJIS);
    }

    public void setMaxRecentEmojis(int value) {
        putInt(K_MAX_RECENT_EMOJIS, Math.max(1, value));
    }

    public boolean monitoringEnabled() {
        return getBool(K_MONITORING, true);
    }

    public void setMonitoringEnabled(boolean value) {
        putBool(K_MONITORING, value);
    }

    public List<String> ignoredApps() {
        String raw = get(K_IGNORED_APPS, "1Password,Keychain Access,Bitwarden,KeePass");
        return raw.isBlank() ? List.of() : List.of(raw.split(","));
    }

    public void setIgnoredApps(List<String> apps) {
        put(K_IGNORED_APPS, String.join(",", apps));
    }

    public int pollIntervalMs() {
        return getInt(K_POLL_INTERVAL, 400);
    }

    private String get(String key, String def) {
        return cache.getOrDefault(key, def);
    }

    private int getInt(String key, int def) {
        try {
            return Integer.parseInt(cache.getOrDefault(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private boolean getBool(String key, boolean def) {
        String v = cache.get(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    private void put(String key, String value) {
        cache.put(key, value);
        store.save(key, value);
    }

    private void putInt(String key, int value) {
        put(key, String.valueOf(value));
    }

    private void putBool(String key, boolean value) {
        put(key, String.valueOf(value));
    }
}

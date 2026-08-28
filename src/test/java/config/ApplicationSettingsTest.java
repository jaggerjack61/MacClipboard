package config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import repository.Database;
import repository.SqliteSettingsStore;

class ApplicationSettingsTest {

    @TempDir
    Path dir;

    @Test
    void fallsBackToDefaults() {
        try (Database db = new Database(dir.resolve("s.db"))) {
            ApplicationSettings settings = new ApplicationSettings(new SqliteSettingsStore(db.connection()));
            assertEquals(ApplicationSettings.DEFAULT_MAX_HISTORY, settings.maxHistory());
            assertEquals(ApplicationSettings.DEFAULT_SHORTCUT, settings.globalShortcut());
            assertTrue(settings.autoPaste());
            assertTrue(settings.persistHistory());
            assertTrue(settings.monitoringEnabled());
            assertFalse(settings.launchAtLogin());
        }
    }

    @Test
    void persistsValuesAcrossReopen() {
        Path file = dir.resolve("persist.db");
        try (Database db = new Database(file)) {
            ApplicationSettings settings = new ApplicationSettings(new SqliteSettingsStore(db.connection()));
            settings.setMaxHistory(42);
            settings.setRetentionDays(7);
            settings.setAutoPaste(false);
            settings.setGlobalShortcut("CTRL+ALT+V");
            settings.setRememberRecentEmojis(false);
            settings.setMaxRecentEmojis(15);
        }
        try (Database db = new Database(file)) {
            ApplicationSettings settings = new ApplicationSettings(new SqliteSettingsStore(db.connection()));
            assertEquals(42, settings.maxHistory());
            assertEquals(7, settings.retentionDays());
            assertFalse(settings.autoPaste());
            assertEquals("CTRL+ALT+V", settings.globalShortcut());
            assertFalse(settings.rememberRecentEmojis());
            assertEquals(15, settings.maxRecentEmojis());
        }
    }

    @Test
    void clampsHistorySize() {
        try (Database db = new Database(dir.resolve("clamp.db"))) {
            ApplicationSettings settings = new ApplicationSettings(new SqliteSettingsStore(db.connection()));
            settings.setMaxHistory(1);
            assertEquals(10, settings.maxHistory());
        }
    }
}

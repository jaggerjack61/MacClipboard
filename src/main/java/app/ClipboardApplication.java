package app;

import clipboard.AwtClipboardGateway;
import clipboard.ClipboardGateway;
import clipboard.ClipboardSnapshot;
import clipboard.ClipboardMonitor;
import clipboard.ClipboardService;
import config.ApplicationSettings;
import emoji.Emoji;
import emoji.EmojiRepository;
import emoji.EmojiService;
import emoji.InMemoryRecentEmojiRepository;
import emoji.RecentEmojiRepository;
import emoji.RecentEmojiService;
import emoji.SqliteRecentEmojiRepository;
import hotkey.GlobalHotkeyService;
import hotkey.MacGlobalHotkeyService;
import hotkey.ShortcutModifier;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;
import javafx.application.Application;
import javafx.application.Platform;
import model.ClipboardItem;
import paste.MacPasteService;
import paste.PasteService;
import platform.LaunchAtLogin;
import platform.MacNative;
import repository.ClipboardRepository;
import repository.Database;
import repository.InMemoryClipboardRepository;
import repository.SettingsStore;
import repository.SqliteClipboardRepository;
import repository.SqliteSettingsStore;
import security.PrivacyService;
import tray.MenuBarService;
import ui.ClipboardPopupController;
import ui.SettingsController;
import javafx.stage.Stage;

/**
 * Application entry point. Wires the clipboard monitor, persistence, emoji services,
 * global hotkey, paste service, popup and tray together. JavaFX runs as an accessory
 * (menu-bar-only) app on macOS.
 */
public final class ClipboardApplication extends Application {

    private static final Logger LOG = Logger.getLogger(ClipboardApplication.class.getName());

    private Database database;
    private ApplicationSettings settings;
    private ClipboardService clipboardService;
    private ClipboardGateway gateway;
    private ClipboardMonitor monitor;
    private RecentEmojiService recentEmojiService;
    private GlobalHotkeyService hotkeys;
    private MenuBarService tray;
    private ClipboardPopupController popup;
    private SettingsController settingsController;
    private PasteService pasteService;
    private final ScheduledExecutorService pasteExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "paste-thread");
                t.setDaemon(true);
                return t;
            });

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage ignored) {
        // Hide the Dock icon when running via gradlew (the packaged app uses LSUIElement).
        MacNative.setActivationPolicyAccessory();
        Platform.setImplicitExit(false);

        Path dataDir = resolveDataDir();
        Database db = openDatabase(dataDir);
        SettingsStore store = db != null ? new SqliteSettingsStore(db.connection()) : new InMemorySettingsStore();
        settings = new ApplicationSettings(store);

        ClipboardRepository repository = settings.persistHistory() && db != null
                ? new SqliteClipboardRepository(db.connection())
                : new InMemoryClipboardRepository();

        gateway = new AwtClipboardGateway();
        PrivacyService privacy = new PrivacyService(settings);
        clipboardService = new ClipboardService(repository, settings);

        EmojiRepository emojiRepo = new EmojiRepository();
        EmojiService emojiService = new EmojiService(emojiRepo);
        RecentEmojiRepository recentRepo = settings.persistHistory() && db != null
                ? new SqliteRecentEmojiRepository(db.connection())
                : new InMemoryRecentEmojiRepository();
        recentEmojiService = new RecentEmojiService(recentRepo, settings);

        pasteService = new MacPasteService();

        popup = new ClipboardPopupController(clipboardService, emojiService, recentEmojiService,
                settings, pasteService, this::handleClipboardSelection, this::handleEmojiSelection);

        hotkeys = new MacGlobalHotkeyService();
        settingsController = new SettingsController(settings, clipboardService, hotkeys, pasteService);
        Runnable togglePopup = () -> Platform.runLater(
                () -> popup.toggle(ClipboardPopupController.Tab.CLIPBOARD));
        settingsController.setHotkeyCallback(togglePopup);

        ShortcutModifier shortcut = ShortcutModifier.parse(settings.globalShortcut());
        if (shortcut != null) {
            boolean ok = hotkeys.register(shortcut, togglePopup);
            if (!ok) {
                LOG.warning("Global hotkey could not be registered; grant Accessibility permission in Settings.");
            }
        }

        monitor = new ClipboardMonitor(gateway, clipboardService, settings, privacy);
        monitor.start();

        tray = new MenuBarService(
                this::openClipboardPopup,
                this::openEmojiPopup,
                () -> toggleMonitoring(),
                () -> clipboardService.clearUnpinned(),
                settingsController::show,
                this::quit);
        tray.install();
        tray.setPaused(!settings.monitoringEnabled());

        if (db != null) {
            clipboardService.applyRetention();
        }

        runDevSelfTestIfRequested();
    }

    /**
     * Development aid: with {@code -Dclipboard.dev.popup} the app seeds sample data and
     * opens the popup automatically, so the UI can be verified/screenshotted without
     * needing the global hotkey or Accessibility permission.
     */
    private void runDevSelfTestIfRequested() {
        if (!Boolean.getBoolean("clipboard.dev.popup")) {
            return;
        }
        clipboardService.ingest(ClipboardSnapshot.text("Hello world", null));
        clipboardService.ingest(ClipboardSnapshot.text("SELECT * FROM users WHERE id = 42", null));
        clipboardService.ingest(ClipboardSnapshot.text("https://example.com/very/long/path", null));
        new java.util.Timer(true).schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> {
                    if (Boolean.getBoolean("clipboard.dev.settings")) {
                        settingsController.show();
                        return;
                    }
                    ClipboardPopupController.Tab tab = Boolean.getBoolean("clipboard.dev.emoji")
                            ? ClipboardPopupController.Tab.EMOJI
                            : ClipboardPopupController.Tab.CLIPBOARD;
                    popup.show(tab);
                });
            }
        }, 1500);
    }

    private void openClipboardPopup() {
        popup.show(ClipboardPopupController.Tab.CLIPBOARD);
    }

    private void openEmojiPopup() {
        popup.show(ClipboardPopupController.Tab.EMOJI);
    }

    private void toggleMonitoring() {
        settings.setMonitoringEnabled(!settings.monitoringEnabled());
        tray.setPaused(!settings.monitoringEnabled());
    }

    /** Clipboard item chosen from history: restore to clipboard and optionally paste. */
    private void handleClipboardSelection(ClipboardItem item) {
        clipboardService.copyToClipboard(item, gateway);
        schedulePasteIfEnabled();
    }

    /** Emoji chosen: copy it, remember it, and optionally paste it. */
    private void handleEmojiSelection(Emoji emoji) {
        gateway.write(ClipboardSnapshot.text(emoji.character(), null));
        recentEmojiService.record(emoji.character());
        schedulePasteIfEnabled();
    }

    private void schedulePasteIfEnabled() {
        if (!settings.autoPaste()) {
            return;
        }
        pasteExecutor.execute(() -> pasteService.restoreFocusAndPaste(true));
    }

    private void quit() {
        Platform.exit();
        System.exit(0);
    }

    private static Path resolveDataDir() {
        return Path.of(System.getProperty("user.home"), "Library", "Application Support", "Clipboard");
    }

    private Database openDatabase(Path dataDir) {
        try {
            database = new Database(dataDir.resolve("clipboard.db"));
            return database;
        } catch (RuntimeException e) {
            LOG.warning("SQLite unavailable, falling back to in-memory history: " + e.getMessage());
            return null;
        }
    }

    /** Fallback {@link SettingsStore} if SQLite cannot be opened. */
    private static final class InMemorySettingsStore implements SettingsStore {
        private final java.util.Map<String, String> map = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public java.util.Map<String, String> loadAll() {
            return new java.util.HashMap<>(map);
        }

        @Override
        public void save(String key, String value) {
            map.put(key, value);
        }

        @Override
        public void delete(String key) {
            map.remove(key);
        }
    }

    @Override
    public void stop() {
        if (monitor != null) {
            monitor.close();
        }
        if (hotkeys != null) {
            hotkeys.close();
        }
        if (tray != null) {
            tray.dispose();
        }
        pasteExecutor.shutdownNow();
        if (database != null) {
            database.close();
        }
    }
}

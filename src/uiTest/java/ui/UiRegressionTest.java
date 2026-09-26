package ui;

import static org.junit.jupiter.api.Assertions.*;

import clipboard.ClipboardService;
import clipboard.ClipboardSnapshot;
import config.ApplicationSettings;
import hotkey.GlobalHotkeyService;
import hotkey.ShortcutModifier;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import model.ClipboardPreview;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import paste.PasteService;
import repository.ClipboardRepository;
import repository.InMemoryClipboardRepository;
import repository.SettingsStore;

class UiRegressionTest {
    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopFx() { Platform.exit(); }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private static ApplicationSettings settings() {
        return new ApplicationSettings(new SettingsStore() {
            public Map<String, String> loadAll() { return Map.of(); }
            public void save(String key, String value) { }
            public void delete(String key) { }
        });
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.MAC)
    void nativeClipboardRevisionIsAvailableOnThePollingThread() throws Exception {
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            long revision = worker.submit(platform.MacNative::clipboardChangeCount).get(5, TimeUnit.SECONDS);
            assertTrue(revision >= 0, "Native metadata must be available to skip unchanged image reads");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void settingsDisplaySavedValuesStepNormallyAndApplyRetentionImmediately() throws Exception {
        var settings = settings();
        settings.setMaxHistory(120);
        settings.setMaxRecentEmojis(32);
        var repo = new InMemoryClipboardRepository();
        var service = new ClipboardService(repo, settings);
        var old = service.ingest(ClipboardSnapshot.text("expired test item", null)).orElseThrow();
        repo.touch(old.id(), System.currentTimeMillis() - 3L * 86_400_000);
        GlobalHotkeyService hotkey = new GlobalHotkeyService() {
            public boolean register(ShortcutModifier shortcut, Runnable callback) { return true; }
            public void unregister() { }
        };
        PasteService paste = new PasteService() {
            public void captureFocusOwner() { }
            public boolean restoreFocusAndPaste(boolean value) { return false; }
            public boolean canAutoPaste() { return false; }
        };
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            fx(() -> {
                var controller = new SettingsController(settings, service, hotkey, paste, worker);
                var build = SettingsController.class.getDeclaredMethod("buildStage");
                build.setAccessible(true);
                Stage stage = (Stage) build.invoke(controller);
                try {
                    var root = stage.getScene().getRoot();
                    root.applyCss(); // creates the ScrollPane skin so lookups reach its content
                    Spinner<Integer> history = (Spinner<Integer>) root.lookup("#history-size");
                    Spinner<Integer> emoji = (Spinner<Integer>) root.lookup("#recent-emoji-limit");
                    assertEquals(120, history.getValue());
                    assertEquals(32, emoji.getValue());
                    history.increment();
                    emoji.increment();
                    assertEquals(130, settings.maxHistory());
                    assertEquals(36, settings.maxRecentEmojis());
                    ChoiceBox<String> retention = (ChoiceBox<String>) root.lookup("#retention");
                    retention.setValue("1 day");
                    return null;
                } finally {
                    stage.close();
                }
            });
            worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
            assertEquals(1, settings.retentionDays());
            assertEquals(0, service.count());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void slowHistoryQueriesRunOffFxAndCannotOverwriteNewerSearchResults() throws Exception {
        var settings = settings();
        var memory = new InMemoryClipboardRepository();
        var seed = new ClipboardService(memory, settings);
        seed.ingest(ClipboardSnapshot.text("alpha", null));
        seed.ingest(ClipboardSnapshot.text("beta", null));
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch slowReturned = new CountDownLatch(1);
        AtomicBoolean queryOnFx = new AtomicBoolean();
        ClipboardRepository delayed = (ClipboardRepository) Proxy.newProxyInstance(
                ClipboardRepository.class.getClassLoader(), new Class<?>[]{ClipboardRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findPreviews")) {
                        if (Platform.isFxApplicationThread()) queryOnFx.set(true);
                        if ("".equals(args[0])) {
                            slowStarted.countDown();
                            if (!releaseSlow.await(10, TimeUnit.SECONDS)) throw new AssertionError("query never released");
                        }
                    }
                    try {
                        Object result = method.invoke(memory, args);
                        if (method.getName().equals("findPreviews") && "".equals(args[0])) slowReturned.countDown();
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        try (ExecutorService worker = Executors.newFixedThreadPool(2)) {
            ClipboardTabController tab = fx(() -> new ClipboardTabController(new ClipboardService(delayed, settings), null, worker));
            ListView<ClipboardPreview> list = fx(() -> (ListView<ClipboardPreview>) tab.getNode().lookup(".clipboard-list"));
            CountDownLatch betaShown = new CountDownLatch(1);
            fx(() -> {
                list.itemsProperty().addListener((o, old, current) -> {
                    if (current.size() == 1 && "beta".equals(current.getFirst().preview())) betaShown.countDown();
                });
                tab.refresh();
                return null;
            });
            try {
                assertTrue(slowStarted.await(5, TimeUnit.SECONDS));
                fx(() -> {
                    TextField search = (TextField) tab.getNode().lookup(".search-field");
                    search.setText("beta");
                    return null;
                });
                assertTrue(betaShown.await(5, TimeUnit.SECONDS));
                assertFalse(queryOnFx.get());
            } finally {
                releaseSlow.countDown();
            }
            assertTrue(slowReturned.await(5, TimeUnit.SECONDS));
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals("beta", fx(() -> list.getItems().getFirst().preview()));
            assertEquals(1, fx(() -> list.getItems().size()));
        }
    }
}

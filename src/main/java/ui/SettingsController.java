package ui;

import clipboard.ClipboardService;
import config.ApplicationSettings;
import hotkey.GlobalHotkeyService;
import hotkey.ShortcutModifier;
import java.util.List;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import paste.PasteService;
import platform.LaunchAtLogin;
import platform.MacNative;

/**
 * Settings window: history size, retention, login item, shortcut, auto-paste,
 * persistence and recent-emoji options, plus privacy actions.
 */
public final class SettingsController {

    /** Source repository shown in the About section. */
    private static final String ABOUT_REPO_URL = "https://github.com/jaggerjack61/MacClipboard";

    private static final List<String> SHORTCUT_PRESETS = List.of(
            "MAC+SHIFT+V", "CTRL+SHIFT+V", "ALT+SHIFT+V", "MAC+CTRL+V", "CTRL+ALT+V");

    private final ApplicationSettings settings;
    private final ClipboardService clipboardService;
    private final GlobalHotkeyService hotkeys;
    private final PasteService pasteService;
    private Stage stage;

    public SettingsController(ApplicationSettings settings, ClipboardService clipboardService,
                              GlobalHotkeyService hotkeys, PasteService pasteService) {
        this.settings = settings;
        this.clipboardService = clipboardService;
        this.hotkeys = hotkeys;
        this.pasteService = pasteService;
    }

    public void show() {
        if (stage == null) {
            stage = buildStage();
        }
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    private Stage buildStage() {
        Label title = new Label("Clipboard History Settings");
        title.getStyleClass().add("settings-title");

        // History size
        Spinner<Integer> maxHistory = new Spinner<>();
        maxHistory.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(10, 500, 10, settings.maxHistory()));
        maxHistory.setEditable(true);
        maxHistory.setPrefWidth(90);
        maxHistory.valueProperty().addListener((o, old, v) -> settings.setMaxHistory(v));

        // Retention
        ChoiceBox<String> retention = new ChoiceBox<>();
        retention.getItems().addAll("Never", "1 day", "7 days", "30 days", "90 days");
        retention.setValue(mapRetentionToLabel(settings.retentionDays()));
        retention.setOnAction(e -> settings.setRetentionDays(mapLabelToRetention(retention.getValue())));

        // Shortcut
        ChoiceBox<String> shortcut = new ChoiceBox<>();
        for (String preset : SHORTCUT_PRESETS) {
            shortcut.getItems().add(formatShortcut(preset));
        }
        shortcut.setValue(formatShortcut(settings.globalShortcut()));
        shortcut.setOnAction(e -> {
            String raw = SHORTCUT_PRESETS.get(shortcut.getSelectionModel().getSelectedIndex());
            settings.setGlobalShortcut(raw);
            ShortcutModifier parsed = ShortcutModifier.parse(raw);
            if (parsed != null) {
                hotkeys.register(parsed, hotkeyCallback);
            }
        });

        // Toggles
        CheckBox launch = new CheckBox("Launch at login");
        launch.setSelected(settings.launchAtLogin());
        launch.setOnAction(e -> {
            settings.setLaunchAtLogin(launch.isSelected());
            applyLaunchAtLogin(launch.isSelected());
        });

        CheckBox autoPaste = new CheckBox("Automatically paste after selection (requires Accessibility permission)");
        autoPaste.setSelected(settings.autoPaste());
        autoPaste.setOnAction(e -> settings.setAutoPaste(autoPaste.isSelected()));

        CheckBox persist = new CheckBox("Store clipboard history between restarts");
        persist.setSelected(settings.persistHistory());
        persist.setOnAction(e -> settings.setPersistHistory(persist.isSelected()));

        CheckBox rememberEmoji = new CheckBox("Remember recently used emojis");
        rememberEmoji.setSelected(settings.rememberRecentEmojis());
        rememberEmoji.setOnAction(e -> settings.setRememberRecentEmojis(rememberEmoji.isSelected()));

        Spinner<Integer> emojiLimit = new Spinner<>();
        emojiLimit.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(4, 200, 4,
                settings.maxRecentEmojis()));
        emojiLimit.setEditable(true);
        emojiLimit.setPrefWidth(90);
        emojiLimit.valueProperty().addListener((o, old, v) -> settings.setMaxRecentEmojis(v));

        Button clear = new Button("Clear clipboard history now");
        clear.setOnAction(e -> clipboardService.clearUnpinned());

        Label permStatus = new Label(pasteService.canAutoPaste()
                ? "✓ Accessibility permission granted"
                : "✗ Accessibility permission not granted");
        permStatus.getStyleClass().add("hint");
        Button grant = new Button("Open System Settings…");
        grant.setOnAction(e -> MacNative.openAccessibilitySettings());

        // About
        Label aboutTitle = new Label("About");
        aboutTitle.getStyleClass().add("section-header");
        Label aboutAuthor = new Label("Clipboard History is free, open source software, "
                + "created by Samuel Jarai.");
        aboutAuthor.getStyleClass().add("settings-label");
        aboutAuthor.setWrapText(true);
        Hyperlink aboutRepo = new Hyperlink("github.com/jaggerjack61/MacClipboard");
        aboutRepo.setFocusTraversable(false);
        aboutRepo.setOnAction(e -> openInBrowser(ABOUT_REPO_URL));
        Label aboutLicense = new Label("Source is available under the license in the repository.");
        aboutLicense.getStyleClass().add("hint");
        aboutLicense.setWrapText(true);

        HBox shortcutRow = row("Global shortcut:", shortcut);
        VBox box = new VBox(12, title,
                row("History size:", maxHistory),
                row("Retention period:", retention),
                shortcutRow,
                launch, autoPaste, persist, rememberEmoji,
                row("Recent emoji limit:", emojiLimit),
                clear,
                new Label("Clipboard data never leaves this machine."),
                new HBox(10, permStatus, grant),
                aboutTitle, aboutAuthor, aboutRepo, aboutLicense);
        box.setPadding(new Insets(18));
        box.getStyleClass().add("settings-root");

        Scene scene = new Scene(box, 480, 560);
        scene.getStylesheets().add(SettingsController.class.getResource("/ui/clipboard.css").toExternalForm());
        Stage st = new Stage();
        st.initModality(Modality.NONE);
        st.setTitle("Settings");
        st.setScene(scene);
        return st;
    }

    /** Hotkey re-registration needs the popup toggle callback; injected by the app. */
    private Runnable hotkeyCallback = () -> {
    };

    private static void openInBrowser(String url) {
        try {
            new ProcessBuilder("/usr/bin/open", url).start();
        } catch (Exception ignored) {
            // No browser available; the URL is still visible as the link's label.
        }
    }

    public void setHotkeyCallback(Runnable callback) {
        this.hotkeyCallback = callback;
    }

    private static HBox row(String label, javafx.scene.Node control) {
        Label l = new Label(label);
        l.getStyleClass().add("settings-label");
        HBox h = new HBox(10, l, control);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getStyleClass().add("settings-row");
        return h;
    }

    private static String mapRetentionToLabel(int days) {
        return switch (days) {
            case 0 -> "Never";
            case 1 -> "1 day";
            case 7 -> "7 days";
            case 30 -> "30 days";
            case 90 -> "90 days";
            default -> "Never";
        };
    }

    private static int mapLabelToRetention(String label) {
        return switch (label) {
            case "1 day" -> 1;
            case "7 days" -> 7;
            case "30 days" -> 30;
            case "90 days" -> 90;
            default -> 0;
        };
    }

    private static String formatShortcut(String raw) {
        ShortcutModifier s = ShortcutModifier.parse(raw);
        return s != null ? s.format() : raw;
    }

    private void applyLaunchAtLogin(boolean enabled) {
        String appPath = detectAppBundlePath();
        List<String> command = enabled && appPath != null
                ? List.of("/usr/bin/open", appPath)
                : List.of();
        LaunchAtLogin.setEnabled(enabled, command);
    }

    /**
     * When running from a packaged .app (Contents/... in the classpath) use that bundle;
     * otherwise point the login item at `./gradlew run` in this project.
     */
    private static String detectAppBundlePath() {
        String cp = System.getProperty("java.class.path", "");
        int idx = cp.indexOf(".app/Contents");
        if (idx >= 0) {
            return cp.substring(0, idx + 4);
        }
        if (cp.contains("clipboard")) {
            return cp.substring(0, Math.max(0, cp.indexOf("clipboard") + "clipboard".length()));
        }
        return Optional.ofNullable(System.getProperty("user.dir")).orElse(null);
    }
}

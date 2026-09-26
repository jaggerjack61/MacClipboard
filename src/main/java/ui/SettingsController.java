package ui;

import clipboard.ClipboardService;
import config.ApplicationSettings;
import hotkey.GlobalHotkeyService;
import hotkey.ShortcutModifier;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import javafx.animation.PauseTransition;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
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
    private final Executor background;
    private static final PseudoClass GRANTED = PseudoClass.getPseudoClass("granted");

    private final Label permissionStatus = new Label();
    private final Region permissionDot = new Region();
    private Stage stage;

    public SettingsController(ApplicationSettings settings, ClipboardService clipboardService,
                              GlobalHotkeyService hotkeys, PasteService pasteService, Executor background) {
        this.settings = settings;
        this.clipboardService = clipboardService;
        this.hotkeys = hotkeys;
        this.pasteService = pasteService;
        this.background = background;
    }

    public void show() {
        if (stage == null) {
            stage = buildStage();
        }
        Theme.apply(stage.getScene());
        refreshPermissionStatus();
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    private Stage buildStage() {
        // History size
        Spinner<Integer> maxHistory = new Spinner<>();
        maxHistory.setId("history-size");
        maxHistory.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(10, 500, settings.maxHistory(), 10));
        maxHistory.setEditable(true);
        maxHistory.setPrefWidth(96);
        maxHistory.valueProperty().addListener((o, old, v) -> {
            settings.setMaxHistory(v);
            background.execute(clipboardService::applyLimits);
        });

        // Retention
        ChoiceBox<String> retention = new ChoiceBox<>();
        retention.setId("retention");
        retention.getItems().addAll("Never", "1 day", "7 days", "30 days", "90 days");
        retention.setValue(mapRetentionToLabel(settings.retentionDays()));
        retention.setOnAction(e -> {
            settings.setRetentionDays(mapLabelToRetention(retention.getValue()));
            background.execute(clipboardService::applyRetention);
        });

        // Shortcut
        ChoiceBox<String> shortcut = new ChoiceBox<>();
        shortcut.setId("shortcut");
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
        CheckBox launch = toggle(settings.launchAtLogin());
        launch.setOnAction(e -> {
            settings.setLaunchAtLogin(launch.isSelected());
            applyLaunchAtLogin(launch.isSelected());
        });

        CheckBox autoPaste = toggle(settings.autoPaste());
        autoPaste.setOnAction(e -> settings.setAutoPaste(autoPaste.isSelected()));

        CheckBox persist = toggle(settings.persistHistory());
        persist.setOnAction(e -> settings.setPersistHistory(persist.isSelected()));

        CheckBox rememberEmoji = toggle(settings.rememberRecentEmojis());
        rememberEmoji.setOnAction(e -> settings.setRememberRecentEmojis(rememberEmoji.isSelected()));

        Spinner<Integer> emojiLimit = new Spinner<>();
        emojiLimit.setId("recent-emoji-limit");
        emojiLimit.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(4, 200,
                settings.maxRecentEmojis(), 4));
        emojiLimit.setEditable(true);
        emojiLimit.setPrefWidth(96);
        emojiLimit.valueProperty().addListener((o, old, v) -> settings.setMaxRecentEmojis(v));

        Button clear = new Button("Clear History");
        clear.getStyleClass().add("destructive");
        PauseTransition clearedReset = new PauseTransition(Duration.seconds(1.6));
        clearedReset.setOnFinished(e -> {
            clear.setText("Clear History");
            clear.setDisable(false);
        });
        clear.setOnAction(e -> {
            background.execute(clipboardService::clearUnpinned);
            clear.setText("Cleared");
            clear.setDisable(true);
            clearedReset.playFromStart();
        });

        Button grant = new Button("Open System Settings\u2026");
        grant.setOnAction(e -> MacNative.openAccessibilitySettings());
        permissionStatus.getStyleClass().add("settings-detail");
        permissionDot.getStyleClass().add("status-dot");
        HBox statusLine = new HBox(6, permissionDot, permissionStatus);
        statusLine.setAlignment(Pos.CENTER_LEFT);
        Label permissionDetail = new Label("Needed for auto-paste and the global shortcut");
        permissionDetail.getStyleClass().add("settings-detail");
        permissionDetail.setWrapText(true);
        VBox status = new VBox(2, permissionDetail, statusLine);
        refreshPermissionStatus();

        // About
        String version = SettingsController.class.getPackage().getImplementationVersion();
        Label aboutName = new Label("Clipboard History " + (version != null ? version : "(development build)"));
        aboutName.getStyleClass().add("settings-about-name");
        Label aboutAuthor = new Label("Clipboard History is free, open source software, "
                + "created by Samuel Jarai.");
        aboutAuthor.getStyleClass().add("settings-label");
        aboutAuthor.setWrapText(true);
        Hyperlink aboutRepo = new Hyperlink("github.com/jaggerjack61/MacClipboard");
        aboutRepo.setFocusTraversable(false);
        aboutRepo.setOnAction(e -> openInBrowser(ABOUT_REPO_URL));
        Label aboutLicense = new Label("Source is available under the license in the repository.");
        aboutLicense.getStyleClass().add("settings-detail");
        aboutLicense.setWrapText(true);
        VBox about = new VBox(4, aboutName, aboutAuthor, aboutRepo, aboutLicense);
        about.getStyleClass().add("settings-about");

        Label privacy = new Label("Clipboard data never leaves this Mac.");
        privacy.getStyleClass().add("settings-footnote");

        VBox box = new VBox(header(),
                sectionTitle("General"),
                card(row("Global shortcut", "Opens clipboard history from any app", shortcut),
                        row("Launch at login", launch),
                        row("Paste automatically", "Pastes into the previous app after you pick an item",
                                autoPaste),
                        row("Accessibility access", status, grant)),
                sectionTitle("History"),
                card(row("History size", "Maximum number of items kept", maxHistory),
                        row("Remove items older than", retention),
                        row("Keep history after restart", "Stored locally. Applies after restarting the app",
                                persist),
                        row("Clear history", "Removes everything except pinned items", clear)),
                privacy,
                sectionTitle("Emoji"),
                card(row("Remember recently used", rememberEmoji),
                        row("Recent emoji limit", emojiLimit)),
                sectionTitle("About"),
                card(about));
        box.getStyleClass().add("settings-root");

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("settings-scroll");

        Scene scene = new Scene(scroll, 520, 640);
        Theme.apply(scene);
        Stage st = new Stage();
        st.initModality(Modality.NONE);
        st.setTitle("Settings");
        st.setMinWidth(460);
        st.setMinHeight(360);
        st.setScene(scene);
        return st;
    }

    private void refreshPermissionStatus() {
        boolean granted = pasteService.canAutoPaste();
        permissionStatus.setText(granted ? "Granted" : "Not granted");
        permissionDot.pseudoClassStateChanged(GRANTED, granted);
    }

    private static HBox header() {
        ImageView badge = new ImageView(new Image(
                SettingsController.class.getResource("/ui/app-icon.png").toExternalForm(), 104, 104, true, true));
        // The artwork includes the macOS icon margin, so it renders larger than the text column.
        badge.setFitWidth(52);
        badge.setFitHeight(52);
        Label title = new Label("Clipboard History");
        title.getStyleClass().add("settings-title");
        Label subtitle = new Label("Settings");
        subtitle.getStyleClass().add("settings-subtitle");
        VBox text = new VBox(1, title, subtitle);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox header = new HBox(8, badge, text);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("settings-header");
        return header;
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-header");
        return label;
    }

    /** A rounded group of rows separated by hairlines. */
    private static VBox card(Node... rows) {
        VBox card = new VBox();
        card.getStyleClass().add("settings-card");
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                Region divider = new Region();
                divider.getStyleClass().add("card-divider");
                card.getChildren().add(divider);
            }
            card.getChildren().add(rows[i]);
        }
        return card;
    }

    private static HBox row(String title, Node control) {
        return row(title, (Node) null, control);
    }

    private static HBox row(String title, String detail, Node control) {
        Label detailLabel = null;
        if (detail != null) {
            detailLabel = new Label(detail);
            detailLabel.getStyleClass().add("settings-detail");
            detailLabel.setWrapText(true);
        }
        return row(title, detailLabel, control);
    }

    /** Title (and optional detail line) on the left, the control right-aligned. */
    private static HBox row(String title, Node detail, Node control) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("settings-label");
        VBox text = detail == null ? new VBox(titleLabel) : new VBox(2, titleLabel, detail);
        text.setAlignment(Pos.CENTER_LEFT);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        if (control instanceof Region region) {
            // Text wraps first; controls never truncate.
            region.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox row = new HBox(16, text, control);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        return row;
    }

    /** A CheckBox styled as a macOS switch (see .switch in clipboard.css). */
    private static CheckBox toggle(boolean selected) {
        CheckBox toggle = new CheckBox();
        toggle.getStyleClass().add("switch");
        toggle.setSelected(selected);
        return toggle;
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

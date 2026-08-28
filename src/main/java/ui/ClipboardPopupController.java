package ui;

import model.ClipboardItem;
import clipboard.ClipboardService;
import config.ApplicationSettings;
import emoji.Emoji;
import emoji.EmojiService;
import emoji.RecentEmojiService;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.function.Consumer;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import paste.PasteService;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * The floating Win+V-style popup. Hosts the Clipboard and Emoji tabs, positions
 * itself near the mouse, keeps focus above other windows, and closes on focus loss
 * or Escape.
 */
public final class ClipboardPopupController {

    public enum Tab { CLIPBOARD, EMOJI }

    private static final double WIDTH = 440;
    private static final double HEIGHT = 560;

    private final Stage stage = new Stage(StageStyle.TRANSPARENT);
    private final StackPane contentArea = new StackPane();
    private final Label clipboardTabButton = new Label("Clipboard");
    private final Label emojiTabButton = new Label("Emoji");
    private final ClipboardTabController clipboardTab;
    private final EmojiTabController emojiTab;
    private final ApplicationSettings settings;
    private final PasteService pasteService;
    private final Consumer<ClipboardItem> onClipboardSelected;
    private final Consumer<Emoji> onEmojiSelected;

    private Tab activeTab = Tab.CLIPBOARD;
    private boolean visible;
    /** Set once the user drags the popup or minimizes it; stops re-centering on the mouse. */
    private boolean positionOverridden;
    private double dragOffsetX;
    private double dragOffsetY;
    private boolean dragMoved;

    public ClipboardPopupController(ClipboardService clipboardService, EmojiService emojiService,
                                    RecentEmojiService recentEmojiService, ApplicationSettings settings,
                                    PasteService pasteService,
                                    Consumer<ClipboardItem> onClipboardSelected,
                                    Consumer<Emoji> onEmojiSelected) {
        this.settings = settings;
        this.pasteService = pasteService;
        this.onClipboardSelected = onClipboardSelected;
        this.onEmojiSelected = onEmojiSelected;
        this.clipboardTab = new ClipboardTabController(clipboardService, this);
        this.emojiTab = new EmojiTabController(emojiService, recentEmojiService, settings, this);
        buildStage();
    }

    private void buildStage() {
        clipboardTabButton.getStyleClass().add("tab-button");
        emojiTabButton.getStyleClass().add("tab-button");
        clipboardTabButton.setFocusTraversable(true);
        emojiTabButton.setFocusTraversable(true);
        clipboardTabButton.setOnMouseClicked(e -> showTab(Tab.CLIPBOARD));
        emojiTabButton.setOnMouseClicked(e -> showTab(Tab.EMOJI));
        // Left/Right on a focused tab button switches tabs.
        clipboardTabButton.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.RIGHT) {
                showTab(Tab.EMOJI);
                e.consume();
            }
        });
        emojiTabButton.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.LEFT) {
                showTab(Tab.CLIPBOARD);
                e.consume();
            }
        });

        Label hint = new Label("⌘1  ⌘2   ·   ⎋ to close");
        hint.getStyleClass().add("hint");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(18, clipboardTabButton, emojiTabButton, spacer, hint);
        header.getStyleClass().add("popup-header");

        HBox topBar = buildTopBar();

        contentArea.getChildren().addAll(clipboardTab.getNode(), emojiTab.getNode());

        VBox root = new VBox(topBar, header, contentArea);
        root.getStyleClass().add("popup-root");

        StackPane outer = new StackPane(root);
        outer.setPadding(new javafx.geometry.Insets(8));

        Scene scene = new Scene(outer, WIDTH + 16, HEIGHT + 16);
        scene.setFill(null);
        scene.getStylesheets().add(ClipboardPopupController.class.getResource("/ui/clipboard.css").toExternalForm());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE), this::hidePopup);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN),
                () -> showTab(Tab.CLIPBOARD));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.SHORTCUT_DOWN),
                () -> showTab(Tab.EMOJI));

        // Close when the user clicks outside (focus loss).
        stage.focusedProperty().addListener((obs, was, now) -> {
            if (!now && visible) {
                hidePopup();
            }
        });

        stage.setScene(scene);
        stage.setAlwaysOnTop(true);
        stage.setTitle("Clipboard History");
        updateTabStyles();
        showTab(Tab.CLIPBOARD);
    }

    public boolean isVisible() {
        return visible;
    }

    /**
     * macOS-style title bar: traffic-light close/minimize buttons and drag-to-move.
     * The popup is a TRANSPARENT stage (no native decorations), so this is the only
     * way to move or dismiss it with the mouse.
     */
    private HBox buildTopBar() {
        Label closeLight = new Label("\u00d7");
        closeLight.getStyleClass().addAll("traffic-light", "close-light");
        closeLight.setTooltip(new Tooltip("Close"));
        closeLight.setFocusTraversable(false);
        closeLight.setOnMouseReleased(e -> {
            // Only act on a click, not the end of a drag across the bar.
            if (!dragMoved) {
                positionOverridden = false; // next open follows the mouse again
                hidePopup();
            }
        });

        Label minLight = new Label("\u2212");
        minLight.getStyleClass().addAll("traffic-light", "minimize-light");
        minLight.setTooltip(new Tooltip("Minimize (keeps this position)"));
        minLight.setFocusTraversable(false);
        minLight.setOnMouseReleased(e -> {
            if (!dragMoved) {
                positionOverridden = true; // reopen where the user left it
                hidePopup();
            }
        });

        Label title = new Label("Clipboard History");
        title.getStyleClass().add("topbar-title");

        Region leftSpacer = new Region();
        Region rightSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);
        HBox bar = new HBox(8, closeLight, minLight, leftSpacer, title, rightSpacer);
        bar.getStyleClass().add("popup-topbar");

        // Drag anywhere on the bar to move the window.
        bar.setOnMousePressed(e -> {
            dragMoved = false;
            dragOffsetX = e.getScreenX() - stage.getX();
            dragOffsetY = e.getScreenY() - stage.getY();
        });
        bar.setOnMouseDragged(e -> {
            double newX = e.getScreenX() - dragOffsetX;
            double newY = e.getScreenY() - dragOffsetY;
            if (newX != stage.getX() || newY != stage.getY()) {
                dragMoved = true;
            }
            positionOverridden = true;
            Rectangle bounds = defaultScreenBounds();
            // Keep most of the bar on screen so the window cannot be lost off-edge.
            double minX = bounds.x - WIDTH + 80;
            double maxX = bounds.x + bounds.width - 80;
            double maxY = bounds.y + bounds.height - 40;
            stage.setX(Math.max(minX, Math.min(newX, maxX)));
            stage.setY(Math.max(bounds.y, Math.min(newY, maxY)));
        });
        return bar;
    }

    /** Toggle the popup; when opening, capture the previous focus owner for pasting. */
    public void toggle(Tab tab) {
        if (visible) {
            hidePopup();
        } else {
            show(tab);
        }
    }

    public void show(Tab tab) {
        pasteService.captureFocusOwner();
        if (!positionOverridden) {
            positionNearMouse();
        }
        activeTab = tab;
        showTab(tab, true);
        stage.show();
        stage.toFront();
        visible = true;
        javafx.application.Platform.runLater(() -> {
            stage.requestFocus();
            if (tab == Tab.CLIPBOARD) {
                clipboardTab.refresh();
            } else {
                emojiTab.refresh();
            }
        });
    }

    public void hidePopup() {
        visible = false;
        stage.hide();
    }

    /** Called by tab controllers when the user picks a clipboard item. */
    public void select(ClipboardItem item) {
        hidePopup();
        onClipboardSelected.accept(item);
    }

    /** Called by tab controllers when the user picks an emoji. */
    public void select(Emoji emoji) {
        hidePopup();
        onEmojiSelected.accept(emoji);
    }

    public void refreshClipboardTab() {
        clipboardTab.refresh();
    }

    public void showTab(Tab tab) {
        showTab(tab, false);
    }

    private void showTab(Tab tab, boolean forceRefresh) {
        activeTab = tab;
        updateTabStyles();
        contentArea.getChildren().remove(clipboardTab.getNode());
        contentArea.getChildren().remove(emojiTab.getNode());
        contentArea.getChildren().add(tab == Tab.CLIPBOARD ? clipboardTab.getNode() : emojiTab.getNode());
        if (tab == Tab.CLIPBOARD) {
            clipboardTab.refresh();
        } else {
            emojiTab.refresh();
        }
    }

    private void updateTabStyles() {
        clipboardTabButton.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"),
                activeTab == Tab.CLIPBOARD);
        emojiTabButton.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"),
                activeTab == Tab.EMOJI);
    }

    private void positionNearMouse() {
        Point mouse = java.awt.MouseInfo.getPointerInfo().getLocation();
        Rectangle bounds = defaultScreenBounds();
        double x = mouse.x - WIDTH / 2.0;
        double y = mouse.y + 24;
        double maxX = bounds.x + bounds.width - WIDTH - 20;
        double maxY = bounds.y + bounds.height - HEIGHT - 40;
        x = Math.max(bounds.x + 20, Math.min(x, maxX));
        y = Math.max(bounds.y + 40, Math.min(y, maxY));
        stage.setX(x);
        stage.setY(y);
    }

    private static Rectangle defaultScreenBounds() {
        try {
            GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
            Rectangle vc = env.getMaximumWindowBounds();
            return new Rectangle(vc.x, vc.y, vc.width, vc.height);
        } catch (Exception e) {
            return new Rectangle(0, 0, 1440, 900);
        }
    }
}

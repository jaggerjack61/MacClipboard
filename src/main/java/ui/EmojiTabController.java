package ui;

import config.ApplicationSettings;
import emoji.Emoji;
import emoji.EmojiCategory;
import emoji.EmojiService;
import emoji.RecentEmojiService;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The Emoji tab: search, recently used row, and categorized scrollable grids with
 * keyboard navigation. Reuses the same popup window as the Clipboard tab.
 */
public final class EmojiTabController {

    private static final int COLUMNS = 9;
    private static final int MAX_SEARCH_RESULTS = 250;

    private final VBox root = new VBox();
    private final TextField searchField = new TextField();
    private final ScrollPane scrollPane = new ScrollPane();
    private final VBox content = new VBox(6);
    private final EmojiService emojiService;
    private final RecentEmojiService recentService;
    private final ApplicationSettings settings;
    private final ClipboardPopupController popup;

    /** Flattened focus order of currently visible emoji cells for arrow navigation. */
    private final List<Label> focusOrder = new ArrayList<>();
    private String lastFilter = "";

    public EmojiTabController(EmojiService emojiService, RecentEmojiService recentService,
                              ApplicationSettings settings, ClipboardPopupController popup) {
        this.emojiService = emojiService;
        this.recentService = recentService;
        this.settings = settings;
        this.popup = popup;
        buildUi();
    }

    public Node getNode() {
        return root;
    }

    public void refresh() {
        rebuild();
        if (searchField.getText().isBlank()) {
            focusFirstCell();
        }
    }

    /** Call on popup show to ensure the grid is up to date without stealing search focus. */
    public void refreshSilently() {
        rebuild();
    }

    private void buildUi() {
        searchField.getStyleClass().add("search-field");
        searchField.setPromptText("Search emoji...");
        HBox searchBox = new HBox(searchField);
        searchBox.getStyleClass().add("search-box");

        scrollPane.getStyleClass().add("emoji-scroll");
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        content.setPadding(new Insets(4, 10, 8, 10));
        scrollPane.setContent(content);

        searchField.textProperty().addListener((obs, old, q) -> {
            if (q == null) {
                return;
            }
            if (!q.strip().equals(lastFilter)) {
                rebuild();
            }
        });
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN) {
                focusFirstCell();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE && !searchField.getText().isEmpty()) {
                searchField.clear();
                rebuild();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                List<Emoji> first = currentFlatResult();
                if (!first.isEmpty()) {
                    popup.select(first.get(0));
                }
                e.consume();
            }
        });

        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        root.getChildren().addAll(searchBox, scrollPane);
    }

    private List<Emoji> currentFlatResult() {
        if (!searchField.getText().isBlank()) {
            return emojiService.search(searchField.getText());
        }
        List<Emoji> firstCategory = emojiService.byCategory(EmojiCategory.SMILEYS);
        return firstCategory;
    }

    private void rebuild() {
        lastFilter = searchField.getText().strip();
        content.getChildren().clear();
        focusOrder.clear();
        if (searchField.getText().isBlank()) {
            buildBrowseView();
        } else {
            buildSearchView();
        }
    }

    private void buildBrowseView() {
        List<String> recent = recentService.recentCharacters(Math.min(18, settings.maxRecentEmojis()));
        if (!recent.isEmpty()) {
            addSection(EmojiCategory.RECENTLY_USED.label(), recent.stream()
                    .map(this::emojiForCharacter)
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::get)
                    .toList());
        }
        for (EmojiCategory category : EmojiCategory.values()) {
            if (category == EmojiCategory.RECENTLY_USED) {
                continue;
            }
            List<Emoji> emojis = emojiService.byCategory(category);
            if (!emojis.isEmpty()) {
                addSection(category.icon() + "  " + category.label(), emojis);
            }
        }
    }

    private void buildSearchView() {
        List<Emoji> results = emojiService.search(searchField.getText());
        if (results.isEmpty()) {
            Label none = new Label("No emoji matches \"" + searchField.getText() + "\"");
            none.getStyleClass().add("hint");
            content.getChildren().add(none);
            return;
        }
        addSection("Results", results.stream().limit(MAX_SEARCH_RESULTS).toList());
    }

    private void addSection(String title, List<Emoji> emojis) {
        Label header = new Label(title);
        header.getStyleClass().add("emoji-category-label");
        FlowPane grid = new FlowPane();
        grid.getStyleClass().add("emoji-flow");
        grid.setPrefWrapLength(COLUMNS * 42);
        Region spacer = new Region();
        HBox headerRow = new HBox(header, spacer);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        content.getChildren().add(headerRow);
        for (Emoji emoji : emojis) {
            Label cell = makeCell(emoji);
            grid.getChildren().add(cell);
        }
        content.getChildren().add(grid);
    }

    private Label makeCell(Emoji emoji) {
        Label cell = new Label(emoji.character());
        cell.getStyleClass().add("emoji-cell");
        cell.setFocusTraversable(true);
        cell.setTooltip(new javafx.scene.control.Tooltip(emoji.name()));
        cell.setOnMouseClicked(e -> popup.select(emoji));
        cell.setOnKeyPressed(e -> handleGridKey(e, cell));
        focusOrder.add(cell);
        return cell;
    }

    private void handleGridKey(javafx.scene.input.KeyEvent e, Label cell) {
        int idx = focusOrder.indexOf(cell);
        if (idx < 0) {
            return;
        }
        switch (e.getCode()) {
            case LEFT -> moveFocus(idx - 1);
            case RIGHT -> moveFocus(idx + 1);
            case UP -> moveFocus(idx - COLUMNS);
            case DOWN -> moveFocus(idx + COLUMNS);
            case ENTER, SPACE -> {
                Emoji emoji = emojiForLabel(cell);
                if (emoji != null) {
                    popup.select(emoji);
                }
            }
            case ESCAPE -> popup.hidePopup();
            default -> {
                if (isPrintable(e)) {
                    searchField.requestFocus();
                    searchField.appendText(e.getText());
                    e.consume();
                    return;
                }
                return;
            }
        }
        e.consume();
    }

    private void moveFocus(int target) {
        if (target >= 0 && target < focusOrder.size()) {
            focusOrder.get(target).requestFocus();
        }
    }

    private void focusFirstCell() {
        if (!focusOrder.isEmpty()) {
            focusOrder.get(0).requestFocus();
        }
    }

    private static boolean isPrintable(javafx.scene.input.KeyEvent e) {
        String text = e.getText();
        return text != null && text.length() == 1 && Character.isLetterOrDigit(text.charAt(0));
    }

    private Emoji emojiForLabel(Label cell) {
        String character = cell.getText();
        return emojiForCharacter(character).orElse(null);
    }

    private java.util.Optional<Emoji> emojiForCharacter(String character) {
        return emojiService.repository().all().stream()
                .filter(e -> e.character().equals(character))
                .findFirst();
    }
}

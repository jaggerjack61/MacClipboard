package ui;

import config.ApplicationSettings;
import emoji.Emoji;
import emoji.EmojiCategory;
import emoji.EmojiService;
import emoji.RecentEmojiService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The Emoji tab: search, recently used row, and categorized scrollable grids with
 * keyboard navigation. Reuses the same popup window as the Clipboard tab.
 *
 * The grid is fully virtualized: the ListView items are section headers and rows of
 * {@link #COLUMNS} emojis, so list cells (and their emoji labels) are created only
 * for what is visible and recycled while scrolling — instead of one live node and
 * Tooltip per emoji in the whole dataset. Keyboard focus is controller-managed
 * (row + column state with an :active pseudo-class) rather than real node focus,
 * which stays correct across cell reuse. The hovered/focused emoji name is shown
 * in one shared hint bar.
 */
public final class EmojiTabController {

    private static final int COLUMNS = 9;
    private static final int MAX_SEARCH_RESULTS = 250;
    private static final int CELL_SIZE = 40;
    private static final int CELL_GAP = 2;
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    /** A section title ("😀  Smileys & Emotion"). */
    private record Header(String title) { }

    /** One grid row of up to {@link #COLUMNS} emojis. */
    private record Row(List<Emoji> emojis) { }

    private final VBox root = new VBox();
    private final TextField searchField = new TextField();
    private final ListView<Object> listView = new ListView<>();
    private final Label hintLabel = new Label();
    private final EmojiService emojiService;
    private final RecentEmojiService recentService;
    private final ApplicationSettings settings;
    private final ClipboardPopupController popup;

    /** O(1) lookup of emoji by display character (used for the recents row). */
    private final Map<String, Emoji> byCharacter = new HashMap<>();
    private final ObservableList<Object> items = FXCollections.observableArrayList();
    /** Indices into {@link #items} that are {@link Row}s, in display order. */
    private final List<Integer> rowIndices = new ArrayList<>();
    private String lastFilter = "";
    private String lastRecentsKey = null;
    /** Controller-managed grid focus: row indexes {@link #rowIndices}, -1 = none. */
    private int focusRow = -1;
    private int focusCol = 0;

    public EmojiTabController(EmojiService emojiService, RecentEmojiService recentService,
                              ApplicationSettings settings, ClipboardPopupController popup) {
        this.emojiService = emojiService;
        this.recentService = recentService;
        this.settings = settings;
        this.popup = popup;
        for (Emoji emoji : emojiService.repository().all()) {
            byCharacter.putIfAbsent(emoji.character(), emoji);
        }
        buildUi();
    }

    public Node getNode() {
        return root;
    }

    /** Called each time the popup shows / the tab is activated. */
    public void refresh() {
        rebuildIfStale();
        if (searchField.getText().isBlank() && focusRow < 0) {
            focusFirstCell();
        }
    }

    /** Call on popup show to ensure the grid is up to date without stealing search focus. */
    public void refreshSilently() {
        rebuildIfStale();
    }

    private void buildUi() {
        searchField.getStyleClass().add("search-field");
        searchField.setPromptText("Search emoji...");
        HBox searchBox = new HBox(searchField);
        searchBox.getStyleClass().add("search-box");

        listView.getStyleClass().add("emoji-list");
        listView.setItems(items);
        listView.setCellFactory(lv -> new EmojiCell());
        listView.setFocusTraversable(true);
        hintLabel.getStyleClass().add("hint");
        hintLabel.setMaxWidth(Double.MAX_VALUE);
        hintLabel.setAlignment(Pos.CENTER);
        hintLabel.setMinHeight(22);
        hintLabel.setPadding(new Insets(0, 12, 4, 12));

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
                listView.requestFocus();
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

        // Filter (not handler) so ListView's built-in key behavior never fights us.
        listView.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case LEFT -> {
                    moveFocus(0, -1);
                    e.consume();
                }
                case RIGHT -> {
                    moveFocus(0, 1);
                    e.consume();
                }
                case UP -> {
                    moveFocus(-1, 0);
                    e.consume();
                }
                case DOWN -> {
                    moveFocus(1, 0);
                    e.consume();
                }
                case ENTER, SPACE -> {
                    selectFocused();
                    e.consume();
                }
                case ESCAPE -> {
                    popup.hidePopup();
                    e.consume();
                }
                default -> {
                    if (isPrintable(e)) {
                        searchField.requestFocus();
                        searchField.appendText(e.getText());
                        e.consume();
                    }
                }
            }
        });
        listView.setOnMousePressed(e -> listView.requestFocus());

        VBox.setVgrow(listView, Priority.ALWAYS);
        root.getChildren().addAll(searchBox, listView, hintLabel);
    }

    private List<Emoji> recentEmojis() {
        int limit = Math.min(18, settings.maxRecentEmojis());
        return recentService.recentCharacters(limit).stream()
                .map(byCharacter::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /** Rebuilds only when the filter changed or the recents section changed. */
    private void rebuildIfStale() {
        String filter = searchField.getText().strip();
        if (!filter.equals(lastFilter)) {
            rebuild();
            return;
        }
        if (!filter.isEmpty()) {
            return;
        }
        String key = String.join("", recentEmojis().stream().map(Emoji::character).toList());
        if (!key.equals(lastRecentsKey)) {
            rebuild();
        }
    }

    private void rebuild() {
        lastFilter = searchField.getText().strip();
        lastRecentsKey = null;
        focusRow = -1;
        hintLabel.setText(null);
        rowIndices.clear();
        List<Object> newItems = new ArrayList<>();
        if (lastFilter.isEmpty()) {
            List<Emoji> recents = recentEmojis();
            lastRecentsKey = String.join("", recents.stream().map(Emoji::character).toList());
            if (!recents.isEmpty()) {
                addSection(newItems, EmojiCategory.RECENTLY_USED.label(), recents);
            }
            for (EmojiCategory category : EmojiCategory.values()) {
                if (category == EmojiCategory.RECENTLY_USED) {
                    continue;
                }
                List<Emoji> emojis = emojiService.byCategory(category);
                if (!emojis.isEmpty()) {
                    addSection(newItems, category.icon() + "  " + category.label(), emojis);
                }
            }
        } else {
            List<Emoji> results = emojiService.search(lastFilter);
            if (results.isEmpty()) {
                newItems.add(new Header("No emoji matches \"" + lastFilter + "\""));
            } else {
                addSection(newItems, "Results", results.stream().limit(MAX_SEARCH_RESULTS).toList());
            }
        }
        items.setAll(newItems);
    }

    private void addSection(List<Object> out, String title, List<Emoji> emojis) {
        out.add(new Header(title));
        for (int i = 0; i < emojis.size(); i += COLUMNS) {
            rowIndices.add(out.size());
            out.add(new Row(emojis.subList(i, Math.min(i + COLUMNS, emojis.size()))));
        }
    }

    private List<Emoji> currentFlatResult() {
        if (!searchField.getText().isBlank()) {
            return emojiService.search(searchField.getText());
        }
        return emojiService.byCategory(EmojiCategory.SMILEYS);
    }

    private void focusFirstCell() {
        if (rowIndices.isEmpty()) {
            return;
        }
        focusCell(Math.max(focusRow, 0), focusCol);
    }

    /** Sets the grid focus to (row, col), scrolls it into view and updates visuals. */
    private void focusCell(int row, int col) {
        if (rowIndices.isEmpty()) {
            return;
        }
        focusRow = Math.max(0, Math.min(row, rowIndices.size() - 1));
        focusCol = Math.max(0, Math.min(col, COLUMNS - 1));
        Row focused = rowAt(focusRow);
        if (focused != null && focusCol >= focused.emojis().size()) {
            focusCol = focused.emojis().size() - 1;
        }
        listView.scrollTo(rowIndices.get(focusRow));
        refreshActiveVisuals();
        showFocusedName();
    }

    private void moveFocus(int deltaRow, int deltaCol) {
        if (rowIndices.isEmpty()) {
            return;
        }
        if (focusRow < 0) {
            focusCell(0, 0);
            return;
        }
        int col = focusCol + deltaCol;
        int row = focusRow + deltaRow;
        if (col < 0) {
            col = COLUMNS - 1;
            row--;
        } else if (col >= COLUMNS) {
            col = 0;
            row++;
        }
        focusCell(row, col);
    }

    private void selectFocused() {
        Emoji emoji = focusedEmoji();
        if (emoji != null) {
            popup.select(emoji);
        }
    }

    private Row rowAt(int row) {
        int itemIndex = rowIndices.get(row);
        return items.get(itemIndex) instanceof Row r ? r : null;
    }

    private Emoji focusedEmoji() {
        if (focusRow < 0 || focusRow >= rowIndices.size()) {
            return null;
        }
        Row row = rowAt(focusRow);
        if (row == null || focusCol >= row.emojis().size()) {
            return null;
        }
        return row.emojis().get(focusCol);
    }

    private void showFocusedName() {
        Emoji emoji = focusedEmoji();
        hintLabel.setText(emoji == null ? null : emoji.name());
    }

    private void refreshActiveVisuals() {
        int focusedItemIndex = focusRow >= 0 && focusRow < rowIndices.size()
                ? rowIndices.get(focusRow) : -1;
        for (Node node : listView.lookupAll(".emoji-cell")) {
            if (!node.isVisible()) {
                continue;
            }
            int[] position = (int[]) node.getUserData();
            boolean active = position != null
                    && position[0] == focusedItemIndex && position[1] == focusCol;
            node.pseudoClassStateChanged(ACTIVE, active);
        }
    }

    private static boolean isPrintable(KeyEvent e) {
        if (e.isShortcutDown() || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
            return false;
        }
        String text = e.getText();
        return text != null && text.length() == 1 && Character.isLetterOrDigit(text.charAt(0));
    }

    /**
     * Recycled cell: renders either a section header or one row of emoji labels.
     * Only the labels of realized cells exist in the scene graph.
     */
    private final class EmojiCell extends ListCell<Object> {

        private final HBox rowBox = new HBox(CELL_GAP);
        private final List<Label> labels = new ArrayList<>(COLUMNS);

        EmojiCell() {
            rowBox.getStyleClass().add("emoji-row");
            rowBox.setAlignment(Pos.CENTER);
            for (int i = 0; i < COLUMNS; i++) {
                Label label = new Label();
                label.getStyleClass().add("emoji-cell");
                label.setMinSize(CELL_SIZE, CELL_SIZE);
                label.setPrefSize(CELL_SIZE, CELL_SIZE);
                label.setMaxSize(CELL_SIZE, CELL_SIZE);
                label.setAlignment(Pos.CENTER);
                labels.add(label);
                rowBox.getChildren().add(label);
            }
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove("emoji-header-cell");
            getStyleClass().remove("emoji-row-cell");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (item instanceof Header header) {
                getStyleClass().add("emoji-header-cell");
                setText(header.title());
                setGraphic(null);
                return;
            }
            getStyleClass().add("emoji-row-cell");
            setText(null);
            setGraphic(rowBox);
            Row row = (Row) item;
            int itemIndex = getIndex();
            boolean rowFocused = focusRow >= 0 && focusRow < rowIndices.size()
                    && rowIndices.get(focusRow) == itemIndex;
            for (int i = 0; i < COLUMNS; i++) {
                Label label = labels.get(i);
                if (i < row.emojis().size()) {
                    Emoji emoji = row.emojis().get(i);
                    label.setText(emoji.character());
                    label.setUserData(new int[] {itemIndex, i});
                    label.setOnMouseClicked(e -> popup.select(emoji));
                    label.setOnMouseEntered(e -> hintLabel.setText(emoji.name()));
                    label.pseudoClassStateChanged(ACTIVE, rowFocused && i == focusCol);
                    label.setVisible(true);
                    label.setManaged(true);
                } else {
                    label.setText(null);
                    label.setUserData(null);
                    label.setOnMouseClicked(null);
                    label.setOnMouseEntered(null);
                    label.pseudoClassStateChanged(ACTIVE, false);
                    label.setVisible(false);
                    label.setManaged(false);
                }
            }
        }
    }
}

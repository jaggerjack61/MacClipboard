package ui;

import clipboard.ClipboardService;
import java.io.ByteArrayInputStream;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import model.ClipboardItem;

/**
 * The Clipboard tab: search field + history list with pin/delete affordances,
 * keyboard navigation and compact macOS-styled rows.
 */
public final class ClipboardTabController {

    /** Max characters rendered in a cell (keeps ListView recycling fast for long entries). */
    private static final int CELL_RENDER_LIMIT = 400;

    private final VBox root = new VBox();
    private final TextField searchField = new TextField();
    private final ListView<ClipboardItem> listView = new ListView<>();
    private final Label emptyLabel = new Label("Nothing copied yet");
    private final ClipboardService service;
    private final ClipboardPopupController popup;

    public ClipboardTabController(ClipboardService service, ClipboardPopupController popup) {
        this.service = service;
        this.popup = popup;
        buildUi();
    }

    public Node getNode() {
        return root;
    }

    /** Called each time the popup shows / the tab is activated. */
    public void refresh() {
        reload();
        listView.requestFocus();
    }

    private void buildUi() {
        root.getStyleClass().add("clipboard-tab");
        searchField.getStyleClass().add("search-field");
        searchField.setPromptText("Search clipboard...");
        HBox searchBox = new HBox(searchField);
        searchBox.getStyleClass().add("search-box");

        listView.getStyleClass().add("clipboard-list");
        listView.setFocusTraversable(true);
        listView.setCellFactory(lv -> new ItemCell());
        listView.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.SINGLE);
        emptyLabel.getStyleClass().add("hint");
        emptyLabel.setMaxWidth(Double.MAX_VALUE);
        emptyLabel.setAlignment(Pos.CENTER);
        emptyLabel.setVisible(false);
        listView.setPlaceholder(emptyLabel);

        searchField.textProperty().addListener((obs, old, q) -> reload());
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN) {
                listView.requestFocus();
                selectFirst();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                selectFirst();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                if (!searchField.getText().isEmpty()) {
                    searchField.clear();
                    e.consume();
                }
            }
        });

        listView.setOnKeyPressed(e -> {
            ClipboardItem focused = listView.getFocusModel().getFocusedItem();
            if (e.getCode() == KeyCode.ENTER && focused != null) {
                popup.select(focused);
                e.consume();
            } else if (e.getCode() == KeyCode.DELETE && focused != null) {
                remove(focused);
                e.consume();
            } else if (new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN).match(e)) {
                service.togglePin(focused.id());
                reload();
                e.consume();
            } else if (e.getCode() == KeyCode.UP && listView.getFocusModel().getFocusedIndex() <= 0) {
                searchField.requestFocus();
                e.consume();
            }
        });
        listView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                ClipboardItem item = listView.getSelectionModel().getSelectedItem();
                if (item != null) {
                    popup.select(item);
                }
            }
        });

        VBox.setVgrow(listView, Priority.ALWAYS);
        root.getChildren().addAll(searchBox, listView);
    }

    private void selectFirst() {
        if (!listView.getItems().isEmpty()) {
            listView.getSelectionModel().select(0);
            listView.getFocusModel().focus(0);
            listView.scrollTo(0);
        }
    }

    private void reload() {
        var items = FXCollections.observableArrayList(service.history(searchField.getText()));
        listView.setItems(items);
        emptyLabel.setVisible(items.isEmpty() && searchField.getText().isBlank());
    }

    private void remove(ClipboardItem item) {
        service.delete(item.id());
        reload();
    }

    /**
     * Custom cell: preview + meta line on the left, pin/delete controls on the right.
     */
    private final class ItemCell extends ListCell<ClipboardItem> {

        private final Label previewLabel = new Label();
        private final Label metaLabel = new Label();
        private final Button pinButton = new Button("\uD83D\uDCCC");
        private final Button deleteButton = new Button("\u2715");
        private final ImageView thumbView = new ImageView();
        private final HBox row;

        ItemCell() {
            previewLabel.getStyleClass().add("item-preview");
            previewLabel.setWrapText(false);
            previewLabel.setMinWidth(0);
            // Fixed popup width: cap the label so pin/delete buttons always fit.
            previewLabel.setMaxWidth(265);
            metaLabel.getStyleClass().add("item-meta");
            metaLabel.setMaxWidth(265);

            pinButton.getStyleClass().addAll("icon-button", "pin-button");
            pinButton.setFocusTraversable(false);
            deleteButton.getStyleClass().add("icon-button");
            deleteButton.setFocusTraversable(false);

            thumbView.setFitWidth(44);
            thumbView.setFitHeight(44);
            thumbView.setPreserveRatio(true);
            thumbView.setSmooth(true);
            thumbView.getStyleClass().add("thumb");

            VBox textCol = new VBox(2, previewLabel, metaLabel);
            textCol.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(textCol, Priority.ALWAYS);
            textCol.setMaxWidth(Double.MAX_VALUE);
            textCol.setMinWidth(0);

            HBox actions = new HBox(pinButton, deleteButton);
            actions.getStyleClass().add("item-actions");

            row = new HBox(10, thumbView, textCol, actions);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("clipboard-item");
            row.prefWidthProperty().bind(widthProperty().subtract(2));
            actions.setMinWidth(Region.USE_PREF_SIZE);
            selectedProperty().addListener((obs, old, sel) ->
                    row.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), sel));

            pinButton.setOnAction(e -> {
                ClipboardItem item = getItem();
                if (item != null) {
                    service.togglePin(item.id());
                    popup.refreshClipboardTab();
                }
            });
            deleteButton.setOnAction(e -> {
                ClipboardItem item = getItem();
                if (item != null) {
                    remove(item);
                }
            });

            setPadding(Insets.EMPTY);
        }

        @Override
        protected void updateItem(ClipboardItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                getStyleClass().remove("pinned");
                return;
            }
            getStyleClass().removeAll("pinned");
            if (item.pinned()) {
                getStyleClass().add("pinned");
            }
            // macOS renders the emoji glyph with its own colors, so -fx-text-fill cannot
            // signal pin state; opacity does. Pinned = solid, unpinned = dim.
            boolean isPinned = item.pinned();
            pinButton.pseudoClassStateChanged(
                    javafx.css.PseudoClass.getPseudoClass("on"), isPinned);
            pinButton.setTooltip(new Tooltip(isPinned ? "Unpin" : "Pin"));
            previewLabel.setText(truncate(item.preview() == null ? item.textContent() : item.preview()));
            metaLabel.setText(item.contentType().label() + " \u00b7 " + RelativeTime.format(item.timestamp()));
            if (item.hasImage() && item.thumbnail() != null) {
                thumbView.setImage(new Image(new ByteArrayInputStream(item.thumbnail())));
                thumbView.setVisible(true);
                thumbView.setManaged(true);
            } else {
                thumbView.setImage(null);
                thumbView.setVisible(false);
                thumbView.setManaged(false);
            }
            row.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), isSelected());
            setGraphic(row);
        }

        private String truncate(String value) {
            if (value == null) {
                return "";
            }
            String flat = value.strip().replaceAll("\\s*\n\\s*", " \u00b7 ");
            return flat.length() <= CELL_RENDER_LIMIT ? flat : flat.substring(0, CELL_RENDER_LIMIT) + "\u2026";
        }
    }
}

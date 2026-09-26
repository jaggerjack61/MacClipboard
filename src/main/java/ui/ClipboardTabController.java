package ui;

import clipboard.ClipboardService;
import java.io.ByteArrayInputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;
import javafx.collections.FXCollections;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import model.ClipboardCollection;
import model.ClipboardContentType;
import model.ClipboardPreview;
import model.HistoryFilter;

/**
 * The Clipboard tab: search field + history list with pin/delete affordances,
 * keyboard navigation and compact macOS-styled rows.
 */
public final class ClipboardTabController {

    /** Max characters rendered in a cell (keeps ListView recycling fast for long entries). */
    private static final int CELL_RENDER_LIMIT = 400;

    private final VBox root = new VBox();
    private final TextField searchField = new TextField();
    private final ListView<ClipboardPreview> listView = new ListView<>();
    private final Label emptyLabel = new Label("Nothing copied yet");
    private final Label emptyDetail = new Label();
    private final VBox emptyState = new VBox(8);
    private final ClipboardService service;
    private final ClipboardPopupController popup;
    private final Executor background;
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(150));
    private final CollectionBar collectionBar;
    private Map<Long, String> collectionNames = Map.of();
    private long reloadVersion;

    /** One background load: the list for the current filter plus the collections. */
    private record Loaded(List<ClipboardPreview> items, List<ClipboardCollection> collections) { }

    public ClipboardTabController(ClipboardService service, ClipboardPopupController popup, Executor background) {
        this.service = service;
        this.popup = popup;
        this.background = background;
        this.collectionBar = new CollectionBar(
                filter -> reload(),
                this::createCollection,
                (id, name) -> mutateHistory(() -> service.renameCollection(id, name)),
                id -> mutateHistory(() -> service.deleteCollection(id)));
        buildUi();
    }

    public Node getNode() {
        return root;
    }

    /** Called each time the popup shows / the tab is activated. */
    public void refresh() {
        reload();
        focus();
    }

    public void focus() {
        listView.requestFocus();
    }

    /** Each time the popup opens it starts on the full history. */
    public void resetFilter() {
        collectionBar.reset();
    }

    private void buildUi() {
        root.getStyleClass().add("clipboard-tab");
        searchField.setPromptText("Search clipboard");
        HBox searchBox = Controls.searchBox(searchField);

        listView.getStyleClass().add("clipboard-list");
        listView.setFocusTraversable(true);
        listView.setCellFactory(lv -> new ItemCell());
        listView.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.SINGLE);
        emptyLabel.getStyleClass().add("empty-title");
        emptyDetail.getStyleClass().add("empty-detail");
        emptyDetail.setWrapText(true);
        emptyState.getChildren().addAll(Icons.icon(Icons.CLIPBOARD, 34, "empty-icon"), emptyLabel, emptyDetail);
        emptyState.getStyleClass().add("empty-state");
        emptyState.setAlignment(Pos.CENTER);
        emptyState.setVisible(false);
        listView.setPlaceholder(emptyState);

        searchDelay.setOnFinished(e -> loadHistory(reloadVersion, searchField.getText()));
        searchField.textProperty().addListener((obs, old, q) -> {
            reloadVersion++;
            searchDelay.playFromStart();
        });
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
            ClipboardPreview focused = listView.getFocusModel().getFocusedItem();
            if (e.getCode() == KeyCode.ENTER && focused != null) {
                popup.select(focused);
                e.consume();
            } else if ((e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) && focused != null) {
                remove(focused);
                e.consume();
            } else if (focused != null
                    && new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN).match(e)) {
                togglePin(focused);
                e.consume();
            } else if (e.getCode() == KeyCode.UP && listView.getFocusModel().getFocusedIndex() <= 0) {
                searchField.requestFocus();
                e.consume();
            }
        });
        listView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                ClipboardPreview item = listView.getSelectionModel().getSelectedItem();
                if (item != null) {
                    popup.select(item);
                }
            }
        });

        HBox footer = new HBox(14,
                Controls.keyHint("\u21a9", "Paste"),
                Controls.keyHint("\u2318P", "Pin"),
                Controls.keyHint("\u232b", "Delete"),
                Controls.hSpacer(),
                Controls.keyHint("esc", "Close"));
        footer.getStyleClass().add("popup-footer");

        VBox.setVgrow(listView, Priority.ALWAYS);
        root.getChildren().addAll(searchBox, collectionBar.getNode(), listView, footer);
    }

    private void selectFirst() {
        if (!listView.getItems().isEmpty()) {
            listView.getSelectionModel().select(0);
            listView.getFocusModel().focus(0);
            listView.scrollTo(0);
        }
    }

    private void reload() {
        searchDelay.stop();
        loadHistory(++reloadVersion, searchField.getText());
    }

    private void loadHistory(long version, String query) {
        HistoryFilter filter = collectionBar.filter();
        CompletableFuture.supplyAsync(() -> new Loaded(service.previews(query, filter), service.collections()),
                        background)
                .whenComplete((loaded, error) -> Platform.runLater(() -> {
                    // Slow responses must never replace results for a newer query.
                    if (version != reloadVersion) {
                        return;
                    }
                    if (error != null) {
                        listView.getItems().clear();
                        emptyLabel.setText("Could not load clipboard history");
                        emptyDetail.setText("Try reopening the window.");
                        emptyState.setVisible(true);
                        return;
                    }
                    collectionNames = loaded.collections().stream()
                            .collect(Collectors.toMap(ClipboardCollection::id, ClipboardCollection::name));
                    collectionBar.setCollections(loaded.collections());
                    listView.setItems(FXCollections.observableArrayList(loaded.items()));
                    describeEmpty(filter, query);
                    emptyState.setVisible(loaded.items().isEmpty());
                }));
    }

    private void describeEmpty(HistoryFilter filter, String query) {
        String scope = switch (filter.kind()) {
            case ALL -> "your history";
            case PINNED -> "your pinned items";
            case COLLECTION -> "\u201c" + collectionNames.getOrDefault(filter.collectionId(), "") + "\u201d";
        };
        if (!query.isBlank()) {
            emptyLabel.setText("No matches");
            emptyDetail.setText("Nothing in " + scope + " matches \u201c" + query.strip() + "\u201d.");
            return;
        }
        switch (filter.kind()) {
            case ALL -> {
                emptyLabel.setText("Nothing copied yet");
                emptyDetail.setText("Copy text or images and they will appear here.");
            }
            case PINNED -> {
                emptyLabel.setText("No pinned items");
                emptyDetail.setText("Pin an item with \u2318P or its pin button to keep it.");
            }
            case COLLECTION -> {
                emptyLabel.setText(scope + " is empty");
                emptyDetail.setText("Right-click an item, or use its folder button, to add it here.");
            }
        }
    }

    private void createCollection(String name, Consumer<ClipboardCollection> then) {
        reloadVersion++;
        CompletableFuture.supplyAsync(() -> service.createCollection(name), background)
                .whenComplete((created, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        System.getLogger(ClipboardTabController.class.getName())
                                .log(System.Logger.Level.WARNING, "Could not create collection");
                    } else {
                        then.accept(created);
                    }
                    reload();
                }));
    }

    private void moveTo(ClipboardPreview item, Long collectionId) {
        mutateHistory(() -> service.moveToCollection(item.id(), collectionId));
    }

    /** Check items for each collection (the item's own is checked) plus "New Collection…". */
    private List<MenuItem> collectionMenuItems(ClipboardPreview item) {
        List<MenuItem> items = new ArrayList<>();
        for (ClipboardCollection collection : collectionBar.collections()) {
            CheckMenuItem entry = new CheckMenuItem(collection.name());
            entry.setMnemonicParsing(false);
            entry.setSelected(Long.valueOf(collection.id()).equals(item.collectionId()));
            // Unchecking keeps the item pinned, just outside any collection.
            entry.setOnAction(e -> moveTo(item, entry.isSelected() ? collection.id() : null));
            items.add(entry);
        }
        if (!items.isEmpty()) {
            items.add(new SeparatorMenuItem());
        }
        MenuItem create = new MenuItem("New Collection\u2026");
        create.setOnAction(e -> collectionBar.beginCreate(created -> moveTo(item, created.id())));
        items.add(create);
        return items;
    }

    private ContextMenu rowMenu(ClipboardPreview item) {
        MenuItem paste = new MenuItem("Paste");
        paste.setOnAction(e -> popup.select(item));
        MenuItem pin = new MenuItem(item.pinned() ? "Unpin" : "Pin");
        pin.setOnAction(e -> togglePin(item));
        Menu collections = new Menu("Add to Collection");
        collections.getItems().setAll(collectionMenuItems(item));
        MenuItem delete = new MenuItem("Delete");
        delete.setOnAction(e -> remove(item));
        return new ContextMenu(paste, pin, collections, new SeparatorMenuItem(), delete);
    }

    private void mutateHistory(Runnable action) {
        reloadVersion++;
        searchDelay.stop();
        CompletableFuture.runAsync(action, background).whenComplete((unused, error) ->
                Platform.runLater(() -> {
                    if (error != null) {
                        System.getLogger(ClipboardTabController.class.getName())
                                .log(System.Logger.Level.WARNING, "History update failed");
                    }
                    reload();
                }));
    }

    private void remove(ClipboardPreview item) {
        mutateHistory(() -> service.delete(item.id()));
    }

    private void togglePin(ClipboardPreview item) {
        mutateHistory(() -> service.togglePin(item.id()));
    }

    /**
     * Custom cell: type badge (or image thumbnail), preview + meta line, and pin/delete
     * controls that appear on hover or selection. Pinned rows keep their pin visible.
     */
    private final class ItemCell extends ListCell<ClipboardPreview> {

        private static final double LEADING = 34;
        private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
        private static final PseudoClass PINNED = PseudoClass.getPseudoClass("pinned");

        private final Label previewLabel = new Label();
        private final Label metaLabel = new Label();
        private final Icons.Icon typeIcon = Icons.icon(Icons.TEXT, 17, "type-icon");
        private final StackPane badge = new StackPane(typeIcon);
        private final ImageView thumbView = new ImageView();
        private final StackPane leading = new StackPane(badge, thumbView);
        private final Icons.Icon pinIcon = Icons.icon(Icons.PIN, 16);
        private final Button pinButton = new Button();
        private final Button deleteButton = new Button();
        private final Button collectionButton = new Button();
        private final HBox row;

        ItemCell() {
            previewLabel.getStyleClass().add("item-preview");
            previewLabel.setWrapText(false);
            previewLabel.setMinWidth(0);
            previewLabel.setMaxWidth(Double.MAX_VALUE);
            metaLabel.getStyleClass().add("item-meta");
            metaLabel.setMinWidth(0);

            badge.getStyleClass().add("type-badge");
            badge.setMaxSize(LEADING, LEADING);
            thumbView.setFitWidth(LEADING);
            thumbView.setFitHeight(LEADING);
            thumbView.setSmooth(true);
            Rectangle thumbClip = new Rectangle(LEADING, LEADING);
            thumbClip.setArcWidth(14);
            thumbClip.setArcHeight(14);
            thumbView.setClip(thumbClip);
            leading.setMinSize(LEADING, LEADING);
            leading.setMaxSize(LEADING, LEADING);

            pinButton.setGraphic(pinIcon);
            pinButton.getStyleClass().addAll("icon-button", "pin-button");
            pinButton.setFocusTraversable(false);
            deleteButton.setGraphic(Icons.icon(Icons.TRASH, 16));
            deleteButton.getStyleClass().addAll("icon-button", "delete-button");
            deleteButton.setTooltip(new Tooltip("Delete"));
            deleteButton.setFocusTraversable(false);
            collectionButton.setGraphic(Icons.icon(Icons.FOLDER_PLUS, 16));
            collectionButton.getStyleClass().addAll("icon-button", "collection-button");
            collectionButton.setTooltip(new Tooltip("Add to collection"));
            collectionButton.setFocusTraversable(false);

            VBox textCol = new VBox(1, previewLabel, metaLabel);
            textCol.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(textCol, Priority.ALWAYS);
            textCol.setMaxWidth(Double.MAX_VALUE);
            textCol.setMinWidth(0);
            // Size from the row, not the text, so long entries never widen the list.
            textCol.setPrefWidth(0);

            // Pin sits rightmost so pinned rows (pin only) line up with hovered rows.
            HBox actions = new HBox(2, deleteButton, collectionButton, pinButton);
            actions.getStyleClass().add("item-actions");
            actions.setMinWidth(Region.USE_PREF_SIZE);

            row = new HBox(12, leading, textCol, actions);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("clipboard-item");
            selectedProperty().addListener((obs, old, sel) -> row.pseudoClassStateChanged(SELECTED, sel));

            pinButton.setOnAction(e -> {
                ClipboardPreview item = getItem();
                if (item != null) {
                    togglePin(item);
                }
            });
            deleteButton.setOnAction(e -> {
                ClipboardPreview item = getItem();
                if (item != null) {
                    remove(item);
                }
            });
            collectionButton.setOnAction(e -> {
                ClipboardPreview item = getItem();
                if (item != null) {
                    CollectionBar.showBelow(new ContextMenu(collectionMenuItems(item).toArray(MenuItem[]::new)),
                            collectionButton);
                }
            });
            row.setOnContextMenuRequested(e -> {
                ClipboardPreview item = getItem();
                if (item != null) {
                    getListView().getSelectionModel().select(getIndex());
                    rowMenu(item).show(row, e.getScreenX(), e.getScreenY());
                    e.consume();
                }
            });

            setPadding(Insets.EMPTY);
        }

        @Override
        protected void updateItem(ClipboardPreview item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                thumbView.setImage(null);
                return;
            }
            boolean isPinned = item.pinned();
            row.pseudoClassStateChanged(PINNED, isPinned);
            pinButton.setTooltip(new Tooltip(isPinned ? "Unpin" : "Pin"));
            previewLabel.setText(truncate(item.preview()));
            String kind = isLink(item) ? "Link" : item.contentType().label();
            String collection = item.collectionId() == null ? null : collectionNames.get(item.collectionId());
            metaLabel.setText(kind + " \u00b7 " + RelativeTime.format(item.timestamp())
                    + (collection != null ? " \u00b7 " + collection : isPinned ? " \u00b7 Pinned" : ""));
            if (item.thumbnail() != null) {
                Image image = new Image(new ByteArrayInputStream(item.thumbnail()));
                double side = Math.min(image.getWidth(), image.getHeight());
                // Center-crop to a square so every thumbnail fills its slot.
                thumbView.setViewport(new Rectangle2D((image.getWidth() - side) / 2,
                        (image.getHeight() - side) / 2, side, side));
                thumbView.setImage(image);
                thumbView.setVisible(true);
                badge.setVisible(false);
            } else {
                thumbView.setImage(null);
                thumbView.setVisible(false);
                badge.setVisible(true);
                typeIcon.setPath(iconFor(item, kind));
            }
            row.pseudoClassStateChanged(SELECTED, isSelected());
            setGraphic(row);
        }

        private static boolean isLink(ClipboardPreview item) {
            String text = item.preview();
            return item.contentType() == ClipboardContentType.TEXT && text != null
                    && text.strip().matches("(?i)^(https?://|www\\.)\\S+$");
        }

        private static String iconFor(ClipboardPreview item, String kind) {
            if (kind.equals("Link")) {
                return Icons.LINK;
            }
            return switch (item.contentType()) {
                case TEXT -> Icons.TEXT;
                case RICH_TEXT -> Icons.RICH_TEXT;
                case IMAGE -> Icons.IMAGE;
                case UNKNOWN -> Icons.FILE;
            };
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

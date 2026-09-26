package ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import model.ClipboardCollection;
import model.HistoryFilter;

/**
 * Horizontal chip strip above the clipboard list: All, Pinned, one chip per collection
 * and a trailing "+" to create one. Collections are created and renamed in place with
 * an inline text field (a dialog would take focus and close the popup); right-click a
 * collection chip to rename or delete it.
 *
 * <p>The bar only edits its own UI. Persisting is delegated to the callbacks, which the
 * tab runs off the FX thread before reloading.</p>
 */
final class CollectionBar {

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final PseudoClass INVALID = PseudoClass.getPseudoClass("invalid");

    private final HBox chips = new HBox(6);
    private final ScrollPane scroller = new ScrollPane(chips);
    private final Consumer<HistoryFilter> onFilterChanged;
    private final BiConsumer<String, Consumer<ClipboardCollection>> onCreate;
    private final BiConsumer<Long, String> onRename;
    private final Consumer<Long> onDelete;

    private List<ClipboardCollection> collections = List.of();
    private HistoryFilter filter = HistoryFilter.ALL;
    /** Non-null while an inline editor is open; rebuilding would destroy it. */
    private TextField editor;
    /** Closes the open editor without saving. */
    private Runnable discardEditor = () -> { };

    CollectionBar(Consumer<HistoryFilter> onFilterChanged,
                  BiConsumer<String, Consumer<ClipboardCollection>> onCreate,
                  BiConsumer<Long, String> onRename,
                  Consumer<Long> onDelete) {
        this.onFilterChanged = onFilterChanged;
        this.onCreate = onCreate;
        this.onRename = onRename;
        this.onDelete = onDelete;
        chips.getStyleClass().add("collection-chips");
        chips.setAlignment(Pos.CENTER_LEFT);
        scroller.getStyleClass().add("collection-bar");
        scroller.setFitToHeight(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setFocusTraversable(false);
        // A plain mouse wheel scrolls vertically; turn that into sideways movement here.
        scroller.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.getDeltaX() == 0 && e.getDeltaY() != 0) {
                double overflow = chips.getWidth() - scroller.getViewportBounds().getWidth();
                if (overflow > 0) {
                    scroller.setHvalue(scroller.getHvalue() - e.getDeltaY() / overflow);
                }
                e.consume();
            }
        });
        rebuild();
    }

    Node getNode() {
        return scroller;
    }

    HistoryFilter filter() {
        return filter;
    }

    List<ClipboardCollection> collections() {
        return collections;
    }

    /** Selects a filter without notifying (used when the popup reopens). */
    void reset() {
        filter = HistoryFilter.ALL;
        rebuild();
    }

    void setCollections(List<ClipboardCollection> latest) {
        collections = List.copyOf(latest);
        // A collection deleted elsewhere cannot stay selected.
        if (filter.kind() == HistoryFilter.Kind.COLLECTION
                && collections.stream().noneMatch(c -> c.id() == filter.collectionId())) {
            filter = HistoryFilter.PINNED;
            onFilterChanged.accept(filter);
        }
        if (editor == null) {
            rebuild();
        }
    }

    void select(HistoryFilter next) {
        if (!next.equals(filter)) {
            filter = next;
            for (Node node : chips.getChildren()) {
                node.pseudoClassStateChanged(SELECTED, filter.equals(node.getUserData()));
            }
            revealSelected();
            onFilterChanged.accept(filter);
        }
    }

    /** Opens the inline "new collection" field; {@code onCreated} gets the new collection. */
    void beginCreate(Consumer<ClipboardCollection> onCreated) {
        cancelEditing();
        TextField field = newEditor("New collection", "");
        int plusIndex = chips.getChildren().size() - 1;
        chips.getChildren().set(plusIndex, field);
        openEditor(field, name -> onCreate.accept(name, created -> {
            onCreated.accept(created);
            select(HistoryFilter.collection(created.id()));
        }), null);
    }

    private void beginRename(ClipboardCollection collection, Node chip) {
        cancelEditing();
        TextField field = newEditor("Collection name", collection.name());
        chips.getChildren().set(chips.getChildren().indexOf(chip), field);
        openEditor(field, name -> {
            if (!name.equals(collection.name())) {
                onRename.accept(collection.id(), name);
            }
        }, collection.id());
    }

    private void rebuild() {
        List<Node> nodes = new ArrayList<>();
        nodes.add(chip("All", null, HistoryFilter.ALL));
        nodes.add(chip("Pinned", null, HistoryFilter.PINNED));
        for (ClipboardCollection collection : collections) {
            Label chip = chip(collection.name(), collection.itemCount(), HistoryFilter.collection(collection.id()));
            chip.setTooltip(new Tooltip(collection.name() + " — right-click to rename or delete"));
            ContextMenu menu = collectionMenu(collection, chip);
            chip.setOnContextMenuRequested(e -> menu.show(chip, e.getScreenX(), e.getScreenY()));
            chip.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                    beginRename(collection, chip);
                } else if (e.getButton() == MouseButton.PRIMARY) {
                    select(HistoryFilter.collection(collection.id()));
                }
            });
            nodes.add(chip);
        }
        Label add = new Label();
        add.setGraphic(Icons.icon(Icons.PLUS, 13));
        add.getStyleClass().addAll("chip", "chip-add");
        add.setTooltip(new Tooltip("New collection"));
        add.setOnMouseClicked(e -> beginCreate(created -> { }));
        nodes.add(add);
        chips.getChildren().setAll(nodes);
        Platform.runLater(this::revealSelected);
    }

    private Label chip(String text, Integer count, HistoryFilter target) {
        Label chip = new Label(text);
        chip.getStyleClass().add("chip");
        if (count != null) {
            Label badge = new Label(Integer.toString(count));
            badge.getStyleClass().add("chip-count");
            chip.setGraphic(badge);
            chip.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        }
        chip.pseudoClassStateChanged(SELECTED, target.equals(filter));
        chip.setUserData(target);
        chip.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                select(target);
            }
        });
        return chip;
    }

    private ContextMenu collectionMenu(ClipboardCollection collection, Node chip) {
        MenuItem rename = new MenuItem("Rename…");
        rename.setOnAction(e -> beginRename(collection, chip));
        MenuItem delete = new MenuItem("Delete Collection");
        delete.setOnAction(e -> {
            if (filter.kind() == HistoryFilter.Kind.COLLECTION && filter.collectionId() == collection.id()) {
                // Its items stay pinned: show them where they went.
                filter = HistoryFilter.PINNED;
                onFilterChanged.accept(filter);
            }
            onDelete.accept(collection.id());
        });
        MenuItem note = new MenuItem("Items stay pinned");
        note.setDisable(true);
        return new ContextMenu(rename, new SeparatorMenuItem(), delete, note);
    }

    private TextField newEditor(String prompt, String text) {
        TextField field = new TextField(text);
        field.setPromptText(prompt);
        field.getStyleClass().add("chip-editor");
        field.setPrefColumnCount(10);
        return field;
    }

    /**
     * Enter commits (blocked with a hint while the name is blank or taken), Escape
     * cancels, clicking elsewhere commits a valid name and otherwise cancels.
     */
    private void openEditor(TextField field, Consumer<String> commit, Long renaming) {
        editor = field;
        boolean[] done = {false};
        Tooltip hint = new Tooltip();
        Runnable finish = () -> {
            done[0] = true;
            hint.hide();
            editor = null;
            discardEditor = () -> { };
            rebuild();
        };
        discardEditor = finish;
        field.textProperty().addListener((o, old, now) -> {
            field.pseudoClassStateChanged(INVALID, false);
            hint.hide();
        });
        field.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                finish.run();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                String problem = nameProblem(field.getText(), renaming);
                if (problem != null) {
                    field.pseudoClassStateChanged(INVALID, true);
                    hint.setText(problem);
                    Bounds b = field.localToScreen(field.getBoundsInLocal());
                    hint.show(field, b.getMinX(), b.getMaxY() + 4);
                } else {
                    String name = field.getText().strip();
                    finish.run();
                    commit.accept(name);
                }
                e.consume();
            }
        });
        field.focusedProperty().addListener((o, was, now) -> {
            if (!now && !done[0]) {
                String name = field.getText().strip();
                boolean valid = nameProblem(name, renaming) == null;
                finish.run();
                if (valid) {
                    commit.accept(name);
                }
            }
        });
        Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
            reveal(field);
        });
    }

    private void cancelEditing() {
        discardEditor.run();
    }

    /** Mirrors ClipboardService.collectionNameProblem against the loaded list, for instant feedback. */
    private String nameProblem(String name, Long renaming) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            return "Enter a name";
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        boolean taken = collections.stream().anyMatch(c -> c.name().toLowerCase(Locale.ROOT).equals(lower)
                && !Long.valueOf(c.id()).equals(renaming));
        return taken ? "A collection with that name already exists" : null;
    }

    private void revealSelected() {
        chips.getChildren().stream()
                .filter(n -> filter.equals(n.getUserData()))
                .findFirst()
                .ifPresent(this::reveal);
    }

    /** Scrolls the strip so {@code node} is fully visible. */
    private void reveal(Node node) {
        // Lay out the scroller (not just the strip) so its content width is current.
        scroller.applyCss();
        scroller.layout();
        double viewport = scroller.getViewportBounds().getWidth();
        double overflow = chips.getWidth() - viewport;
        if (overflow <= 0) {
            return;
        }
        Bounds b = node.getBoundsInParent();
        double left = scroller.getHvalue() * overflow;
        if (b.getMinX() < left) {
            scroller.setHvalue(Math.max(0, (b.getMinX() - 8) / overflow));
        } else if (b.getMaxX() > left + viewport) {
            scroller.setHvalue(Math.min(1, (b.getMaxX() - viewport + 8) / overflow));
        }
    }

    /** Shows the collection picker for an item below {@code anchor}. */
    static void showBelow(ContextMenu menu, Node anchor) {
        menu.show(anchor, Side.BOTTOM, 0, 2);
    }
}

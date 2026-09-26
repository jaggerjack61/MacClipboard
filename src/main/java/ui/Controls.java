package ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Small reusable pieces shared by the popup tabs. */
final class Controls {

    private static final double ICON = 15;
    private static final double INSET = 10;

    private Controls() {
    }

    /**
     * A full-width search field with a leading magnifier and a trailing clear button.
     * The field stays the box's first child; the icons are overlaid (unmanaged) so they
     * sit inside the field's rounded background.
     */
    static HBox searchBox(TextField field) {
        Region glass = Icons.icon(Icons.SEARCH, ICON, "search-icon");
        glass.setMouseTransparent(true);
        glass.setManaged(false);

        Region clear = Icons.clearButton(ICON);
        clear.setManaged(false);
        clear.setOnMouseClicked(e -> {
            field.clear();
            field.requestFocus();
        });
        clear.visibleProperty().bind(field.textProperty().isNotEmpty());

        HBox box = new HBox(field, glass, clear) {
            @Override
            protected void layoutChildren() {
                super.layoutChildren();
                double y = field.getLayoutY() + (field.getHeight() - ICON) / 2;
                glass.resizeRelocate(field.getLayoutX() + INSET, y, ICON, ICON);
                clear.resizeRelocate(field.getLayoutX() + field.getWidth() - INSET - ICON, y, ICON, ICON);
            }
        };
        box.getStyleClass().add("search-box");
        HBox.setHgrow(field, Priority.ALWAYS);
        field.setMaxWidth(Double.MAX_VALUE);
        field.getStyleClass().add("search-field");
        return box;
    }

    /** "⌘P  Pin" style hint: a keycap followed by a muted label. */
    static Node keyHint(String key, String action) {
        Label cap = new Label(key);
        cap.getStyleClass().add("keycap");
        Label text = new Label(action);
        text.getStyleClass().add("key-action");
        HBox hint = new HBox(5, cap, text);
        hint.setAlignment(Pos.CENTER_LEFT);
        return hint;
    }

    static Region hSpacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}

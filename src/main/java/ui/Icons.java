package ui;

import javafx.scene.Group;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

/**
 * Line icons in the style of SF Symbols: round-capped strokes on a 24×24 grid, scaled
 * to the requested size and colored from CSS ({@code .icon-path { -fx-stroke }}), so
 * they follow the theme in light and dark mode — unlike emoji glyphs, which macOS
 * always paints in their own colors. Paths are from Lucide (ISC license).
 */
final class Icons {

    static final String SEARCH = "M3 11a8 8 0 1 0 16 0a8 8 0 1 0-16 0zM21 21l-4.3-4.3";
    static final String PIN = "M12 17v5M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16"
            + "a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7"
            + "a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z";
    static final String TRASH = "M3 6h18M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6M8 6V4c0-1 1-2 2-2h4"
            + "c1 0 2 1 2 2v2M10 11v6M14 11v6";
    static final String TEXT = "M4 6h16M4 12h16M4 18h10";
    static final String RICH_TEXT = "M4 7V4h16v3M9 20h6M12 4v16";
    static final String LINK = "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"
            + "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71";
    static final String IMAGE = "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"
            + "M7 9a2 2 0 1 0 4 0a2 2 0 1 0-4 0zM21 15l-3.09-3.09a2 2 0 0 0-2.82 0L6 21";
    static final String FILE = "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7zM14 2v4"
            + "a2 2 0 0 0 2 2h4";
    static final String PLUS = "M5 12h14M12 5v14";
    static final String FOLDER_PLUS = "M12 10v6M9 13h6M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9"
            + "a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z";
    static final String FOLDER = "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9"
            + "A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z";
    static final String CLIPBOARD = "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z"
            + "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2";

    /** Filled circle with a knocked-out ×, like the native search-field clear button. */
    private static final String CLEAR_SOLID = "M12 2C6.47 2 2 6.47 2 12s4.47 10 10 10 10-4.47 10-10"
            + "S17.53 2 12 2zm4.3 12.89L14.89 16.3 12 13.41 9.11 16.3 7.7 14.89 10.59 12 7.7 9.11 9.11 7.7"
            + " 12 10.59 14.89 7.7 16.3 9.11 13.41 12z";

    private Icons() {
    }

    /** A stroked icon of the given size; style it with {@code .icon-path} in CSS. */
    static Icon icon(String svgPath, double size, String... styleClasses) {
        Icon icon = new Icon(svgPath, size);
        icon.getStyleClass().addAll(styleClasses);
        return icon;
    }

    /** The search field's clear button (a solid shape, colored with -fx-background-color). */
    static Region clearButton(double size) {
        Region region = new Region();
        region.setStyle("-fx-shape: \"" + CLEAR_SOLID + "\";");
        region.getStyleClass().add("search-clear");
        region.setMinSize(size, size);
        region.setPrefSize(size, size);
        region.setMaxSize(size, size);
        return region;
    }

    /** Fixed-size box holding one scaled 24×24 path; the grid, not the ink, is centered. */
    static final class Icon extends StackPane {

        private final SVGPath path = new SVGPath();

        private Icon(String svgPath, double size) {
            path.setContent(svgPath);
            path.getStyleClass().add("icon-path");
            Rectangle grid = new Rectangle(24, 24);
            grid.setFill(null);
            grid.setStroke(null);
            Group scaled = new Group(grid, path);
            scaled.getTransforms().add(new Scale(size / 24, size / 24));
            // The outer group reports the scaled bounds to the StackPane's layout.
            getChildren().add(new Group(scaled));
            getStyleClass().add("icon");
            setMinSize(size, size);
            setPrefSize(size, size);
            setMaxSize(size, size);
        }

        void setPath(String svgPath) {
            path.setContent(svgPath);
        }
    }
}

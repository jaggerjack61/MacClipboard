package ui;

import java.util.List;
import javafx.scene.Scene;
import platform.MacNative;

/**
 * Applies the app stylesheet plus a dark-mode overlay that follows the macOS
 * appearance. The overlay is a scene stylesheet (not a style class) so popups owned by
 * the scene — tooltips, choice-box menus — pick up the same palette.
 */
final class Theme {

    private static final String BASE = stylesheet("/ui/clipboard.css");
    private static final String DARK = stylesheet("/ui/clipboard-dark.css");

    private Theme() {
    }

    /** Call when a window is about to show; re-reads the system appearance each time. */
    static void apply(Scene scene) {
        boolean dark = Boolean.getBoolean("clipboard.theme.dark") || MacNative.isDarkMode();
        List<String> sheets = scene.getStylesheets();
        if (!sheets.contains(BASE)) {
            sheets.add(0, BASE);
        }
        if (dark && !sheets.contains(DARK)) {
            sheets.add(DARK);
        } else if (!dark) {
            sheets.remove(DARK);
        }
    }

    private static String stylesheet(String path) {
        return Theme.class.getResource(path).toExternalForm();
    }
}

package tray;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.application.Platform;

/**
 * Menu-bar (tray) integration built on AWT {@link SystemTray}. The app runs without
 * a Dock icon (accessory activation policy), so this icon is its main entry point.
 */
public final class MenuBarService {

    private static final Logger LOG = Logger.getLogger(MenuBarService.class.getName());

    static {
        // Read when the first TrayIcon is created: lets macOS tint the icon like native ones.
        System.setProperty("apple.awt.enableTemplateImages", "true");
    }

    private final TrayIcon trayIcon;
    private PopupMenu menu;

    public MenuBarService(Runnable openClipboard,
                          Runnable openEmoji,
                          Runnable togglePause,
                          Runnable clearHistory,
                          Runnable openSettings,
                          Runnable quit) {
        if (!SystemTray.isSupported()) {
            this.trayIcon = null;
            LOG.warning("System tray is not supported on this platform");
        } else {
            trayIcon = new TrayIcon(createIconImage(), "Clipboard History");
            trayIcon.setImageAutoSize(true);
            menu = buildMenu(openClipboard, openEmoji, togglePause, clearHistory, openSettings, quit, trayIcon);
            trayIcon.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    // Left click opens the main popup; right click (or ctrl-click) opens the menu.
                    if (e.getButton() == java.awt.event.MouseEvent.BUTTON1 && !(e.isControlDown() || e.isMetaDown())) {
                        run(openClipboard);
                    }
                }
            });
        }
    }

    public void install() {
        if (trayIcon != null && SystemTray.isSupported()) {
            try {
                SystemTray.getSystemTray().add(trayIcon);
            } catch (java.awt.AWTException e) {
                LOG.log(Level.WARNING, "could not add tray icon", e);
            }
        }
    }

    public void setPaused(boolean paused) {
        if (menu != null) {
            for (int i = 0; i < menu.getItemCount(); i++) {
                if (menu.getItem(i).getLabel().contains("Clipboard Monitoring")) {
                    menu.getItem(i).setLabel(paused ? "Resume Clipboard Monitoring" : "Pause Clipboard Monitoring");
                }
            }
        }
    }

    public void dispose() {
        if (trayIcon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
    }

    private PopupMenu buildMenu(Runnable openClipboard, Runnable openEmoji, Runnable togglePause,
                                Runnable clearHistory, Runnable openSettings, Runnable quit,
                                TrayIcon icon) {
        PopupMenu popup = new PopupMenu();
        popup.add(item("Open Clipboard History", openClipboard));
        popup.add(item("Open Emoji Picker", openEmoji));
        popup.add(new java.awt.MenuItem("-"));
        popup.add(item("Pause Clipboard Monitoring", togglePause));
        popup.add(item("Clear History", clearHistory));
        popup.add(new java.awt.MenuItem("-"));
        popup.add(item("Settings…", openSettings));
        popup.add(new java.awt.MenuItem("-"));
        popup.add(item("Quit Clipboard", quit));
        icon.setPopupMenu(popup);
        return popup;
    }

    private static MenuItem item(String label, Runnable action) {
        MenuItem menuItem = new MenuItem(label);
        menuItem.addActionListener(e -> run(action));
        return menuItem;
    }

    private static void run(Runnable action) {
        Platform.runLater(action);
    }

    /**
     * The menu-bar glyph: a clipboard with two lines, matching the app's line icons.
     * Drawn black-on-transparent at 1x and 2x as a macOS template image, so the system
     * tints it for light/dark menu bars and the selected state.
     */
    private static Image createIconImage() {
        return new BaseMultiResolutionImage(drawIcon(1), drawIcon(2));
    }

    private static BufferedImage drawIcon(int scale) {
        int size = 22 * scale;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        // Lay a 24-unit grid over the central 18 pt of the 22 pt slot.
        double unit = scale * 18 / 24.0;
        g.translate(scale * 2, scale * 2);
        g.scale(unit, unit);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D board = new Path2D.Double();
        board.moveTo(16, 4);
        board.lineTo(18, 4);
        board.quadTo(20, 4, 20, 6);
        board.lineTo(20, 20);
        board.quadTo(20, 22, 18, 22);
        board.lineTo(6, 22);
        board.quadTo(4, 22, 4, 20);
        board.lineTo(4, 6);
        board.quadTo(4, 4, 6, 4);
        board.lineTo(8, 4);
        g.draw(board);
        g.draw(new RoundRectangle2D.Double(8, 2, 8, 4, 2, 2));
        g.draw(new Line2D.Double(8, 12, 16, 12));
        g.draw(new Line2D.Double(8, 16, 14, 16));
        g.dispose();
        return image;
    }
}

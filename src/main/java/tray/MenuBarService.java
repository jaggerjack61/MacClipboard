package tray;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
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
     * Draws a small template-style clipboard glyph, matching macOS menu-bar icons.
     */
    private static BufferedImage createIconImage() {
        int size = 22;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, size, size);
        g.setComposite(AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(70, 70, 74));
        g.setStroke(new java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        // board
        g.drawRoundRect(4, 4, 13, 15, 4, 4);
        // clip
        g.drawRoundRect(8, 2, 5, 4, 2, 2);
        // lines
        g.drawLine(7, 10, 14, 10);
        g.drawLine(7, 13, 14, 13);
        g.drawLine(7, 16, 12, 16);
        g.dispose();
        return image;
    }
}

package paste;

/**
 * Copies a value to the system clipboard and, where the OS allows, pastes it into the
 * application that had focus before the popup opened. Platform-specific implementations
 * handle focus tracking and synthetic keystrokes; a fallback implementation only copies.
 */
public interface PasteService {

    /**
     * Remembers which application currently has focus. Must be called before the popup
     * window steals focus. Safe to call repeatedly.
     */
    void captureFocusOwner();

    /**
     * Closes any transient UI, restores focus to the captured application and (if
     * {@code paste} is true and permitted) triggers a native paste.
     *
     * @param paste whether to synthesize Cmd+V after restoring focus
     * @return true if an automatic paste was actually delivered
     */
    boolean restoreFocusAndPaste(boolean paste);

    /** Whether automatic paste is currently possible (e.g. Accessibility granted). */
    boolean canAutoPaste();
}

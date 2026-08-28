package clipboard;

import java.util.Optional;

/**
 * Abstraction over the OS clipboard. The concrete macOS implementation uses AWT's
 * {@link java.awt.datatransfer.Clipboard}; tests can use an in-memory fake.
 */
public interface ClipboardGateway {

    /**
     * Reads the current clipboard content.
     *
     * @return empty when the clipboard is empty, unsupported or temporarily owned
     *         by another application (a common macOS race during copy operations)
     */
    Optional<ClipboardSnapshot> read();

    /** Publishes a snapshot to the system clipboard. */
    void write(ClipboardSnapshot snapshot);
}

package hotkey;

/**
 * System-wide keyboard shortcut registration. Isolated behind an interface so the
 * macOS implementation (which relies on native event taps + Accessibility permission)
 * can be replaced with another library or a no-op for testing.
 */
public interface GlobalHotkeyService extends AutoCloseable {

    /**
     * Registers the shortcut so that each press invokes the callback. The callback
     * may run on a non-UI thread; callers must marshal to the JavaFX thread.
     *
     * @return true if the shortcut was registered successfully
     */
    boolean register(ShortcutModifier shortcut, Runnable callback);

    /** Removes any registered shortcut. */
    void unregister();

    @Override
    default void close() {
        unregister();
    }
}

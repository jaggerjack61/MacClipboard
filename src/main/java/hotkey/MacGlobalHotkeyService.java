package hotkey;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * macOS global hotkey using JNativeHook (CoreGraphics event tap). Requires macOS
 * Accessibility permission; {@link #register} returns false when the native hook
 * cannot be created so the app can prompt the user instead of failing silently.
 */
public final class MacGlobalHotkeyService implements GlobalHotkeyService {

    private static final Logger LOG = Logger.getLogger(MacGlobalHotkeyService.class.getName());

    private final ShortcutListener listener = new ShortcutListener();
    private volatile ShortcutModifier shortcut;
    private volatile Runnable callback;
    private volatile boolean registered;

    public MacGlobalHotkeyService() {
        // JNativeHook is extremely chatty via JUL; quiet it down.
        Logger jnativehook = Logger.getLogger("com.github.kwhat.jnativehook");
        jnativehook.setLevel(Level.WARNING);
        jnativehook.setUseParentHandlers(false);
    }

    @Override
    public synchronized boolean register(ShortcutModifier newShortcut, Runnable newCallback) {
        if (newShortcut == null) {
            LOG.warning("invalid shortcut configuration");
            return false;
        }
        try {
            if (!registered) {
                GlobalScreen.registerNativeHook();
                GlobalScreen.addNativeKeyListener(listener);
                registered = true;
            }
            this.shortcut = newShortcut;
            this.callback = newCallback;
            LOG.info("global hotkey registered: " + newShortcut.format());
            return true;
        } catch (NativeHookException e) {
            LOG.log(Level.WARNING, "failed to register global hotkey (missing Accessibility permission?)", e);
            return false;
        }
    }

    @Override
    public synchronized void unregister() {
        this.shortcut = null;
        this.callback = null;
        try {
            if (registered) {
                GlobalScreen.removeNativeKeyListener(listener);
                GlobalScreen.unregisterNativeHook();
                registered = false;
            }
        } catch (NativeHookException e) {
            LOG.log(Level.WARNING, "failed to unregister global hotkey", e);
        }
    }

    private final class ShortcutListener implements NativeKeyListener {

        @Override
        public void nativeKeyPressed(NativeKeyEvent event) {
            ShortcutModifier sc = shortcut;
            if (sc == null) {
                return;
            }
            if (event.getKeyCode() != sc.keyCode()) {
                return;
            }
            boolean meta = (event.getModifiers() & NativeKeyEvent.META_MASK) != 0;
            boolean ctrl = (event.getModifiers() & NativeKeyEvent.CTRL_MASK) != 0;
            boolean alt = (event.getModifiers() & NativeKeyEvent.ALT_MASK) != 0;
            boolean shift = (event.getModifiers() & NativeKeyEvent.SHIFT_MASK) != 0;
            if (meta == sc.has(ShortcutModifier.Modifier.MAC)
                    && ctrl == sc.has(ShortcutModifier.Modifier.CTRL)
                    && alt == sc.has(ShortcutModifier.Modifier.ALT)
                    && shift == sc.has(ShortcutModifier.Modifier.SHIFT)) {
                Runnable cb = callback;
                if (cb != null) {
                    cb.run();
                }
            }
        }
    }
}

package paste;

import platform.MacNative;

/**
 * macOS {@link PasteService} using AppKit to remember/restore the frontmost app and
 * CoreGraphics to synthesize Command+V. If Accessibility permission is missing, paste
 * silently degrades to copy-only (the value still lands on the clipboard).
 */
public final class MacPasteService implements PasteService {

    private volatile Object focusToken;

    @Override
    public void captureFocusOwner() {
        focusToken = MacNative.captureFrontmost();
    }

    @Override
    public boolean restoreFocusAndPaste(boolean paste) {
        boolean restored = MacNative.restoreFrontmost(focusToken);
        if (!paste) {
            return false;
        }
        // Give the OS a moment to bring the previous app to front before the keystroke.
        if (restored) {
            sleepQuietly(40);
        }
        boolean delivered = MacNative.synthesizeCmdV();
        if (!delivered) {
            // Try again after a slightly longer settle window.
            sleepQuietly(60);
            delivered = MacNative.synthesizeCmdV();
        }
        return delivered;
    }

    @Override
    public boolean canAutoPaste() {
        return MacNative.isNativeAvailable() && MacNative.isAccessibilityTrusted();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

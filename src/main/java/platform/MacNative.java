package platform;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Low-level macOS native helpers. Two mechanisms are combined:
 * <ul>
 *   <li>JNA mappings for C libraries (CoreGraphics + HIServices) used for the synthetic
 *       Cmd+V paste and accessibility check.</li>
 *   <li>{@link ObjCRuntime} for AppKit calls used to remember/restore the previous
 *       application and to run as a menu-bar-only (accessory) app.</li>
 * </ul>
 *
 * <p>All public methods degrade gracefully to {@code false}/{@code null}/no-op when the
 * libraries cannot be loaded, so the application still runs on non-macOS systems.</p>
 */
public final class MacNative {

    /** CoreGraphics virtual key code for 'V'. */
    private static final int KVK_ANSI_V = 0x09;
    /** kCGEventFlagMaskCommand */
    private static final long CG_EVENT_FLAG_MASK_COMMAND = 0x100000L;
    /** kCGSessionEventTap */
    private static final int CG_SESSION_EVENT_TAP = 1;

    private MacNative() {
    }

    private static volatile boolean loadAttempted;
    private static volatile boolean loadFailed;
    private static CoreGraphics cg;
    private static HIServices hs;

    private static synchronized void ensureLoaded() {
        if (loadAttempted) {
            return;
        }
        loadAttempted = true;
        try {
            cg = Native.load("CoreGraphics", CoreGraphics.class);
        } catch (Throwable t) {
            loadFailed = true;
            log("CoreGraphics unavailable", t);
            return;
        }
        try {
            hs = Native.load("ApplicationServices", HIServices.class);
        } catch (Throwable t1) {
            try {
                hs = Native.load("HIServices", HIServices.class);
            } catch (Throwable t2) {
                loadFailed = true;
                log("HIServices unavailable", t2);
            }
        }
    }

    public static boolean isNativeAvailable() {
        ensureLoaded();
        return !loadFailed && ObjCRuntime.isAvailable();
    }

    // ---- Accessibility permission ------------------------------------------------

    /**
     * Whether this process is trusted for Accessibility. Required for the global hotkey
     * native event tap and for synthetic Cmd+V paste into other applications.
     */
    public static boolean isAccessibilityTrusted() {
        ensureLoaded();
        if (loadFailed) {
            return false;
        }
        try {
            return hs.AXIsProcessTrusted();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Opens the macOS Accessibility settings pane so the user can grant permission.
     * This is the documented, reliable way (the prompt API is non-standard here).
     */
    public static void openAccessibilitySettings() {
        try {
            new ProcessBuilder("/usr/bin/open",
                    "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")
                    .start();
        } catch (Throwable t) {
            log("could not open accessibility settings", t);
        }
    }

    // ---- Frontmost application ---------------------------------------------------

    /**
     * Captures the currently active (frontmost) application, returning a token that can
     * later be passed to {@link #restoreFrontmost(Object)}. Uses the private
     * {@link ObjCRuntime} pointers; callers must treat the token as opaque.
     */
    public static Object captureFrontmost() {
        if (!ObjCRuntime.isAvailable()) {
            return null;
        }
        Pointer workspace = ObjCRuntime.msg(ObjCRuntime.cls("NSWorkspace"), "sharedWorkspace");
        if (workspace == null) {
            return null;
        }
        Pointer app = ObjCRuntime.msg(workspace, "frontmostApplication");
        if (app == null) {
            return null;
        }
        // Store pid rather than the pointer to avoid lifecycle issues.
        int pid = ObjCRuntime.msgInt(app, "processIdentifier");
        return pid > 0 ? pid : null;
    }

    /** Re-activates the previously captured application before synthesizing a paste. */
    public static boolean restoreFrontmost(Object token) {
        if (!(token instanceof Integer pid) || !ObjCRuntime.isAvailable()) {
            return false;
        }
        Pointer runningApp = ObjCRuntime.msg(ObjCRuntime.cls("NSRunningApplication"),
                "runningApplicationWithProcessIdentifier:", pid);
        if (runningApp == null) {
            return false;
        }
        // NSApplicationActivateIgnoringOtherApps = 1 << 1
        long options = 1L << 1;
        return ObjCRuntime.msgBool(runningApp, "activateWithOptions:", options);
    }

    /** Display name of the frontmost app, used for privacy source filtering. */
    public static String frontmostApplicationName() {
        if (!ObjCRuntime.isAvailable()) {
            return null;
        }
        Pointer workspace = ObjCRuntime.msg(ObjCRuntime.cls("NSWorkspace"), "sharedWorkspace");
        if (workspace == null) {
            return null;
        }
        Pointer app = ObjCRuntime.msg(workspace, "frontmostApplication");
        if (app == null) {
            return null;
        }
        Pointer name = ObjCRuntime.msg(app, "localizedName");
        return ObjCRuntime.toJavaString(name);
    }

    // ---- Synthetic Cmd+V paste ---------------------------------------------------

    /**
     * Synthesizes a Command+V keypress into the session event stream. Requires
     * Accessibility permission to reach other applications; returns false otherwise.
     */
    public static boolean synthesizeCmdV() {
        ensureLoaded();
        if (loadFailed || !isAccessibilityTrusted()) {
            return false;
        }
        try {
            Pointer source = cg.CGEventSourceCreate(1); // kCGEventSourceStateHIDSystemState
            Pointer down = cg.CGEventCreateKeyboardEvent(source, KVK_ANSI_V, true);
            cg.CGEventSetFlags(down, CG_EVENT_FLAG_MASK_COMMAND);
            Pointer up = cg.CGEventCreateKeyboardEvent(source, KVK_ANSI_V, false);
            cg.CGEventSetFlags(up, CG_EVENT_FLAG_MASK_COMMAND);
            cg.CGEventPost(CG_SESSION_EVENT_TAP, down);
            cg.CGEventPost(CG_SESSION_EVENT_TAP, up);
            return true;
        } catch (Throwable t) {
            log("synthesize Cmd+V failed", t);
            return false;
        }
    }

    // ---- AppKit activation policy (hide Dock icon) -------------------------------

    /**
     * Switches this process to "accessory" mode so no Dock icon is shown (menu-bar only).
     * Safe to call repeatedly. No-op if the runtime is unavailable. In the packaged
     * .app this is enforced by the LSUIElement Info.plist key; this call covers {@code ./gradlew run}.
     */
    public static void setActivationPolicyAccessory() {
        if (!ObjCRuntime.isAvailable()) {
            return;
        }
        try {
            Pointer nsApp = ObjCRuntime.msg(ObjCRuntime.cls("NSApplication"), "sharedApplication");
            if (nsApp != null) {
                // NSApplicationActivationPolicyAccessory == 1
                ObjCRuntime.msg(nsApp, "setActivationPolicy:", 1);
            }
        } catch (Throwable t) {
            log("could not set accessory activation policy", t);
        }
    }

    private static void log(String message, Throwable t) {
        System.getLogger("platform.MacNative")
                .log(System.Logger.Level.WARNING, message, t);
    }

    // ---- JNA C library mappings --------------------------------------------------

    interface CoreGraphics extends Library {
        Pointer CGEventSourceCreate(int sourceType);
        Pointer CGEventCreateKeyboardEvent(Pointer source, int virtualKey, boolean keyDown);
        void CGEventSetFlags(Pointer event, long flags);
        void CGEventPost(int tap, Pointer event);
    }

    interface HIServices extends Library {
        boolean AXIsProcessTrusted();
    }
}

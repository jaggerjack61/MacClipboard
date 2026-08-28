package platform;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

/**
 * Minimal, safe Objective-C runtime bridge using JNA. Exposes just enough to look up
 * classes, register selectors and send messages with pointer/scalar/boolean arguments.
 *
 * <p>Only scalar/pointer messages are supported (no struct returns), which is all the
 * app needs for clipboard/paste/focus operations. Every entry point is defensive: it
 * returns a neutral value instead of throwing if the runtime is unavailable.</p>
 */
final class ObjCRuntime {

    private static volatile boolean ready;
    private static volatile boolean failed;
    private static Function objc_getClass;
    private static Function sel_registerName;
    private static Function objc_msgSend;
    private static Pointer nil;

    private ObjCRuntime() {
    }

    private static synchronized void init() {
        if (ready || failed) {
            return;
        }
        try {
            NativeLibrary lib = NativeLibrary.getInstance("objc");
            objc_getClass = lib.getFunction("objc_getClass");
            sel_registerName = lib.getFunction("sel_registerName");
            objc_msgSend = lib.getFunction("objc_msgSend");
            ready = true;
        } catch (Throwable t) {
            failed = true;
            System.getLogger("platform.ObjCRuntime")
                    .log(System.Logger.Level.WARNING, "Objective-C runtime unavailable", t);
        }
    }

    static boolean isAvailable() {
        init();
        return ready;
    }

    static Pointer nil() {
        return nil;
    }

    static Pointer cls(String name) {
        init();
        if (!ready) {
            return null;
        }
        try {
            return objc_getClass.invokePointer(new Object[]{name});
        } catch (Throwable t) {
            return null;
        }
    }

    static Pointer sel(String name) {
        init();
        if (!ready) {
            return null;
        }
        try {
            return sel_registerName.invokePointer(new Object[]{name});
        } catch (Throwable t) {
            return null;
        }
    }

    /** Sends a message returning an object pointer. */
    static Pointer msg(Pointer receiver, String selectorName, Object... args) {
        init();
        if (!ready || receiver == null) {
            return null;
        }
        try {
            Pointer sel = sel(selectorName);
            Object[] full = new Object[args.length + 2];
            full[0] = receiver;
            full[1] = sel;
            System.arraycopy(args, 0, full, 2, args.length);
            return objc_msgSend.invokePointer(full);
        } catch (Throwable t) {
            return null;
        }
    }

    static long msgLong(Pointer receiver, String selectorName, Object... args) {
        init();
        if (!ready || receiver == null) {
            return 0;
        }
        try {
            Pointer sel = sel(selectorName);
            Object[] full = new Object[args.length + 2];
            full[0] = receiver;
            full[1] = sel;
            System.arraycopy(args, 0, full, 2, args.length);
            return objc_msgSend.invokeLong(full);
        } catch (Throwable t) {
            return 0;
        }
    }

    static int msgInt(Pointer receiver, String selectorName, Object... args) {
        init();
        if (!ready || receiver == null) {
            return 0;
        }
        try {
            Pointer sel = sel(selectorName);
            Object[] full = new Object[args.length + 2];
            full[0] = receiver;
            full[1] = sel;
            System.arraycopy(args, 0, full, 2, args.length);
            return objc_msgSend.invokeInt(full);
        } catch (Throwable t) {
            return 0;
        }
    }

    static boolean msgBool(Pointer receiver, String selectorName, Object... args) {
        init();
        if (!ready || receiver == null) {
            return false;
        }
        try {
            Pointer sel = sel(selectorName);
            Object[] full = new Object[args.length + 2];
            full[0] = receiver;
            full[1] = sel;
            System.arraycopy(args, 0, full, 2, args.length);
            return objc_msgSend.invokeInt(full) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Copies a UTF-8 NSString into a Java string (reads the C string via UTF8String). */
    static String toJavaString(Pointer nsString) {
        init();
        if (!ready || nsString == null) {
            return null;
        }
        try {
            Pointer utf8 = msg(nsString, "UTF8String");
            if (utf8 == null) {
                return null;
            }
            return utf8.getString(0, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}

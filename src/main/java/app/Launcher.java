package app;

/**
 * Plain entry point that does not extend {@code javafx.application.Application}.
 *
 * <p>JavaFX's launcher refuses to start when the declared {@code Main-Class} extends
 * {@code Application} but JavaFX is on the classpath (not the module path), which is
 * exactly how the jpackage app-image runs. Delegating through this class avoids the
 * "JavaFX runtime components are missing" check while keeping everything else identical.</p>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        ClipboardApplication.main(args);
    }
}

package security;

import clipboard.ClipboardSnapshot;
import config.ApplicationSettings;
import java.util.List;
import model.ClipboardContentType;

/**
 * Central privacy policy for the clipboard pipeline.
 *
 * <p>Responsibilities: a global pause switch, content-shape filters (very long or
 * binary-ish payloads), and the extension point for per-application exclusions
 * (e.g. password managers). The concrete "which app owns the clipboard" lookup is
 * macOS-specific; this service exposes a hook the platform layer can call to mark
 * the current source as ignored.</p>
 */
public final class PrivacyService {

    private static final int MAX_TEXT_LENGTH = 200_000;

    private final ApplicationSettings settings;
    private volatile boolean paused;

    public PrivacyService(ApplicationSettings settings) {
        this.settings = settings;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public void toggle() {
        setPaused(!paused);
    }

    /**
     * Hook used by the platform monitor: when the current clipboard owner app is
     * resolved to an ignored application, this method should be consulted. For now
     * the app-name resolution lives in the AWT gateway; this method makes the
     * decision reusable and unit-testable.
     */
    public boolean isIgnoredApp(String appDisplayName) {
        if (appDisplayName == null || appDisplayName.isBlank()) {
            return false;
        }
        List<String> ignored = settings.ignoredApps();
        String lower = appDisplayName.toLowerCase();
        return ignored.stream().anyMatch(a -> lower.contains(a.toLowerCase()));
    }

    public boolean shouldIgnore(ClipboardSnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return true;
        }
        if (snapshot.contentType() == ClipboardContentType.TEXT || snapshot.contentType() == ClipboardContentType.RICH_TEXT) {
            int length = snapshot.text() == null ? 0 : snapshot.text().length();
            // Guard memory: absurdly large text payloads are not stored.
            if (length == 0 || length > MAX_TEXT_LENGTH) {
                return true;
            }
        }
        return false;
    }
}

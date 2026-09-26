package clipboard;

import config.ApplicationSettings;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import security.PrivacyService;

/**
 * Polls the system clipboard off the UI thread and feeds new content into the
 * {@link ClipboardService}. Polling (rather than AWT ownership listeners) is used
 * because the macOS clipboard does not notify passive observers of changes; the
 * interval is kept small (400 ms) with an almost-zero idle cost since each poll
 * only queries clipboard metadata.
 */
public final class ClipboardMonitor implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ClipboardMonitor.class.getName());

    private final ClipboardGateway gateway;
    private final ClipboardService service;
    private final ApplicationSettings settings;
    private final PrivacyService privacy;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "clipboard-monitor");
                t.setDaemon(true);
                return t;
            });

    private volatile ScheduledFuture<?> future;
    private ScheduledFuture<?> maintenance;
    private long lastChangeCount = -1;

    public ClipboardMonitor(ClipboardGateway gateway, ClipboardService service,
                            ApplicationSettings settings, PrivacyService privacy) {
        this.gateway = gateway;
        this.service = service;
        this.settings = settings;
        this.privacy = privacy;
    }

    public synchronized void start() {
        if (future != null) {
            return;
        }
        int interval = Math.max(250, settings.pollIntervalMs());
        future = executor.scheduleWithFixedDelay(this::poll, interval, interval, TimeUnit.MILLISECONDS);
        maintenance = executor.scheduleWithFixedDelay(this::maintainHistory, 0, 1, TimeUnit.MINUTES);
    }

    public synchronized void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        if (maintenance != null) {
            maintenance.cancel(false);
            maintenance = null;
        }
    }

    public boolean isRunning() {
        return future != null;
    }

    void poll() {
        try {
            if (!settings.monitoringEnabled() || privacy.isPaused()) {
                return;
            }
            long revision = gateway.changeCount();
            if (revision >= 0 && revision == lastChangeCount) {
                return;
            }
            gateway.read().ifPresent(snapshot -> {
                // If another app copied while we decoded, retry the new revision.
                if (revision >= 0 && gateway.changeCount() != revision) {
                    return;
                }
                if (!privacy.shouldIgnore(snapshot)) {
                    service.ingest(snapshot, revision >= 0 && lastChangeCount >= 0);
                }
                lastChangeCount = revision;
            });
        } catch (Exception e) {
            // Never leak clipboard content into logs; only structural failures.
            LOG.log(Level.FINE, () -> "clipboard poll skipped: " + e.getClass().getSimpleName());
        }
    }

    /** Retention continues even while capture is paused or the clipboard is idle. */
    void maintainHistory() {
        try {
            service.applyLimits();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "History cleanup failed: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        stop();
        executor.shutdownNow();
    }
}

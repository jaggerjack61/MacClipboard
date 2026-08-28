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
    }

    public synchronized void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }

    public boolean isRunning() {
        return future != null;
    }

    private void poll() {
        try {
            if (!settings.monitoringEnabled() || privacy.isPaused()) {
                return;
            }
            gateway.read().ifPresent(snapshot -> {
                if (!privacy.shouldIgnore(snapshot)) {
                    service.ingest(snapshot);
                }
            });
        } catch (Exception e) {
            // Never leak clipboard content into logs; only structural failures.
            LOG.log(Level.FINE, () -> "clipboard poll skipped: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        stop();
        executor.shutdownNow();
    }
}

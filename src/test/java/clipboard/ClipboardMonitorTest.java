package clipboard;

import static org.junit.jupiter.api.Assertions.*;

import config.ApplicationSettings;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repository.InMemoryClipboardRepository;
import repository.SettingsStore;
import security.PrivacyService;

class ClipboardMonitorTest {
    private ApplicationSettings settings;
    private InMemoryClipboardRepository repository;
    private ClipboardService service;
    private PrivacyService privacy;
    private FakeGateway gateway;

    @BeforeEach
    void setUp() {
        settings = new ApplicationSettings(new SettingsStore() {
            final Map<String, String> values = new HashMap<>();
            public Map<String, String> loadAll() { return Map.copyOf(values); }
            public void save(String k, String v) { values.put(k, v); }
            public void delete(String k) { values.remove(k); }
        });
        repository = new InMemoryClipboardRepository();
        service = new ClipboardService(repository, settings);
        privacy = new PrivacyService(settings);
        gateway = new FakeGateway();
    }

    private ClipboardMonitor monitor() {
        return new ClipboardMonitor(gateway, service, settings, privacy);
    }

    @Test
    void unchangedRevisionDoesNotReadOrEncodeImagePayloadAgain() {
        gateway.snapshot = ClipboardSnapshot.image(new byte[]{1, 2, 3}, new byte[]{4}, 10, 10);
        try (var monitor = monitor()) {
            monitor.poll();
            service.clearAll();
            for (int i = 0; i < 10; i++) monitor.poll();
            assertEquals(1, gateway.reads);
            assertEquals(0, service.count());
            gateway.revision++;
            gateway.snapshot = ClipboardSnapshot.image(new byte[]{5, 6, 7}, new byte[]{4}, 10, 10);
            monitor.poll();
            assertEquals(2, gateway.reads);
            assertEquals(1, service.count());
        }
    }

    @Test
    void failedReadRetriesTheSameRevision() {
        gateway.fail = true;
        try (var monitor = monitor()) {
            monitor.poll();
            gateway.fail = false;
            monitor.poll();
            assertEquals(2, gateway.reads);
            assertEquals(1, service.count());
        }
    }

    @Test
    void explicitlyCopyingTheSameContentAgainAfterClearingRecordsIt() {
        try (var monitor = monitor()) {
            monitor.poll();
            service.clearAll();
            monitor.poll();
            assertEquals(0, service.count());
            gateway.revision++;
            monitor.poll();
            assertEquals(1, service.count());
        }
    }

    @Test
    void revisionChangingDuringReadDiscardsStaleDataAndRetries() {
        gateway.changeDuringRead = true;
        try (var monitor = monitor()) {
            monitor.poll();
            assertEquals(0, service.count());
            gateway.changeDuringRead = false;
            monitor.poll();
            assertEquals(2, gateway.reads);
            assertEquals(1, service.count());
        }
    }

    @Test
    void unavailableRevisionFallsBackToReadsWithoutResurrectingClearedData() {
        gateway.revision = -1;
        try (var monitor = monitor()) {
            monitor.poll();
            service.clearAll();
            monitor.poll();
            assertEquals(2, gateway.reads);
            assertEquals(0, service.count());
        }
    }

    @Test
    void scheduledMaintenanceRunsEvenWhenCaptureIsPaused() {
        var expired = service.ingest(gateway.snapshot).orElseThrow();
        repository.touch(expired.id(), System.currentTimeMillis() - 3L * 86_400_000);
        settings.setRetentionDays(1);
        settings.setMonitoringEnabled(false);
        privacy.setPaused(true);
        try (var monitor = monitor()) {
            monitor.start();
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (service.count() != 0) Thread.sleep(10);
            });
            assertEquals(0, gateway.reads);
        }
    }

    @Test
    void retentionRemovesIdleContentWithoutTheNextPollReinsertingIt() {
        try (var monitor = monitor()) {
            monitor.poll();
            var expired = service.history("").getFirst();
            repository.touch(expired.id(), System.currentTimeMillis() - 3L * 86_400_000);
            settings.setRetentionDays(1);
            monitor.maintainHistory();
            monitor.poll();
            assertEquals(0, service.count());
        }
    }

    private static class FakeGateway implements ClipboardGateway {
        long revision = 1;
        int reads;
        boolean fail;
        boolean changeDuringRead;
        ClipboardSnapshot snapshot = ClipboardSnapshot.text("test content", null);
        public long changeCount() { return revision; }
        public Optional<ClipboardSnapshot> read() {
            reads++;
            if (fail) throw new IllegalStateException("temporarily unavailable");
            if (changeDuringRead) revision++;
            return Optional.of(snapshot);
        }
        public void write(ClipboardSnapshot value) { snapshot = value; revision++; }
    }
}

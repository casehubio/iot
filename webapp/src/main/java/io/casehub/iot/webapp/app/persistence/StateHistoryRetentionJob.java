package io.casehub.iot.webapp.app.persistence;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@ApplicationScoped
public class StateHistoryRetentionJob {

    private static final Logger LOG = Logger.getLogger(StateHistoryRetentionJob.class);
    private static final int LARGE_DELETE_THRESHOLD = 10_000;

    private final EntityManager em;
    private final StateHistoryRetentionConfig config;

    @Inject
    public StateHistoryRetentionJob(final EntityManager em, final StateHistoryRetentionConfig config) {
        this.em = em;
        this.config = config;
        config.retentionDays().ifPresent(days -> {
            if (days <= 0) {
                throw new IllegalArgumentException(
                    "casehub.iot.webapp.state-history.retention-days must be >= 1, got: " + days);
            }
        });
    }

    @Scheduled(every = "${casehub.iot.webapp.state-history.purge-interval:1h}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void purge() {
        if (config.retentionDays().isEmpty()) {
            LOG.debugv("State history retention not configured — purge skipped");
            return;
        }

        final int retentionDays = config.retentionDays().get();
        final Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        final int deleted = em.createQuery("DELETE FROM IoTDeviceStateHistoryEntity e WHERE e.occurredAt < :cutoff")
            .setParameter("cutoff", cutoff)
            .executeUpdate();

        if (deleted > LARGE_DELETE_THRESHOLD) {
            LOG.warnv("State history retention purge deleted {0} rows (threshold {1}) — " +
                "first-run catch-up or aggressive retention tightening", deleted, LARGE_DELETE_THRESHOLD);
        } else if (deleted > 0) {
            LOG.infov("State history retention purge deleted {0} rows older than {1} days", deleted, retentionDays);
        }
    }
}

package io.casehub.iot.webapp.app.cbr;

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CbrRetentionJob {

    private static final Logger LOG = Logger.getLogger(CbrRetentionJob.class);
    private static final MemoryDomain IOT_DOMAIN = new MemoryDomain("iot");
    private static final int LARGE_DELETE_THRESHOLD = 10_000;

    private final CbrCaseMemoryStore store;
    private final CbrRetentionConfig config;
    private final String tenantId;

    @Inject
    public CbrRetentionJob(final CbrCaseMemoryStore store,
                           final CbrRetentionConfig config,
                           @ConfigProperty(name = "casehub.iot.tenancy-id") final String tenantId) {
        this.store = store;
        this.config = config;
        this.tenantId = tenantId;
        config.maxAgeDays().ifPresent(days -> {
            if (days <= 0) {
                throw new IllegalArgumentException(
                        "casehub.iot.webapp.cbr.retention.max-age-days must be >= 1, got: " + days);
            }
        });
        config.maxCasesPerType().ifPresent(max -> {
            if (max <= 0) {
                throw new IllegalArgumentException(
                        "casehub.iot.webapp.cbr.retention.max-cases-per-type must be >= 1, got: " + max);
            }
        });
    }

    @Scheduled(every = "${casehub.iot.webapp.cbr.retention.purge-interval:PT1H}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void purge() {
        if (config.maxAgeDays().isEmpty() && config.maxCasesPerType().isEmpty()) {
            LOG.debugv("CBR retention not configured — purge skipped");
            return;
        }

        final CbrRetentionPolicy policy = new CbrRetentionPolicy(
                tenantId,
                IOT_DOMAIN,
                null,
                config.maxAgeDays().orElse(null),
                config.maxCasesPerType().orElse(null));

        final int deleted = store.purge(policy);

        if (deleted > LARGE_DELETE_THRESHOLD) {
            LOG.warnv("CBR retention purge deleted {0} rows (threshold {1}) — " +
                    "first-run catch-up or aggressive retention tightening", deleted, LARGE_DELETE_THRESHOLD);
        } else if (deleted > 0) {
            LOG.infov("CBR retention purge deleted {0} rows", deleted);
        }
    }
}

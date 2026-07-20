package io.casehub.iot.webapp.app.persistence;

import io.casehub.iot.webapp.cbr.SuppressionTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "iot_suppression_log")
public class SuppressionLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "situation_id", nullable = false)
    private String situationId;

    @Column(name = "correlation_key", nullable = false)
    private String correlationKey;

    @Column(name = "tenancy_id", nullable = false)
    private String tenancyId;

    @Column(name = "suppressed_at", nullable = false)
    private Instant suppressedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 20)
    private SuppressionTier tier;

    @Column(name = "dismissal_rate", nullable = false)
    private double dismissalRate;

    @Column(name = "matched_case_count", nullable = false)
    private int matchedCaseCount;

    @Column(name = "average_similarity", nullable = false)
    private double averageSimilarity;

    @Column(name = "context_snapshot", columnDefinition = "jsonb")
    private String contextSnapshot;

    @Column(name = "overridden", nullable = false)
    private boolean overridden;

    @Column(name = "overridden_at")
    private Instant overriddenAt;

    @Column(name = "overridden_by")
    private String overriddenBy;

    protected SuppressionLogEntry() {}

    public SuppressionLogEntry(String situationId, String correlationKey, String tenancyId,
                                Instant suppressedAt, SuppressionTier tier,
                                double dismissalRate, int matchedCaseCount,
                                double averageSimilarity, String contextSnapshot) {
        this.situationId = situationId;
        this.correlationKey = correlationKey;
        this.tenancyId = tenancyId;
        this.suppressedAt = suppressedAt;
        this.tier = tier;
        this.dismissalRate = dismissalRate;
        this.matchedCaseCount = matchedCaseCount;
        this.averageSimilarity = averageSimilarity;
        this.contextSnapshot = contextSnapshot;
        this.overridden = false;
    }

    public UUID id() { return id; }
    public String situationId() { return situationId; }
    public String correlationKey() { return correlationKey; }
    public String tenancyId() { return tenancyId; }
    public Instant suppressedAt() { return suppressedAt; }
    public SuppressionTier tier() { return tier; }
    public double dismissalRate() { return dismissalRate; }
    public int matchedCaseCount() { return matchedCaseCount; }
    public double averageSimilarity() { return averageSimilarity; }
    public String contextSnapshot() { return contextSnapshot; }
    public boolean overridden() { return overridden; }
    public Instant overriddenAt() { return overriddenAt; }
    public String overriddenBy() { return overriddenBy; }

    public void markOverridden(String operatorId) {
        this.overridden = true;
        this.overriddenAt = Instant.now();
        this.overriddenBy = operatorId;
    }
}

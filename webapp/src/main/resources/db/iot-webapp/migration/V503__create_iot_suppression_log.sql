CREATE TABLE iot_suppression_log (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    situation_id       VARCHAR(255)     NOT NULL,
    correlation_key    VARCHAR(255)     NOT NULL,
    tenancy_id         VARCHAR(255)     NOT NULL,
    suppressed_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    tier               VARCHAR(20)      NOT NULL,
    dismissal_rate     DOUBLE PRECISION NOT NULL,
    matched_case_count INT              NOT NULL,
    average_similarity DOUBLE PRECISION NOT NULL,
    context_snapshot   JSONB,
    overridden         BOOLEAN          NOT NULL DEFAULT FALSE,
    overridden_at      TIMESTAMPTZ,
    overridden_by      VARCHAR(255)
);

CREATE INDEX idx_suppression_log_situation ON iot_suppression_log (situation_id, suppressed_at DESC);
CREATE INDEX idx_suppression_log_recent ON iot_suppression_log (suppressed_at DESC) WHERE NOT overridden;

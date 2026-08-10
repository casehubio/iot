-- IoT situation definitions (persisted ganglion configuration)
CREATE TABLE iot_situation_definition (
    id              UUID            NOT NULL PRIMARY KEY,
    situation_id    VARCHAR(255)    NOT NULL,
    tenancy_id      VARCHAR(255)    NOT NULL,
    definition      JSONB           NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_iot_sit_def_tenant UNIQUE (situation_id, tenancy_id)
);

CREATE INDEX idx_iot_situation_def_tenancy ON iot_situation_definition (tenancy_id);

-- Case command audit log
CREATE TABLE iot_case_command_log (
    id              UUID            NOT NULL PRIMARY KEY,
    case_id         UUID            NOT NULL,
    tenancy_id      VARCHAR(255)    NOT NULL,
    device_id       VARCHAR(255)    NOT NULL,
    action          VARCHAR(50)     NOT NULL,
    result          VARCHAR(20)     NOT NULL,
    dispatched_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    correlation_id  VARCHAR(255)
);

CREATE INDEX idx_iot_case_cmd_case ON iot_case_command_log (case_id);
CREATE INDEX idx_iot_case_cmd_device ON iot_case_command_log (device_id);
CREATE INDEX idx_iot_case_cmd_tenancy_time ON iot_case_command_log (tenancy_id, dispatched_at DESC);

-- Device state change history
CREATE TABLE iot_device_state_history (
    id                      UUID            NOT NULL PRIMARY KEY,
    tenancy_id              VARCHAR(255)    NOT NULL,
    device_id               VARCHAR(255)    NOT NULL,
    provider_id             VARCHAR(255)    NOT NULL,
    device_class            VARCHAR(50)     NOT NULL,
    state_snapshot          JSONB           NOT NULL,
    changed_capabilities    TEXT[]          NOT NULL,
    occurred_at             TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_iot_state_hist_device_time ON iot_device_state_history (device_id, occurred_at DESC);
CREATE INDEX idx_iot_state_hist_tenancy_time ON iot_device_state_history (tenancy_id, occurred_at DESC);

-- Suppression log (CBR-driven situation suppression)
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

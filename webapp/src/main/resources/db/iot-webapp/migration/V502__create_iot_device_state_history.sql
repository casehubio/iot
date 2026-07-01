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

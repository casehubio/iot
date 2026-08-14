CREATE TABLE bridge_audit_event (
    id              UUID            NOT NULL PRIMARY KEY,
    tenancy_id      VARCHAR(255)    NOT NULL,
    received_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    event_type      VARCHAR(50)     NOT NULL,
    correlation_id  VARCHAR(255),
    device_id       VARCHAR(255),
    message         JSONB
);

CREATE INDEX idx_bridge_audit_tenancy_time
    ON bridge_audit_event (tenancy_id, received_at DESC);

CREATE INDEX idx_bridge_audit_device
    ON bridge_audit_event (device_id);

CREATE INDEX idx_bridge_audit_correlation
    ON bridge_audit_event (correlation_id);

CREATE INDEX idx_bridge_audit_received_at
    ON bridge_audit_event (received_at);

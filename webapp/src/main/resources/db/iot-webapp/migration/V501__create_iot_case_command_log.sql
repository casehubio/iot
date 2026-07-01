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

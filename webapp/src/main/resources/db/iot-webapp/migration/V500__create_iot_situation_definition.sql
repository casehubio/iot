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

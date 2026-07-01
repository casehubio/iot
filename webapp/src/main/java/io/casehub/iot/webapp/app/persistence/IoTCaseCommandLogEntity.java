package io.casehub.iot.webapp.app.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "iot_case_command_log")
public class IoTCaseCommandLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "tenancy_id", nullable = false)
    private String tenancyId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "result", nullable = false, length = 20)
    private String result;

    @Column(name = "dispatched_at", nullable = false)
    private Instant dispatchedAt;

    @Column(name = "correlation_id")
    private String correlationId;

    protected IoTCaseCommandLogEntity() {}

    public IoTCaseCommandLogEntity(final UUID caseId, final String tenancyId,
                                   final String deviceId, final String action,
                                   final String result, final Instant dispatchedAt,
                                   final String correlationId) {
        this.caseId = caseId;
        this.tenancyId = tenancyId;
        this.deviceId = deviceId;
        this.action = action;
        this.result = result;
        this.dispatchedAt = dispatchedAt;
        this.correlationId = correlationId;
    }

    public UUID getId() { return id; }
    public UUID getCaseId() { return caseId; }
    public String getTenancyId() { return tenancyId; }
    public String getDeviceId() { return deviceId; }
    public String getAction() { return action; }
    public String getResult() { return result; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public String getCorrelationId() { return correlationId; }
}

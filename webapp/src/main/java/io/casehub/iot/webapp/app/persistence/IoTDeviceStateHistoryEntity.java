package io.casehub.iot.webapp.app.persistence;

import io.casehub.iot.api.DeviceEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "iot_device_state_history")
public class IoTDeviceStateHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenancy_id", nullable = false)
    private String tenancyId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @Column(name = "device_class", nullable = false, length = 50)
    private String deviceClass;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_snapshot", nullable = false, columnDefinition = "jsonb")
    private DeviceEntity stateSnapshot;

    @Column(name = "changed_capabilities", nullable = false, columnDefinition = "text[]")
    private String[] changedCapabilities;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected IoTDeviceStateHistoryEntity() {}

    public IoTDeviceStateHistoryEntity(final String tenancyId, final String deviceId,
                                       final String providerId, final String deviceClass,
                                       final DeviceEntity stateSnapshot,
                                       final String[] changedCapabilities,
                                       final Instant occurredAt) {
        this.tenancyId = tenancyId;
        this.deviceId = deviceId;
        this.providerId = providerId;
        this.deviceClass = deviceClass;
        this.stateSnapshot = stateSnapshot;
        this.changedCapabilities = changedCapabilities;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public String getTenancyId() { return tenancyId; }
    public String getDeviceId() { return deviceId; }
    public String getProviderId() { return providerId; }
    public String getDeviceClass() { return deviceClass; }
    public DeviceEntity getStateSnapshot() { return stateSnapshot; }
    public String[] getChangedCapabilities() { return changedCapabilities; }
    public Instant getOccurredAt() { return occurredAt; }
}

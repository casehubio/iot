package io.casehub.iot.webapp.app.persistence;

import io.casehub.ras.api.SituationDefinition;
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
@Table(name = "iot_situation_definition")
public class IoTSituationDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "situation_id", nullable = false)
    private String situationId;

    @Column(name = "tenancy_id", nullable = false)
    private String tenancyId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", nullable = false, columnDefinition = "jsonb")
    private SituationDefinition definition;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IoTSituationDefinitionEntity() {}

    public IoTSituationDefinitionEntity(final String situationId, final String tenancyId,
                                        final SituationDefinition definition,
                                        final Instant createdAt, final Instant updatedAt) {
        this.situationId = situationId;
        this.tenancyId = tenancyId;
        this.definition = definition;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public String getSituationId() { return situationId; }
    public String getTenancyId() { return tenancyId; }
    public SituationDefinition getDefinition() { return definition; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

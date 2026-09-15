package io.casehub.iot.webapp.view;

import java.time.Instant;

public record SituationDefinitionView(
    String situationId,
    String tenancyId,
    Object definition,
    Instant createdAt,
    Instant updatedAt,
    String source) {}

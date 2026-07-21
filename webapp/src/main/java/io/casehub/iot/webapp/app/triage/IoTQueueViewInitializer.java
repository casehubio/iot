package io.casehub.iot.webapp.app.triage;

import io.casehub.platform.api.view.SubjectViewSpec;
import io.casehub.platform.api.view.SubjectViewStore;
import io.casehub.platform.view.SubjectViewOrchestrator;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class IoTQueueViewInitializer {

    @Inject SubjectViewStore viewStore;
    @Inject SubjectViewOrchestrator orchestrator;

    @Inject @ConfigProperty(name = "casehub.iot.tenancy-id")
    String tenancyId;

    void onStartup(@Observes StartupEvent event) {
        List<SubjectViewSpec> existing = viewStore.findByTenancy(tenancyId);
        ensureView(existing, "iot-immediate", "iot-triage:immediate");
        ensureView(existing, "iot-ai-resolution", "iot-triage:ai-resolution");
        ensureView(existing, "iot-operator-assisted", "iot-triage:operator-assisted");
        ensureView(existing, "iot-operator-manual", "iot-triage:operator-manual");
    }

    private void ensureView(List<SubjectViewSpec> existing, String name, String labelPattern) {
        boolean found = existing.stream().anyMatch(v -> name.equals(v.name()));
        if (!found) {
            orchestrator.saveView(new SubjectViewSpec(
                UUID.randomUUID(), name, tenancyId, labelPattern,
                null, "enqueuedAt", "ASC", null, Instant.now()));
        }
    }
}

package io.casehub.iot.webapp.app.engine;

import io.casehub.api.engine.YamlCaseHub;
import io.casehub.api.engine.definition.CaseDefinition;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.webapp.engine.SecurityAlertCaseDescriptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * CaseHub for security-alert cases triggered by intrusion situations.
 *
 * <p>Loads YAML structure from classpath and augments with worker lambdas
 * from SecurityAlertCaseDescriptor. Flow: lock doors + activate cameras →
 * notify household → human decision (false alarm / escalate / call authorities).
 * Automated lock is immediate, but escalation requires human approval.
 *
 * <p>Referenced by RAS CaseTriggerConfig: {@code ("io.casehub.iot", "security-alert", "1.0")}
 */
@ApplicationScoped
public class SecurityAlertCaseHub extends YamlCaseHub {

    @Inject
    Instance<DeviceProvider> providers;

    @Inject
    DeviceRegistry registry;

    public SecurityAlertCaseHub() {
        super("iot/security-alert.yaml");
    }

    @Override
    protected void augment(final CaseDefinition definition) {
        final var descriptor = new SecurityAlertCaseDescriptor(providers, registry);
        descriptor.workers().forEach(definition::registerWorker);
    }
}

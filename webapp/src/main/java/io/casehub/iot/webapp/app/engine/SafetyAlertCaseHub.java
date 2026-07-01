package io.casehub.iot.webapp.app.engine;

import io.casehub.api.engine.YamlCaseHub;
import io.casehub.api.engine.definition.CaseDefinition;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.webapp.engine.SafetyAlertCaseDescriptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * CaseHub for safety-alert cases triggered by fire risk situations.
 *
 * <p>Loads YAML structure from classpath and augments with worker lambdas
 * from SafetyAlertCaseDescriptor. Flow: immediately dispatch safety commands
 * (kill HVAC, unlock doors) → notify household → human acknowledgement.
 * Safety-first — no approval gate before automated response.
 *
 * <p>Referenced by RAS CaseTriggerConfig: {@code ("io.casehub.iot", "safety-alert", "1.0")}
 */
@ApplicationScoped
public class SafetyAlertCaseHub extends YamlCaseHub {

    @Inject
    Instance<DeviceProvider> providers;

    @Inject
    DeviceRegistry registry;

    public SafetyAlertCaseHub() {
        super("iot/safety-alert.yaml");
    }

    @Override
    protected void augment(final CaseDefinition definition) {
        final var descriptor = new SafetyAlertCaseDescriptor(providers, registry);
        descriptor.workers().forEach(definition::registerWorker);
    }
}

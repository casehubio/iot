package io.casehub.iot.webapp.risk;

import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.ClassificationContext;
import io.casehub.api.spi.RiskClassifier;
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.worker.api.PlannedAction;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

@RiskClassifier
@ApplicationScoped
public class IoTActionRiskClassifier implements ActionRiskClassifier {

    private static final Set<String> SAFETY_CASE_TYPES = Set.of("safety-alert");
    private static final Set<String> AUTONOMOUS_ACTIONS = Set.of(
            "TURN_ON", "TURN_OFF", "SET_TEMPERATURE", "SET_POSITION", "SET_VOLUME");
    private static final Set<String> GATED_ACTIONS = Set.of("LOCK", "UNLOCK");

    @Override
    public RiskDecision classify(PlannedAction action, ClassificationContext context) {
        var actionType = action.actionType();

        if (SAFETY_CASE_TYPES.contains(context.caseDefinitionName())) {
            return new RiskDecision.Autonomous();
        }

        if (AUTONOMOUS_ACTIONS.contains(actionType)) {
            return new RiskDecision.Autonomous();
        }

        if (GATED_ACTIONS.contains(actionType)) {
            return new RiskDecision.GateRequired(
                    "Lock/unlock commands require human approval",
                    true,
                    StaticSetStrategy.of("iot-operator"),
                    null,
                    "casehubio/iot/oversight");
        }

        return new RiskDecision.GateRequired(
                "Unknown IoT command — manual review required",
                true,
                StaticSetStrategy.of("iot-admin"),
                null,
                "casehubio/iot/oversight");
    }
}

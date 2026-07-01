package io.casehub.iot.webapp.risk;

import io.casehub.api.spi.ClassificationContext;
import io.casehub.api.spi.RiskDecision;
import io.casehub.worker.api.PlannedAction;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IoTActionRiskClassifierTest {

    private final IoTActionRiskClassifier classifier = new IoTActionRiskClassifier();

    private static ClassificationContext ctx(String caseDefName) {
        return new ClassificationContext(
                "iot-worker", UUID.randomUUID(), "default-tenant",
                caseDefName, "device-command-dispatch", "dispatch");
    }

    @Test
    void safetyCommandsDuringSafetyAlertAreAutonomous() {
        var action = PlannedAction.of("Unlock doors", "TURN_OFF",
                Map.of("action", "TURN_OFF", "context", "safety-alert"));
        var result = classifier.classify(action, ctx("safety-alert"));
        assertThat(result).isInstanceOf(RiskDecision.Autonomous.class);
    }

    @Test
    void lockCommandsRequireGate() {
        var action = PlannedAction.of("Lock front door", "LOCK",
                Map.of("action", "LOCK"));
        var result = classifier.classify(action, ctx("security-alert"));
        assertThat(result).isInstanceOf(RiskDecision.GateRequired.class);
    }

    @Test
    void lockCommandsDuringSafetyAlertAreAutonomous() {
        var action = PlannedAction.of("Lock doors", "LOCK",
                Map.of("action", "LOCK", "context", "safety-alert"));
        var result = classifier.classify(action, ctx("safety-alert"));
        assertThat(result).isInstanceOf(RiskDecision.Autonomous.class);
    }

    @Test
    void hvacAdjustmentsAreAutonomous() {
        var action = PlannedAction.of("Set temperature", "SET_TEMPERATURE",
                Map.of("action", "SET_TEMPERATURE"));
        var result = classifier.classify(action, ctx("hvac-anomaly"));
        assertThat(result).isInstanceOf(RiskDecision.Autonomous.class);
    }

    @Test
    void unknownCommandsRequireGate() {
        var action = PlannedAction.of("Unknown action", "CUSTOM_ACTION",
                Map.of("action", "CUSTOM_ACTION"));
        var result = classifier.classify(action, ctx("generic-response"));
        assertThat(result).isInstanceOf(RiskDecision.GateRequired.class);
    }
}

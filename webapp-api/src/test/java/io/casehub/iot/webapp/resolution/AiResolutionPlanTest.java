package io.casehub.iot.webapp.resolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiResolutionPlanTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesExecutePlan() throws Exception {
        String json = """
            {
              "decision": "EXECUTE",
              "reasoning": "High similarity match with past filter replacement case",
              "actions": [{
                "actionType": "SET_TEMPERATURE",
                "targetDeviceId": "thermo-001",
                "parameters": {"target": 22},
                "rationale": "Reset to normal operating temperature"
              }],
              "escalationReason": null
            }
            """;

        AiResolutionPlan plan = mapper.readValue(json, AiResolutionPlan.class);

        assertThat(plan.decision()).isEqualTo(Decision.EXECUTE);
        assertThat(plan.actions()).hasSize(1);
        assertThat(plan.actions().get(0).actionType()).isEqualTo("SET_TEMPERATURE");
        assertThat(plan.actions().get(0).targetDeviceId()).isEqualTo("thermo-001");
        assertThat(plan.escalationReason()).isNull();
    }

    @Test
    void deserializesEscalatePlan() throws Exception {
        String json = """
            {
              "decision": "ESCALATE",
              "reasoning": "Context differs significantly from past cases",
              "actions": [],
              "escalationReason": "No matching resolution pattern for multi-zone failure"
            }
            """;

        AiResolutionPlan plan = mapper.readValue(json, AiResolutionPlan.class);

        assertThat(plan.decision()).isEqualTo(Decision.ESCALATE);
        assertThat(plan.actions()).isEmpty();
        assertThat(plan.escalationReason()).isNotNull();
    }

    @Test
    void rejectsNullDecision() {
        assertThatThrownBy(() -> new AiResolutionPlan(null, "reason", List.of(), null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullReasoning() {
        assertThatThrownBy(() -> new AiResolutionPlan(Decision.EXECUTE, null, List.of(), null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullActionsDefaultsToEmptyList() {
        AiResolutionPlan plan = new AiResolutionPlan(Decision.ESCALATE, "reason", null, "escalate");
        assertThat(plan.actions()).isEmpty();
    }

    @Test
    void plannedActionSpecRejectsNullActionType() {
        assertThatThrownBy(() -> new PlannedActionSpec(null, "dev-1", Map.of(), "reason"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void plannedActionSpecRejectsNullDeviceId() {
        assertThatThrownBy(() -> new PlannedActionSpec("SET_TEMPERATURE", null, Map.of(), "reason"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void plannedActionSpecNullParametersDefaultsToEmptyMap() {
        PlannedActionSpec spec = new PlannedActionSpec("SET_TEMPERATURE", "dev-1", null, "reason");
        assertThat(spec.parameters()).isEmpty();
    }

    @Test
    void executedActionResultRejectsNullAction() {
        assertThatThrownBy(() -> new ExecutedActionResult(null, true, "done"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void executedActionResultRejectsNullOutcome() {
        var action = new PlannedActionSpec("SET_TEMPERATURE", "dev-1", Map.of(), "reason");
        assertThatThrownBy(() -> new ExecutedActionResult(action, true, null))
            .isInstanceOf(NullPointerException.class);
    }
}

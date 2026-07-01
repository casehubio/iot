package io.casehub.iot.webapp.worker;

import io.casehub.worker.api.WorkerResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HumanDecisionWorkerFunctionTest {

    private final HumanDecisionWorkerFunction function = new HumanDecisionWorkerFunction();

    @Test
    void returnsMockDecisionOutcome() {
        var input = Map.<String, Object>of(
                "situationContext", "Fire risk detected",
                "options", Map.of("approve", "Proceed", "reject", "Cancel")
        );

        WorkerResult result = function.apply(input);

        assertThat(result.output()).containsEntry("decision", "approved");
        assertThat(result.output()).containsEntry("workItemCreated", true);
    }

    @Test
    void handlesEmptyOptions() {
        var input = Map.<String, Object>of(
                "situationContext", "Unknown situation"
        );

        WorkerResult result = function.apply(input);

        assertThat(result.output()).containsEntry("decision", "approved");
        assertThat(result.output()).containsKey("situationContext");
    }
}

package io.casehub.iot.webapp.worker;

import io.casehub.worker.api.WorkerResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HouseholdNotificationWorkerFunctionTest {

    private final HouseholdNotificationWorkerFunction function = new HouseholdNotificationWorkerFunction();

    @Test
    void returnsSuccessWithMockData() {
        var input = Map.<String, Object>of(
                "tenancyId", "default-tenant",
                "message", "Safety alert detected"
        );

        WorkerResult result = function.apply(input);

        assertThat(result.output()).containsEntry("sent", true);
        assertThat(result.output()).containsEntry("tenancyId", "default-tenant");
    }

    @Test
    void handlesNullInputs() {
        var input = Map.<String, Object>of();

        WorkerResult result = function.apply(input);

        assertThat(result.output()).containsEntry("sent", true);
        assertThat(result.output()).containsKey("tenancyId");
        assertThat(result.output()).containsKey("message");
    }
}

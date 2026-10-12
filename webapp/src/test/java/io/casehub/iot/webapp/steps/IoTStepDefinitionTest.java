package io.casehub.iot.webapp.steps;

import io.casehub.pages.playbook.runtime.StepCatalogService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IoTStepDefinitionTest {

    private static StepCatalogService catalog;

    @BeforeAll
    static void loadStepDefinitions() throws URISyntaxException {
        var url = IoTStepDefinitionTest.class.getClassLoader().getResource("steps");
        assertThat(url).as("steps/ directory must exist on classpath").isNotNull();
        catalog = new StepCatalogService(Path.of(url.toURI()));
    }

    @Test
    void allThreeActionsPresent() {
        assertThat(catalog.getAction("command")).isPresent();
        assertThat(catalog.getAction("state")).isPresent();
        assertThat(catalog.getAction("desired-state")).isPresent();
    }

    @Test
    void commandHasMcpBinding() {
        var action = catalog.getAction("command");
        assertThat(action).isPresent();
        assertThat(action.get().invokeKind()).isEqualTo("mcp");
        assertThat(action.get().invoke()).isNotNull();
        assertThat(action.get().invoke().kind()).isEqualTo("mcp");
        assertThat(action.get().invoke().metadata()).containsEntry("tool", "iot_send_command");
    }

    @Test
    void stateHasMcpBinding() {
        var action = catalog.getAction("state");
        assertThat(action).isPresent();
        assertThat(action.get().invokeKind()).isEqualTo("mcp");
        assertThat(action.get().invoke()).isNotNull();
        assertThat(action.get().invoke().metadata()).containsEntry("tool", "iot_get_state");
    }

    @Test
    void desiredStateHasNoInvokeBinding() {
        var action = catalog.getAction("desired-state");
        assertThat(action).isPresent();
        assertThat(action.get().invokeKind()).isNull();
        assertThat(action.get().invoke()).isNull();
    }

    @Test
    void allActionsHaveDescriptions() {
        for (var action : catalog.listActions()) {
            assertThat(action.description())
                    .as("Action '%s' should have a description", action.name())
                    .isNotBlank();
        }
    }

    @Test
    void commandHasRequiredInputs() {
        var action = catalog.getAction("command").orElseThrow();
        assertThat(action.inputs()).containsKey("device");
        assertThat(action.inputs().get("device").required()).isTrue();
        assertThat(action.inputs()).containsKey("action");
        assertThat(action.inputs().get("action").required()).isTrue();
    }

    @Test
    void commandHasOptionalInputs() {
        var action = catalog.getAction("command").orElseThrow();
        assertThat(action.inputs()).containsKey("params");
        assertThat(action.inputs().get("params").required()).isFalse();
        assertThat(action.inputs()).containsKey("correlation-id");
        assertThat(action.inputs().get("correlation-id").required()).isFalse();
    }

    @Test
    void stateHasRequiredDeviceInput() {
        var action = catalog.getAction("state").orElseThrow();
        assertThat(action.inputs()).containsKey("device");
        assertThat(action.inputs().get("device").required()).isTrue();
        assertThat(action.inputs()).hasSize(1);
    }

    @Test
    void desiredStateHasOptionalPresetInput() {
        var action = catalog.getAction("desired-state").orElseThrow();
        assertThat(action.inputs()).containsKey("preset");
        assertThat(action.inputs().get("preset").required()).isFalse();
    }
}

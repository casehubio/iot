package io.casehub.iot.webapp.app.scenario;

import io.casehub.pages.scenario.ScriptDescriptor;
import io.casehub.pages.scenario.ScriptDescriptorExtractor;
import io.casehub.pages.scenario.ScriptProvenance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class IoTScenarioYamlTest {

    @ParameterizedTest
    @ValueSource(strings = {"morning-routine", "emergency", "full-demo"})
    void scenarioParsesSuccessfully(String name) {
        String yaml = loadScenario(name);
        ScriptDescriptor desc = ScriptDescriptorExtractor.extract(yaml, ScriptProvenance.BUNDLED);

        assertThat(desc.name()).isEqualTo(name);
        assertThat(desc.description()).isNotBlank();
        assertThat(desc.labels()).contains("demo");
    }

    @Test
    void morningRoutineHasCorrectMetadata() {
        String yaml = loadScenario("morning-routine");
        ScriptDescriptor desc = ScriptDescriptorExtractor.extract(yaml, ScriptProvenance.BUNDLED);

        assertThat(desc.name()).isEqualTo("morning-routine");
        assertThat(desc.labels()).containsExactlyInAnyOrder("demo", "simulation");
    }

    @Test
    void emergencyHasEmergencyLabel() {
        String yaml = loadScenario("emergency");
        ScriptDescriptor desc = ScriptDescriptorExtractor.extract(yaml, ScriptProvenance.BUNDLED);

        assertThat(desc.labels()).contains("emergency");
    }

    @Test
    void fullDemoHasComprehensiveLabel() {
        String yaml = loadScenario("full-demo");
        ScriptDescriptor desc = ScriptDescriptorExtractor.extract(yaml, ScriptProvenance.BUNDLED);

        assertThat(desc.labels()).contains("comprehensive");
    }

    @Test
    void bundledScriptSourceFindsAllScenarios() {
        var paths = java.util.List.of(
                "META-INF/scenarios/morning-routine.yaml",
                "META-INF/scenarios/emergency.yaml",
                "META-INF/scenarios/full-demo.yaml");
        var source = new io.casehub.pages.scenario.runtime.BundledScriptSource(paths);

        assertThat(source.list()).hasSize(3);
        assertThat(source.contains("morning-routine")).isTrue();
        assertThat(source.contains("emergency")).isTrue();
        assertThat(source.contains("full-demo")).isTrue();

        assertThat(source.getYaml("morning-routine")).isPresent();
        assertThat(source.getYaml("emergency")).isPresent();
        assertThat(source.getYaml("full-demo")).isPresent();
    }

    private static String loadScenario(String name) {
        String path = "META-INF/scenarios/" + name + ".yaml";
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path)) {
            assertThat(is).as("Scenario resource %s", path).isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

package io.casehub.iot.webapp.resolution;

import io.casehub.iot.webapp.cbr.ResolutionSuggestion;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AiResolutionPromptBuilderTest {

    @Test
    void buildIncludesCaseContextDeviceClassAndRoomType() {
        Map<String, Object> context = Map.of(
            "deviceClass", "thermostat",
            "roomType", "living_room",
            "eventDescription", "Temperature rising above threshold"
        );

        String prompt = AiResolutionPromptBuilder.build(context, List.of(), Set.of("SET_TEMPERATURE"));

        assertThat(prompt).contains("thermostat");
        assertThat(prompt).contains("living_room");
        assertThat(prompt).contains("Temperature rising above threshold");
        assertThat(prompt).contains("SET_TEMPERATURE");
    }

    @Test
    void buildIncludesSuggestionDetails() {
        Map<String, Object> context = Map.of("deviceClass", "thermostat");
        ResolutionSuggestion suggestion = new ResolutionSuggestion(
            "past-case-1", 0.92, "Sustained temperature rise",
            "Replaced blocked HVAC filter", "RESOLVED", 0.95,
            Map.of("deviceClass", "thermostat"),
            Map.of("deviceClass", 1.0),
            List.of(new PlanTrace("check-filter", "device-control",
                "set-temperature", "SUCCESS", 1, Map.of("target", 22), null))
        );

        String prompt = AiResolutionPromptBuilder.build(
            context, List.of(suggestion), Set.of("SET_TEMPERATURE"));

        assertThat(prompt).contains("0.92");
        assertThat(prompt).contains("Replaced blocked HVAC filter");
        assertThat(prompt).contains("RESOLVED");
        assertThat(prompt).contains("set-temperature");
    }

    @Test
    void buildHandlesEmptySuggestions() {
        String prompt = AiResolutionPromptBuilder.build(
            Map.of("deviceClass", "thermostat"), List.of(), Set.of("SET_TEMPERATURE"));

        assertThat(prompt).contains("No similar past cases found");
    }

    @Test
    void buildHandlesEmptyAvailableActions() {
        String prompt = AiResolutionPromptBuilder.build(
            Map.of("deviceClass", "thermostat"), List.of(), Set.of());

        assertThat(prompt).contains("No autonomous actions available");
    }

    @Test
    void buildHandlesMultipleSuggestionsSortedByIndex() {
        ResolutionSuggestion s1 = new ResolutionSuggestion(
            "case-1", 0.95, "Problem A", "Solution A", "RESOLVED", null,
            Map.of(), Map.of(), List.of());
        ResolutionSuggestion s2 = new ResolutionSuggestion(
            "case-2", 0.87, "Problem B", "Solution B", "RESOLVED_PARTIAL", null,
            Map.of(), Map.of(), List.of());

        String prompt = AiResolutionPromptBuilder.build(
            Map.of("deviceClass", "sensor"), List.of(s1, s2), Set.of("TURN_OFF"));

        assertThat(prompt).contains("Match 1");
        assertThat(prompt).contains("Match 2");
        assertThat(prompt).contains("0.95");
        assertThat(prompt).contains("0.87");
    }
}

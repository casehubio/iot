package io.casehub.iot.openhab;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.openhab.internal.OpenHabItemDto;
import io.casehub.iot.openhab.internal.OpenHabSseEventDto;
import io.casehub.iot.openhab.internal.OpenHabStatePayloadDto;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OpenHabDtoTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void itemDtoDeserializesEquipmentGroupWithMembers() throws Exception {
        String json = """
            {
              "type": "Group",
              "name": "LivingRoom_Thermostat",
              "label": "Living Room Thermostat",
              "state": "NULL",
              "tags": ["Equipment", "HVAC"],
              "members": [
                {
                  "type": "Number:Temperature",
                  "name": "LivingRoom_Temperature",
                  "label": "Current Temperature",
                  "state": "21.5",
                  "tags": ["Measurement", "Temperature"],
                  "stateDescription": {"pattern": "%.1f °C"}
                },
                {
                  "type": "Number:Temperature",
                  "name": "LivingRoom_Setpoint",
                  "label": "Target Temperature",
                  "state": "22.0",
                  "tags": ["Setpoint", "Temperature"],
                  "stateDescription": {"pattern": "%.1f °C"}
                }
              ]
            }
            """;
        OpenHabItemDto item = mapper.readValue(json, OpenHabItemDto.class);
        assertThat(item.type()).isEqualTo("Group");
        assertThat(item.name()).isEqualTo("LivingRoom_Thermostat");
        assertThat(item.tags()).containsExactly("Equipment", "HVAC");
        assertThat(item.members()).hasSize(2);
        assertThat(item.members().get(0).name()).isEqualTo("LivingRoom_Temperature");
        assertThat(item.members().get(0).stateDescription()).isNotNull();
        assertThat(item.members().get(0).stateDescription().pattern()).isEqualTo("%.1f °C");
    }

    @Test
    void itemDtoIgnoresUnknownFields() throws Exception {
        String json = """
            {"type":"Switch","name":"Light_1","state":"ON","tags":[],"unknownField":"value"}
            """;
        OpenHabItemDto item = mapper.readValue(json, OpenHabItemDto.class);
        assertThat(item.name()).isEqualTo("Light_1");
    }

    @Test
    void sseEventDtoDeserializes() throws Exception {
        String json = """
            {
              "topic": "openhab/items/LivingRoom_Temperature/statechanged",
              "payload": "{\\"type\\":\\"Decimal\\",\\"value\\":\\"22.0\\",\\"oldType\\":\\"Decimal\\",\\"oldValue\\":\\"21.5\\"}",
              "type": "ItemStateChangedEvent"
            }
            """;
        OpenHabSseEventDto event = mapper.readValue(json, OpenHabSseEventDto.class);
        assertThat(event.topic()).isEqualTo("openhab/items/LivingRoom_Temperature/statechanged");
        assertThat(event.type()).isEqualTo("ItemStateChangedEvent");

        OpenHabStatePayloadDto payload = mapper.readValue(event.payload(), OpenHabStatePayloadDto.class);
        assertThat(payload.type()).isEqualTo("Decimal");
        assertThat(payload.value()).isEqualTo("22.0");
        assertThat(payload.oldValue()).isEqualTo("21.5");
    }
}

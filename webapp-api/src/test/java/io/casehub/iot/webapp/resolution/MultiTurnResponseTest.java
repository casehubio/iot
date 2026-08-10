package io.casehub.iot.webapp.resolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MultiTurnResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void deserializesResolvedSignal() throws Exception {
        String json = """
            {"signal":"RESOLVED","reasoning":"temp stable",\
             "actions":[{"actionType":"SET_TEMPERATURE","targetDeviceId":"d1",\
                         "parameters":{},"rationale":"cool down"}],\
             "escalationReason":null,"informationNeeded":null}""";
        MultiTurnResponse r = MAPPER.readValue(json, MultiTurnResponse.class);
        assertThat(r.signal()).isEqualTo(TurnSignal.RESOLVED);
        assertThat(r.actions()).hasSize(1);
        assertThat(r.actions().get(0).actionType()).isEqualTo("SET_TEMPERATURE");
    }

    @Test
    void deserializesContinueSignal() throws Exception {
        String json = """
            {"signal":"CONTINUE","reasoning":"need more data",\
             "actions":[],"escalationReason":null,\
             "informationNeeded":"What is the outdoor temperature?"}""";
        MultiTurnResponse r = MAPPER.readValue(json, MultiTurnResponse.class);
        assertThat(r.signal()).isEqualTo(TurnSignal.CONTINUE);
        assertThat(r.informationNeeded()).isEqualTo("What is the outdoor temperature?");
    }

    @Test
    void deserializesEscalateSignal() throws Exception {
        String json = """
            {"signal":"ESCALATE","reasoning":"too complex",\
             "actions":[],"escalationReason":"multiple failures",\
             "informationNeeded":null}""";
        MultiTurnResponse r = MAPPER.readValue(json, MultiTurnResponse.class);
        assertThat(r.signal()).isEqualTo(TurnSignal.ESCALATE);
        assertThat(r.escalationReason()).isEqualTo("multiple failures");
    }

    @Test
    void missingActionsDefaultsToEmptyList() throws Exception {
        String json = """
            {"signal":"ESCALATE","reasoning":"r","escalationReason":"e"}""";
        MultiTurnResponse r = MAPPER.readValue(json, MultiTurnResponse.class);
        assertThat(r.actions()).isEmpty();
    }

    @Test
    void invalidJsonReturnsNull() {
        MultiTurnResponse r = MultiTurnResponse.tryParse("not json", MAPPER);
        assertThat(r).isNull();
    }

    @Test
    void tryParseValidJson() {
        String json = """
            {"signal":"RESOLVED","reasoning":"done","actions":[],\
             "escalationReason":null,"informationNeeded":null}""";
        MultiTurnResponse r = MultiTurnResponse.tryParse(json, MAPPER);
        assertThat(r).isNotNull();
        assertThat(r.signal()).isEqualTo(TurnSignal.RESOLVED);
    }
}

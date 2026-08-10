package io.casehub.iot.webapp.app.resolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.webapp.resolution.TurnSignal;
import io.casehub.platform.agent.AgentEvent;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventCollectorTest {

    private final AgentEventCollector collector = new AgentEventCollector(new ObjectMapper());

    @Test
    void collectsTextDeltasIntoFullText() {
        Multi<AgentEvent> events = Multi.createFrom().items(
                new AgentEvent.TextDelta("{\"signal\":\"RESOLVED\","),
                new AgentEvent.TextDelta("\"reasoning\":\"done\","),
                new AgentEvent.TextDelta("\"actions\":[],\"escalationReason\":null,\"informationNeeded\":null}"),
                new AgentEvent.InvocationComplete(100, 50, 10, 0, 0, 0.01, 500, 480, "s1", 1, false)
        );

        CollectedTurn turn = collector.collect(events);

        assertThat(turn.response()).isNotNull();
        assertThat(turn.response().signal()).isEqualTo(TurnSignal.RESOLVED);
        assertThat(turn.response().reasoning()).isEqualTo("done");
    }

    @Test
    void capturesToolCallsAndResults() {
        Multi<AgentEvent> events = Multi.createFrom().items(
                new AgentEvent.ToolCallComplete(0, "tc1", "iot_get_state", "{\"deviceId\":\"d1\"}"),
                new AgentEvent.ToolResult("tc1", "{\"temp\":22}", false),
                new AgentEvent.TextDelta("{\"signal\":\"RESOLVED\",\"reasoning\":\"r\",\"actions\":[],\"escalationReason\":null,\"informationNeeded\":null}"),
                new AgentEvent.InvocationComplete(100, 50, 0, 0, 0, null, 400, 380, "s1", 1, false)
        );

        CollectedTurn turn = collector.collect(events);

        assertThat(turn.toolCalls()).hasSize(1);
        assertThat(turn.toolCalls().get(0).name()).isEqualTo("iot_get_state");
        assertThat(turn.toolCalls().get(0).result()).isEqualTo("{\"temp\":22}");
        assertThat(turn.toolCalls().get(0).isError()).isFalse();
    }

    @Test
    void capturesTokenCounts() {
        Multi<AgentEvent> events = Multi.createFrom().items(
                new AgentEvent.TextDelta("{\"signal\":\"ESCALATE\",\"reasoning\":\"r\",\"actions\":[],\"escalationReason\":\"e\",\"informationNeeded\":null}"),
                new AgentEvent.InvocationComplete(200, 100, 30, 50, 10, 0.05, 1000, 950, "s1", 1, false)
        );

        CollectedTurn turn = collector.collect(events);

        assertThat(turn.completion().inputTokens()).isEqualTo(200);
        assertThat(turn.completion().outputTokens()).isEqualTo(100);
        assertThat(turn.completion().thinkingTokens()).isEqualTo(30);
    }

    @Test
    void invalidJsonReturnsNullResponse() {
        Multi<AgentEvent> events = Multi.createFrom().items(
                new AgentEvent.TextDelta("not valid json"),
                new AgentEvent.InvocationComplete(10, 5, 0, 0, 0, null, 100, 90, "s1", 1, false)
        );

        CollectedTurn turn = collector.collect(events);

        assertThat(turn.response()).isNull();
        assertThat(turn.rawText()).isEqualTo("not valid json");
    }

    @Test
    void handlesToolCallErrorResult() {
        Multi<AgentEvent> events = Multi.createFrom().items(
                new AgentEvent.ToolCallComplete(0, "tc1", "iot_get_state", "{\"deviceId\":\"unknown\"}"),
                new AgentEvent.ToolResult("tc1", "Device not found", true),
                new AgentEvent.TextDelta("{\"signal\":\"ESCALATE\",\"reasoning\":\"r\",\"actions\":[],\"escalationReason\":\"e\",\"informationNeeded\":null}"),
                new AgentEvent.InvocationComplete(50, 30, 0, 0, 0, null, 200, 180, "s1", 1, false)
        );

        CollectedTurn turn = collector.collect(events);

        assertThat(turn.toolCalls()).hasSize(1);
        assertThat(turn.toolCalls().get(0).isError()).isTrue();
        assertThat(turn.toolCalls().get(0).result()).isEqualTo("Device not found");
    }
}

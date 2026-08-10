package io.casehub.iot.webapp.app.resolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.webapp.resolution.MultiTurnResponse;
import io.casehub.iot.webapp.resolution.ToolCall;
import io.casehub.platform.agent.AgentEvent;
import io.smallrye.mutiny.Multi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AgentEventCollector {

    private final ObjectMapper objectMapper;

    public AgentEventCollector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CollectedTurn collect(Multi<AgentEvent> events) {
        StringBuilder text = new StringBuilder();
        Map<String, ToolCall> pendingToolCalls = new HashMap<>();
        List<ToolCall> completedToolCalls = new ArrayList<>();
        AgentEvent.InvocationComplete completion = null;

        for (AgentEvent event : events.subscribe().asIterable()) {
            switch (event) {
                case AgentEvent.TextDelta d -> text.append(d.text());
                case AgentEvent.ToolCallComplete tc -> pendingToolCalls.put(
                        tc.id(), new ToolCall(tc.name(), tc.arguments(), null, false));
                case AgentEvent.ToolResult tr -> {
                    ToolCall pending = pendingToolCalls.remove(tr.toolCallId());
                    if (pending != null) {
                        completedToolCalls.add(new ToolCall(
                                pending.name(), pending.arguments(), tr.content(), tr.isError()));
                    }
                }
                case AgentEvent.InvocationComplete ic -> completion = ic;
                default -> {}
            }
        }

        String rawText = text.toString();
        MultiTurnResponse response = MultiTurnResponse.tryParse(rawText, objectMapper);
        return new CollectedTurn(response, rawText, List.copyOf(completedToolCalls), completion);
    }
}

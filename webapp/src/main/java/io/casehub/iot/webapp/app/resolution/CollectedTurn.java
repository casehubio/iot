package io.casehub.iot.webapp.app.resolution;

import io.casehub.iot.webapp.resolution.MultiTurnResponse;
import io.casehub.iot.webapp.resolution.ToolCall;
import io.casehub.platform.agent.AgentEvent;

import java.util.List;

public record CollectedTurn(
        MultiTurnResponse response,
        String rawText,
        List<ToolCall> toolCalls,
        AgentEvent.InvocationComplete completion
) {}

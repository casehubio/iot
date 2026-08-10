package io.casehub.iot.webapp.resolution;

import java.time.Instant;
import java.util.List;

public record ConversationTurn(
        int turnNumber,
        String query,
        String responseText,
        List<ToolCall> toolCalls,
        TurnSignal signal,
        Instant timestamp,
        int inputTokens,
        int outputTokens
) {
    public ConversationTurn {
        toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
    }
}

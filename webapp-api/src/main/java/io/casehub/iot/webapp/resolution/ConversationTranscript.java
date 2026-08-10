package io.casehub.iot.webapp.resolution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ConversationTranscript {

    private final List<ConversationTurn> turns = new ArrayList<>();
    private int totalInputTokens;
    private int totalOutputTokens;
    private int totalThinkingTokens;
    private long totalDurationMs;
    private Double totalCostUsd;

    public void addTurn(ConversationTurn turn) {
        turns.add(turn);
        totalInputTokens += turn.inputTokens();
        totalOutputTokens += turn.outputTokens();
    }

    public void addInvocationStats(int thinkingTokens, long durationMs, Double costUsd) {
        totalThinkingTokens += thinkingTokens;
        totalDurationMs += durationMs;
        if (costUsd != null) {
            totalCostUsd = (totalCostUsd != null ? totalCostUsd : 0.0) + costUsd;
        }
    }

    public List<ConversationTurn> turns() { return Collections.unmodifiableList(turns); }
    public int turnCount() { return turns.size(); }
    public int totalInputTokens() { return totalInputTokens; }
    public int totalOutputTokens() { return totalOutputTokens; }
    public int totalThinkingTokens() { return totalThinkingTokens; }
    public long totalDurationMs() { return totalDurationMs; }
    public Double totalCostUsd() { return totalCostUsd; }
}

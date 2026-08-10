package io.casehub.iot.webapp.app.resolution;

import io.casehub.iot.webapp.resolution.ConversationTranscript;
import io.casehub.iot.webapp.resolution.ConversationTurn;
import io.casehub.iot.webapp.resolution.MultiTurnResponse;
import io.casehub.iot.webapp.resolution.PlannedActionSpec;

import java.time.Instant;
import java.util.List;

public final class MultiTurnResolutionState {

    private final ConversationTranscript transcript;
    private int turnCount;
    private List<PlannedActionSpec> resolution;
    private String escalationReason;
    private MultiTurnResponse lastResponse;

    public MultiTurnResolutionState(ConversationTranscript transcript) {
        this.transcript = transcript;
    }

    public void addTurn(String query, CollectedTurn collectedTurn) {
        turnCount++;
        lastResponse = collectedTurn.response();
        var turn = new ConversationTurn(
                turnCount, query,
                collectedTurn.rawText(),
                collectedTurn.toolCalls(),
                collectedTurn.response() != null ? collectedTurn.response().signal() : null,
                Instant.now(),
                collectedTurn.completion() != null ? collectedTurn.completion().inputTokens() : 0,
                collectedTurn.completion() != null ? collectedTurn.completion().outputTokens() : 0);
        transcript.addTurn(turn);
        if (collectedTurn.completion() != null) {
            transcript.addInvocationStats(
                    collectedTurn.completion().thinkingTokens(),
                    collectedTurn.completion().durationMs(),
                    collectedTurn.completion().totalCostUsd());
        }
    }

    public MultiTurnResolutionState withResolution(List<PlannedActionSpec> actions) {
        this.resolution = actions;
        return this;
    }

    public MultiTurnResolutionState withEscalation(String reason) {
        this.escalationReason = reason;
        return this;
    }

    public boolean isFirstTurn() { return turnCount == 0; }
    public boolean isTerminal() { return resolution != null || escalationReason != null; }
    public int turnCount() { return turnCount; }
    public List<PlannedActionSpec> resolution() { return resolution; }
    public String escalationReason() { return escalationReason; }
    public MultiTurnResponse lastResponse() { return lastResponse; }
    public ConversationTranscript transcript() { return transcript; }
}

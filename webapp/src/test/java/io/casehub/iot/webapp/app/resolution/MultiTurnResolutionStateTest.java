package io.casehub.iot.webapp.app.resolution;

import io.casehub.iot.webapp.resolution.ConversationTranscript;
import io.casehub.iot.webapp.resolution.MultiTurnResponse;
import io.casehub.iot.webapp.resolution.PlannedActionSpec;
import io.casehub.iot.webapp.resolution.TurnSignal;
import io.casehub.platform.agent.AgentEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MultiTurnResolutionStateTest {

    @Test
    void startsAsFirstTurnNotTerminal() {
        var state = new MultiTurnResolutionState(new ConversationTranscript());
        assertThat(state.isFirstTurn()).isTrue();
        assertThat(state.isTerminal()).isFalse();
    }

    @Test
    void withResolutionIsTerminal() {
        var state = new MultiTurnResolutionState(new ConversationTranscript());
        var actions = List.of(new PlannedActionSpec("TURN_OFF", "d1", Map.of(), "reason"));
        var resolved = state.withResolution(actions);
        assertThat(resolved.isTerminal()).isTrue();
        assertThat(resolved.resolution()).isEqualTo(actions);
    }

    @Test
    void withEscalationIsTerminal() {
        var state = new MultiTurnResolutionState(new ConversationTranscript());
        var escalated = state.withEscalation("too complex");
        assertThat(escalated.isTerminal()).isTrue();
        assertThat(escalated.escalationReason()).isEqualTo("too complex");
    }

    @Test
    void addTurnIncrementsTurnCount() {
        var state = new MultiTurnResolutionState(new ConversationTranscript());
        assertThat(state.isFirstTurn()).isTrue();

        var response = new MultiTurnResponse(TurnSignal.CONTINUE, "r", List.of(), null, "need data");
        var completion = new AgentEvent.InvocationComplete(50, 30, 0, 0, 0, null, 200, 180, "s", 1, false);
        var turn = new CollectedTurn(response, "{}", List.of(), completion);

        state.addTurn("query", turn);

        assertThat(state.isFirstTurn()).isFalse();
        assertThat(state.turnCount()).isEqualTo(1);
        assertThat(state.lastResponse()).isEqualTo(response);
        assertThat(state.transcript().turnCount()).isEqualTo(1);
    }

    @Test
    void transcriptAccumulatesTokensAcrossTurns() {
        var state = new MultiTurnResolutionState(new ConversationTranscript());

        var r1 = new MultiTurnResponse(TurnSignal.CONTINUE, "r", List.of(), null, null);
        var c1 = new AgentEvent.InvocationComplete(100, 50, 10, 0, 0, 0.01, 500, 480, "s", 1, false);
        state.addTurn("q1", new CollectedTurn(r1, "{}", List.of(), c1));

        var r2 = new MultiTurnResponse(TurnSignal.RESOLVED, "done", List.of(), null, null);
        var c2 = new AgentEvent.InvocationComplete(120, 60, 5, 0, 0, 0.02, 400, 380, "s", 2, false);
        state.addTurn("q2", new CollectedTurn(r2, "{}", List.of(), c2));

        assertThat(state.transcript().totalInputTokens()).isEqualTo(220);
        assertThat(state.transcript().totalOutputTokens()).isEqualTo(110);
        assertThat(state.transcript().totalThinkingTokens()).isEqualTo(15);
        assertThat(state.transcript().totalCostUsd()).isEqualTo(0.03);
    }
}

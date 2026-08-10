package io.casehub.iot.webapp.resolution;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationTranscriptTest {

    @Test
    void accumulatesTurnsAndTokens() {
        var transcript = new ConversationTranscript();
        transcript.addTurn(new ConversationTurn(1, "q1", "r1", List.of(),
                TurnSignal.CONTINUE, Instant.now(), 100, 50));
        transcript.addTurn(new ConversationTurn(2, "q2", "r2", List.of(),
                TurnSignal.RESOLVED, Instant.now(), 120, 60));

        assertThat(transcript.turnCount()).isEqualTo(2);
        assertThat(transcript.totalInputTokens()).isEqualTo(220);
        assertThat(transcript.totalOutputTokens()).isEqualTo(110);
    }

    @Test
    void accumulatesInvocationStats() {
        var transcript = new ConversationTranscript();
        transcript.addInvocationStats(10, 500, 0.01);
        transcript.addInvocationStats(15, 600, 0.02);

        assertThat(transcript.totalThinkingTokens()).isEqualTo(25);
        assertThat(transcript.totalDurationMs()).isEqualTo(1100);
        assertThat(transcript.totalCostUsd()).isEqualTo(0.03);
    }

    @Test
    void turnsAreUnmodifiable() {
        var transcript = new ConversationTranscript();
        transcript.addTurn(new ConversationTurn(1, "q", "r", List.of(),
                TurnSignal.RESOLVED, Instant.now(), 10, 5));
        assertThrows(UnsupportedOperationException.class,
                () -> transcript.turns().add(null));
    }

    @Test
    void recordsToolCalls() {
        var toolCalls = List.of(
                new ToolCall("iot_get_state", "{\"deviceId\":\"d1\"}", "{\"temp\":22}", false));
        var transcript = new ConversationTranscript();
        transcript.addTurn(new ConversationTurn(1, "q", "r", toolCalls,
                TurnSignal.CONTINUE, Instant.now(), 50, 30));

        assertThat(transcript.turns().get(0).toolCalls()).hasSize(1);
        assertThat(transcript.turns().get(0).toolCalls().get(0).name()).isEqualTo("iot_get_state");
    }

    @Test
    void nullCostAccumulation() {
        var transcript = new ConversationTranscript();
        transcript.addInvocationStats(10, 100, null);
        assertThat(transcript.totalCostUsd()).isNull();

        transcript.addInvocationStats(10, 100, 0.05);
        assertThat(transcript.totalCostUsd()).isEqualTo(0.05);
    }

    @Test
    void emptyTranscript() {
        var transcript = new ConversationTranscript();
        assertThat(transcript.turnCount()).isZero();
        assertThat(transcript.totalInputTokens()).isZero();
        assertThat(transcript.totalOutputTokens()).isZero();
        assertThat(transcript.turns()).isEmpty();
    }
}

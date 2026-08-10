# Multi-Turn LLM Conversation for Complex Resolutions

**Date:** 2026-08-08
**Issue:** casehubio/iot#83
**Parent spec:** `docs/specs/issue-63-llm-resolution-agent/2026-07-29-llm-resolution-agent-design.md` §9

## Summary

The current `IoTAiResolutionAgent` uses single-shot LLM calls: one prompt,
one response, execute or escalate. This works for straightforward situations
with high CBR similarity, but fails for complex cases where the LLM needs to
gather additional device information, refine its plan based on constraints,
or adjust after observing execution results.

This spec adds multi-turn conversation support using the platform's existing
`AgentProvider.openSession()` / `AgentSession` infrastructure with IoT MCP
tools attached for device data access, orchestrated by a blocks loop pattern
for termination and state management.

## §1 Component Architecture

### Module placement

| Module | Component | Purpose |
|--------|-----------|---------|
| `webapp-api` (Tier 1) | `MultiTurnResponse` | Structured LLM output per turn: signal (CONTINUE/RESOLVED/ESCALATE), reasoning, actions, escalation reason, information needed |
| `webapp-api` (Tier 1) | `TurnSignal` | Enum: CONTINUE, RESOLVED, ESCALATE |
| `webapp-api` (Tier 1) | `ConversationTurn` | Immutable record of one turn: query, response text, tool calls, tool results, signal, timestamp, token counts |
| `webapp-api` (Tier 1) | `ConversationTranscript` | Ordered list of `ConversationTurn`s with aggregated token/cost totals |
| `webapp` | `MultiTurnResolutionState` | Mutable state for the conversation loop: turn count, resolution status, transcript. Lives in `webapp` because it depends on `CaseQueueEntry` and `CaseInstance` (CDI-tier types) |
| `webapp` | `AgentEventCollector` | Collects `Multi<AgentEvent>` stream into text, tool calls, tool results, and token counts. Parses final text into `MultiTurnResponse` |
| `webapp` | `IoTAiResolutionAgent` | Refactored: `processEntry()` uses conversation mode routing (single/multi/auto) |
| `webapp` | `IoTAiResolutionConfig` | Extended: `conversationMode()`, `maxConversationTurns()` |

### Key records

**`MultiTurnResponse`** — structured LLM output per turn:

```java
record MultiTurnResponse(
    TurnSignal signal,
    String reasoning,
    List<PlannedActionSpec> actions,
    String escalationReason,
    String informationNeeded
)

enum TurnSignal { CONTINUE, RESOLVED, ESCALATE }
```

**`ConversationTranscript`** — persisted to case working context:

```java
record ConversationTranscript(
    List<ConversationTurn> turns,
    int totalInputTokens,
    int totalOutputTokens,
    int totalThinkingTokens,
    long totalDurationMs,
    Double totalCostUsd
)

record ConversationTurn(
    int turnNumber,
    String query,
    String responseText,
    List<ToolCall> toolCalls,
    TurnSignal signal,
    Instant timestamp,
    int inputTokens,
    int outputTokens
)

record ToolCall(String name, String arguments, String result, boolean isError)
```

**`MultiTurnResolutionState`** — conversation loop state (webapp module):

Tracks `turnCount`, `resolution` (null until RESOLVED, then holds final
action list), `escalationReason` (null until ESCALATE or max turns),
`transcript` (growing `ConversationTranscript`), and `lastResponse`
(most recent `MultiTurnResponse` for follow-up context). Exposes
`isFirstTurn()` and `isTerminal()` for the loop.

---

## §2 Conversation Flow

```
processEntry(entry)
  │
  ├─ claim entry (unchanged)
  ├─ load case, CBR suggestions (unchanged)
  ├─ write pre-LLM escalation context (unchanged)
  │
  ├─ openSession(systemPrompt, iotMcpServer)
  │
  ├─ CONVERSATION LOOP (blocks LoopBuilder with custom backend)
  │   │
  │   ├─ Turn 1: session.query(caseContext + CBR + "gather info, propose plan")
  │   │   └─ Returns Multi<AgentEvent> — streamed events
  │   │   └─ AgentEventCollector gathers: TextDelta → full text,
  │   │      ToolCallComplete → tool invocations, ToolResult → tool outcomes,
  │   │      InvocationComplete → token counts
  │   │   └─ Collector parses full text as JSON → MultiTurnResponse
  │   │   └─ LLM may call MCP tools during this turn (tool calls handled
  │   │      by AgentSession internally — ToolResult events confirm outcomes)
  │   │
  │   ├─ Turn 2+: session.query(followUp based on lastResponse)
  │   │   └─ Same event collection and parsing
  │   │   └─ LLM signals: CONTINUE, RESOLVED, or ESCALATE
  │   │
  │   ├─ Termination (LoopBuilder.exitCondition):
  │   │   ├─ state.isTerminal() → RESOLVED or ESCALATE signalled
  │   │   ├─ maxIterations reached → escalate with transcript
  │   │   └─ Otherwise → CONTINUE → next turn
  │   │
  │   └─ Each turn: AgentEventCollector → ConversationTurn → append to transcript
  │
  ├─ session.close() (in finally block — see §2a Error Handling)
  ├─ persist ConversationTranscript to case working context
  └─ execute / escalate (based on final state)
```

### AgentEvent stream collection

`AgentSession.query()` returns `Multi<AgentEvent>`, not a parsed response.
The `AgentEventCollector` subscribes to this stream and collects events:

```java
class AgentEventCollector {
    // Subscribes to Multi<AgentEvent>, blocks until InvocationComplete
    CollectedTurn collect(Multi<AgentEvent> events) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        InvocationComplete completion = null;

        events.subscribe().asIterable().forEach(event -> {
            switch (event) {
                case TextDelta d    -> text.append(d.text());
                case ToolCallComplete tc -> toolCalls.add(
                    new ToolCall(tc.name(), tc.arguments(), null, false));
                case ToolResult tr  -> // match to pending tool call by id,
                                       // set result and isError
                case InvocationComplete ic -> completion = ic;
                default -> {} // ThinkingDelta, ToolCallDelta — ignored
            }
        });

        MultiTurnResponse response = parseResponse(text.toString());
        return new CollectedTurn(response, toolCalls, completion);
    }
}
```

The collector blocks the virtual thread until `InvocationComplete` arrives
(the terminal event). This is safe on virtual threads and consistent with
the existing blocking SPI convention (ADR-0005).

### Action execution stays outside the conversation

The LLM proposes actions through the conversation. Actual device commands
execute after the conversation loop concludes — same risk classification
and status guard as today. The LLM can read device state via MCP during
the conversation, but write actions go through the existing risk pipeline.

This preserves the safety invariant: no device commands bypass
`ActionRiskClassifier`. The MCP server exposed to the session provides
read-only tools (`iot_get_devices`, `iot_get_state`, `iot_get_history`)
but not `iot_send_command`.

### Structured output across turns

The system prompt instructs the LLM to respond with JSON matching the
`MultiTurnResponse` schema. `AgentEventCollector` extracts the full text
from `TextDelta` events and parses it via `ObjectMapper`. If parsing fails,
the turn is treated as ESCALATE with the raw text as the escalation reason.

Note: `AgentSession` does not support `responseSchema()` — structured
output enforcement is via prompt instruction only, unlike the single-shot
`Agent` which supports schema-constrained output. JSON parse failures are
a transient error path, not a design gap.

---

## §2a Error Handling

### Session lifecycle

`AgentSession` implements `AutoCloseable`. The session MUST be closed in a
`finally` block to prevent resource leaks:

```java
AgentSession session = agentProvider.openSession(init);
try {
    // conversation loop
} finally {
    session.close(Duration.ofSeconds(5));
}
```

### AgentSessionLimitException

`AgentProvider.openSession()` may throw `AgentSessionLimitException` if
the provider has reached its concurrent session limit. Handle by falling
back to single-shot `Agent.execute()` for this entry and logging a warning.
This is a graceful degradation — the entry still gets processed, just
without multi-turn capability.

### Per-turn transient failures

If `session.query()` throws a transient exception (HTTP timeout, connection
error, 429/5xx), apply the same retry logic as the current single-shot
path: up to 2 retries with exponential backoff (5s, 15s). If retries
exhaust, escalate with the transcript so far — the operator sees what
turns completed before the failure.

### Timeout sweep interaction

The existing timeout sweep runs in the same `@Scheduled` poll cycle. If
it escalates an entry that has an active conversation, the status guard
check at the end of the conversation loop catches this: the entry's status
is no longer CLAIMED in the AI resolution view, so action execution is
aborted. The conversation transcript is still persisted.

To prevent the timeout sweep from interrupting active conversations,
track in-progress session case IDs in a `ConcurrentHashMap<UUID, Instant>`
and skip entries in the sweep that are in the active-sessions map. The map
entry is added when the session opens and removed in the `finally` block.

---

## §3 AgentSession + MCP Wiring

The webapp already hosts the IoT MCP server (it adds `casehub-iot-mcp`
as a dependency with `quarkus-mcp-server-http`). The `AgentSession`
connects to it as a local HTTP MCP server.

```java
// Resolve the actual AgentMcpServer construction from the platform API
// The exact factory method depends on the AgentMcpServer.Http record's
// constructor — verify fields during implementation
AgentMcpServer mcpServer = new AgentMcpServer.Http(
    "http://localhost:" + httpPort + "/mcp/iot-readonly");

AgentSessionInit init = new AgentSessionInit(
    MULTI_TURN_SYSTEM_PROMPT,
    List.of(mcpServer),
    Duration.ofSeconds(config.timeoutSeconds()),
    "iot-resolution-" + entry.getCaseId());

AgentSession session = agentProvider.openSession(init);
```

### Read-only tool surface

A separate read-only MCP tool class `IoTReadOnlyMcpTools` exposes only
query operations. This class is registered as a distinct MCP endpoint
(`/mcp/iot-readonly`) so the resolution session cannot reach
`iot_send_command`:

```java
@McpResource(path = "/mcp/iot-readonly")
public class IoTReadOnlyMcpTools {
    // Delegates to the same DeviceRegistry and DeviceStateHistoryProvider
    // but only exposes: iot_get_devices, iot_get_state, iot_get_history
}
```

The full MCP endpoint (`/mcp`) remains available for external MCP clients.
The resolution session is wired to the read-only endpoint only.

### Identity context

The MCP tools are `@RolesAllowed` and tenancy-filtered via
`McpIdentityContext`. The session's HTTP calls to the local MCP endpoint
must carry the agent's identity. Two options:

1. **Bearer token in MCP server config** — if `AgentMcpServer.Http`
   supports auth headers, pass a service token that maps to the agent's
   tenancy
2. **Localhost bypass** — for local-only MCP calls, configure a
   `McpIdentityContext` that trusts localhost connections and injects the
   tenancy from the `correlationId` field

Verify which mechanism the platform supports during implementation. If
neither works, the read-only MCP tools can accept tenancy as an explicit
parameter (the agent passes `tenancyId` in every tool call).

---

## §4 Blocks Loop Integration

The blocks `LoopBuilder` wraps the conversation lifecycle. A custom
`ExecutionBackend<MultiTurnResolutionState>` implements each iteration
as a `session.query()` call. The loop framework handles iteration counting
and termination via `exitCondition()`.

```java
var loopModel = Patterns.<MultiTurnResolutionState>loop()
    .maxIterations(config.maxConversationTurns())
    .exitCondition(state -> state.isTerminal())
    .backend(sessionBackend(session, collector))
    .build();

Uni<ExecutionResult> result = loopModel
    .execute(initialState)    // blocks on virtual thread via .await()
    .await().atMost(Duration.ofSeconds(config.timeoutSeconds()));
```

### Custom `ExecutionBackend`

The backend implements the per-iteration logic. Each iteration:
1. Builds the query string (initial context for turn 1, follow-up for
   subsequent turns)
2. Calls `session.query(queryString)`
3. Collects `AgentEvent` stream via `AgentEventCollector`
4. Updates `MultiTurnResolutionState` with the parsed response

```java
ExecutionBackend<MultiTurnResolutionState> sessionBackend(
        AgentSession session, AgentEventCollector collector) {
    return (model, state) -> {
        String query = state.isFirstTurn()
            ? buildInitialQuery(state)
            : buildFollowUpQuery(state);

        Multi<AgentEvent> events = session.query(query);
        CollectedTurn turn = collector.collect(events);
        state.addTurn(query, turn);

        return switch (turn.response().signal()) {
            case RESOLVED -> Uni.createFrom().item(
                ExecutionResult.completed(state.withResolution(turn.response().actions())));
            case ESCALATE -> Uni.createFrom().item(
                ExecutionResult.completed(state.withEscalation(turn.response().escalationReason())));
            case CONTINUE -> Uni.createFrom().item(
                ExecutionResult.continued(state));
        };
    };
}
```

### `MultiTurnResolutionState`

Tracks: `turnCount` (current turn number), `resolution` (null until
RESOLVED, then holds final action list), `escalationReason` (null until
ESCALATE or max turns), `transcript` (growing `ConversationTranscript`),
`lastResponse` (most recent `MultiTurnResponse` for follow-up context).

### Termination

Runtime-configurable via `IoTAiResolutionConfig`:
- `maxConversationTurns` (default 5) → `LoopBuilder.maxIterations()`
- `exitCondition(state -> state.isTerminal())` — checks for RESOLVED
  or ESCALATE signal
- When `maxIterations` is reached without terminal signal, the loop
  completes and `MultiTurnResolutionState` is checked — if not terminal,
  escalate with "Max conversation turns exceeded" and the full transcript
- The existing `timeoutSeconds` applies across the whole conversation
  via `AgentSessionInit.timeout()` and the `.await().atMost()` on the
  loop execution

### Concurrency

The existing `Semaphore(maxConcurrentLlmCalls)` bounds single-shot calls.
Multi-turn sessions are more expensive — each session holds resources
across multiple turns. A separate `Semaphore(maxConcurrentSessions)`
(default: 1) bounds concurrent multi-turn conversations. This is
independent of the LLM call semaphore — the session makes its own LLM
calls internally via `AgentProvider`.

```properties
casehub.iot.ai-resolution.max-concurrent-sessions=1
```

When the session semaphore is unavailable, the entry falls back to
single-shot processing (same as `AgentSessionLimitException` handling).

### After the loop exits

- RESOLVED: risk-check all proposed actions → status guard → execute
  (same pipeline as today)
- ESCALATE: persist transcript to `AiEscalationContext` → escalate to
  operator-assisted view
- In both cases, `ConversationTranscript` is written to the case working
  context under key `aiConversationTranscript`

---

## §5 Backward Compatibility + Migration

### Configuration-driven routing

```properties
casehub.iot.ai-resolution.conversation-mode=auto
```

| Mode | Behaviour |
|------|-----------|
| `single` | Current behaviour — `Agent.execute()`, no session. Zero change. |
| `multi` | Always open a session, run the blocks loop. |
| `auto` (default) | Always open a session. Turn 1 includes full context. If the LLM resolves or escalates on turn 1, the session closes immediately (behaves like single-shot but with session overhead). If CONTINUE, the conversation continues. |

### Why `auto` always opens a session

The original auto-mode design started with single-shot `Agent.execute()`
and escalated to a session on CONTINUE. This creates a context
discontinuity: the session has no memory of turn 1 (which used a different
code path). Replaying turn 1's prompt into the session wastes tokens and
produces inconsistent context.

Instead, `auto` always opens a session. For simple cases (RESOLVED on
turn 1), the session overhead is one `openSession()` + one `close()` call.
The LLM call itself is the same cost either way. The simplicity and
context continuity justify the minor session overhead.

### `AiResolutionPlan` migration

`MultiTurnResponse` is a new record, not an extension of `AiResolutionPlan`.
The single-shot path (`conversation-mode=single`) continues to use
`Agent.execute()` with `AiResolutionPlan` response schema — no change.
The multi-turn path uses `MultiTurnResponse` parsed from the session's
text output.

Mapping: `Decision.EXECUTE` ≡ `TurnSignal.RESOLVED`,
`Decision.ESCALATE` ≡ `TurnSignal.ESCALATE`. No `CONTINUE` in single-shot.

### `AiEscalationContext` migration

`AiEscalationContext` is a Java record. Adding a field is a binary-breaking
change (constructor signature changes). To maintain backward compatibility:

- Add a new record `AiEscalationContextV2` that includes the
  `ConversationTranscript transcript` field
- The multi-turn path writes `AiEscalationContextV2`
- The single-shot path continues to write `AiEscalationContext`
- Both are stored in the case working context under different keys:
  `aiEscalationContext` (v1) and `aiEscalationContextV2` (v2)
- Consumer code checks for v2 first, falls back to v1

Alternatively, since the project is pre-release with no external consumers,
simply add the field to `AiEscalationContext` with a default value and
update all constructor call sites. Decide during implementation.

---

## §6 Observability + Transcript

### Metrics (extending #85 instrumentation)

| Metric | Type | Tags | Purpose |
|--------|------|------|---------|
| `casehub.iot.ai.resolution.conversation.turns` | Distribution summary | `outcome` | Turn count per conversation |
| `casehub.iot.ai.resolution.conversation.duration` | Timer | `outcome`, `mode` | Wall clock for full conversation |
| `casehub.iot.ai.resolution.conversation.tool.calls` | Counter | `tool` | MCP tool call frequency |
| `casehub.iot.ai.resolution.conversation.tokens` | Counter | `type` (input/output/thinking) | Token spend per conversation |
| `casehub.iot.ai.resolution.session.fallback` | Counter | `reason` (limit/semaphore) | How often multi-turn falls back to single-shot |

`InvocationComplete` events from `AgentSession` carry `inputTokens`,
`outputTokens`, `thinkingTokens`, `totalCostUsd`, `durationMs`,
`cacheReadTokens`, `cacheWriteTokens`. The `AgentEventCollector` captures
these per turn; `ConversationTranscript` aggregates across all turns.

### Transcript persistence

`ConversationTranscript` is written to the case working context under
key `aiConversationTranscript` on both resolution and escalation. On
escalation, the operator sees the full conversation: what the LLM asked
for, what it learned, what it proposed, and why it escalated.

---

## §7 Testing Strategy

### webapp-api (unit, no CDI)

- **`MultiTurnResponseTest`** — JSON deserialization of all three signals
  (CONTINUE, RESOLVED, ESCALATE), missing fields, invalid JSON fallback
- **`ConversationTranscriptTest`** — Turn accumulation, token/cost
  aggregation, tool call recording, serialization round-trip

### webapp (unit + integration, mocked AgentProvider)

| Test | What it verifies |
|------|------------------|
| `AgentEventCollectorTest` | Collects TextDelta into full text, matches ToolResult to ToolCallComplete by id, extracts token counts from InvocationComplete, handles parse failure gracefully |
| Single-shot happy path (single mode) | `Agent.execute()` path unchanged, no session opened |
| Auto mode single-turn resolve | Session opens → LLM returns RESOLVED on turn 1 → session closes, actions executed |
| Auto mode multi-turn | Session opens → CONTINUE → turn 2 with tool calls → RESOLVED on turn 3 |
| Multi-turn with tool calls | Session LLM calls `iot_get_state` (ToolCallComplete + ToolResult events) → proposes plan → RESOLVED |
| Multi-turn escalation | LLM signals ESCALATE on turn 3 → transcript persisted → entry escalated |
| Max turns exceeded | LLM returns CONTINUE 5 times → auto-escalate with transcript |
| Risk gate on multi-turn | Multi-turn RESOLVED but action is GateRequired → escalate with full transcript |
| Status guard race | Timeout sweep moves entry during conversation → status guard aborts before execution |
| Token/cost tracking | InvocationComplete events summed across turns → transcript totals correct → metrics recorded |
| Conversation mode config | `single` → no session; `multi` → always session; `auto` → session with possible single-turn exit |
| Session limit fallback | `AgentSessionLimitException` → graceful fallback to single-shot |
| Session semaphore exhausted | `maxConcurrentSessions` reached → fallback to single-shot |
| Session cleanup on error | Exception mid-conversation → session.close() called in finally |
| Per-turn transient retry | `session.query()` throws ConnectException → retry → success on second attempt |
| Timeout sweep skip | Active session case ID in map → sweep skips that entry |

### Not tested here

MCP tool execution (covered by `IoTDeviceMcpToolTest`), risk
classification (covered by `IoTActionRiskClassifierTest`), CBR retrieval
(covered by existing tests).

---

## §8 Scope Boundaries

**In scope:**
- `MultiTurnResponse`, `ConversationTurn`, `ConversationTranscript`
  records in `webapp-api`
- `MultiTurnResolutionState`, `AgentEventCollector` in `webapp`
- Refactored `IoTAiResolutionAgent` with conversation mode routing
- Blocks `LoopBuilder` with custom `ExecutionBackend` for session turns
- `AgentSession` + IoT read-only MCP endpoint wiring
- Extended `IoTAiResolutionConfig` with `conversationMode`,
  `maxConversationTurns`, `maxConcurrentSessions`
- Conversation metrics and transcript persistence
- Session lifecycle error handling, semaphore-based concurrency control

**Out of scope:**
- Promoting conversation abstractions to engine-api (future — when
  another CaseHub app needs multi-turn)
- Write-capable MCP tools in the conversation (device commands stay
  outside the conversation loop, behind the risk pipeline)
- Custom model fine-tuning or prompt versioning (#84)
- Engine `Agent` API changes — we use `AgentProvider` directly

---

## §9 Dependencies

### New dependency for webapp

`casehub-platform-agent-api` (already available as transitive via
platform). Direct dependency on `AgentProvider`, `AgentSession`,
`AgentSessionInit`, `AgentEvent`, `AgentMcpServer`,
`AgentSessionLimitException`.

### Blocks dependency

`casehub-blocks` — for `Patterns.loop()`, `LoopBuilder`,
`ExecutionBackend`, `ExecutionResult`. Already a SNAPSHOT dependency.

### No engine modifications required

The platform's `AgentProvider` and `AgentSession` APIs are sufficient.
No changes to engine-api or engine-queue.

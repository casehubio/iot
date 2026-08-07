# CBR Re-Routing on Context Changes — casehub-iot #82

**Date:** 2026-08-06
**Issue:** casehubio/iot#82
**Parent spec:** `docs/specs/issue-63-llm-resolution-agent/2026-07-29-llm-resolution-agent-design.md` §9

## Summary

When a case is routed to the AI resolution queue based on high CBR similarity,
and the case's working context subsequently changes (new sensor readings,
situation evolution), the original CBR stats become stale. The case may no longer
match the high-confidence band but stays in the AI queue because nobody
re-evaluates.

This spec adds an event-driven observer that detects working context changes on
AI-queued cases, re-runs CBR retrieval with current features, and escalates to
the appropriate operator queue when the similarity band drops.

## Architecture

Uses the engine's existing `CaseContextUpdatedEvent` CDI event — fired on every
working layer change but currently has zero observers. The observer is IoT-side
only, no engine changes required.

**Re-routing is downward only.** Cases can move from AI resolution to operator
queues when confidence drops. Cases in operator queues are never promoted to AI
resolution — operator workflow disruption outweighs the automation benefit, and
the right mechanism for increasing AI coverage is case base growth (new cases
start with higher confidence).

**Re-routing mechanism:** Direct `queueService.escalate()` — same API used by
the agent and timeout sweep. Labels become stale (still say
`iot-triage:ai-resolution`) but are self-correcting on the next lifecycle event.
Queue placement — what operators and the agent actually see — is immediately
correct.

---

## §1 Component and Module Placement

| Module | Component | Purpose |
|--------|-----------|---------|
| `webapp` | `IoTCbrReEvaluationObserver` | Observes `CaseContextUpdatedEvent`, re-evaluates CBR for AI-queued cases, escalates on band drop |

Package: `io.casehub.iot.webapp.app.resolution` — alongside `IoTAiResolutionAgent`.
Both are AI resolution infrastructure and share the same dependencies (queue
service, retrieval service, feature extractors, view IDs).

No `webapp-api` additions. The observer is CDI-dependent (event observation,
injected services). CBR retrieval and feature extraction logic it calls already
lives in `webapp-api` (Tier 1).

---

## §2 Event Flow

```
Sensor signal arrives
  → device state change → ganglia → situation context update
  → engine writes to case working layer
  → engine fires CaseContextChangedEvent on event bus
  → CaseContextChangedEventHandler processes rules/goals
  → fires CaseContextUpdatedEvent(caseId, "working", tenancyId)

IoTCbrReEvaluationObserver receives CaseContextUpdatedEvent
  → filter: changedLayer == "working"?
  → debounce: re-evaluated this case within last 30 seconds?
  → load case from CaseInstanceCache
  → is this case in the AI resolution queue? (query queue service)
  → resolve CaseDefinition via CaseDefinitionRegistry (for CbrConfig)
  → if no CbrConfig on definition → return (non-CBR case type)
  → extract features from current working context (same as agent's extractFeatures())
  → run CBR retrieval with current features via IoTCbrRetrievalService
  → compute new similarity band and outcome consistency
  → compare with current band (read cbrBestSimilarity from working context)
  → if band dropped below AI threshold:
      1. update cbrBestSimilarity, cbrMatchCount, cbrOutcomeConsistency
         in working context
      2. find the queue entry for this case
      3. determine target view (operator-assisted or operator-manual)
      4. queueService.escalate(entryId, tenancyId, targetViewId)
      5. log re-route with old/new similarity values
  → if band unchanged: no-op
```

**No infinite loop risk.** The observer writes CBR stats via
`CaseContext.set()` and calls `escalate()`. Neither fires a
`CaseContextUpdatedEvent` — that event originates from the engine's
`CaseContextChangedEventHandler`, which is triggered by Vert.x event bus
messages (`casehub.context.changed`), not by direct context writes.

---

## §3 Filtering and Debouncing

The observer receives `CaseContextUpdatedEvent` for every working layer change
on every case. Three filters reduce this to the small set that matters.

**Filter 1 — Layer check (instant, no I/O):**
```java
if (!"working".equals(event.changedLayer())) return;
```

**Filter 2 — AI queue membership (one query):**
Query `queueService.findByView(aiResolutionViewId, tenancyId)` and find entries
matching `event.caseId()`. If no match → return. Same query the agent's
`sweepStaleEntries()` already runs every 10 seconds.

**Filter 3 — Debounce (in-memory, per-case):**
`ConcurrentHashMap<UUID, Instant>` tracks the last re-evaluation time per case.
Skip if re-evaluated within the last 30 seconds.

Why 30 seconds:
- Sensor readings arrive every few minutes — 30s catches bursts without
  missing real drift
- CBR retrieval is a database query — prevents hammering the store during
  rapid context updates
- Agent poll interval is 10s — 30s means at most one re-evaluation per
  ~3 poll cycles, responsive enough for non-safety cases

Stale entries are removed from the debounce map when the case is no longer in
the AI queue (either re-routed or resolved).

**Net effect:** For a deployment with 5 cases in the AI resolution queue and
sensor readings every 2 minutes, the observer runs CBR retrieval at most once
per 30 seconds per case — roughly 10 retrievals per minute at peak. Negligible
next to the agent's own CBR calls.

---

## §4 Band Comparison and Target View Selection

The observer computes the new similarity band using the same thresholds as
`IoTTriageLabelRules`:

- **HIGH:** `similarity >= aiMinSimilarity AND consistency >= aiMinConsistency`
  → ai-resolution
- **MEDIUM:** `similarity >= 0.5 AND NOT HIGH` → operator-assisted
- **LOW:** `similarity < 0.5` → operator-manual

Thresholds are read from `IoTTriageConfig` — the same `@ConfigMapping` the
label rules use. No duplicated threshold values.

### Target view selection (downward only)

| Current band | New band | Action |
|-------------|----------|--------|
| HIGH | HIGH | no-op |
| HIGH | MEDIUM | escalate → `iot-operator-assisted` |
| HIGH | LOW | escalate → `iot-operator-manual` |

The observer only acts when the case is in the AI resolution view and the band
drops. Cases in operator views are never touched (Filter 2 in §3 excludes them).

### View ID resolution

The observer resolves `aiResolutionViewId`, `operatorAssistedViewId`, and
`operatorManualViewId` at `@PostConstruct` from
`SubjectViewStore.findByTenancy()` — same pattern as
`IoTAiResolutionAgent.init()`. Three lines each, duplicated rather than
abstracted — the beans have different lifecycles and the shared code is trivial.

---

## §5 Interaction with the AI Resolution Agent

The observer and agent both act on entries in the AI resolution queue. Three
scenarios, all handled by existing concurrency controls:

**Scenario 1 — PENDING entry re-routed before claim:**
Observer moves the entry to operator-assisted. Agent's next poll calls
`findPending()` — the entry is gone. No interaction. Highest-value scenario:
the agent never wastes an LLM call.

**Scenario 2 — CLAIMED entry re-routed during LLM call:**
Observer moves the entry while the agent is mid-`callLlmWithRetry()`. LLM
call completes, agent reaches the status guard:
```java
private boolean statusGuardPasses(CaseQueueEntry entry) {
    return queueService.findByView(aiResolutionViewId, tenancyId).stream()
                       .anyMatch(e -> e.getId().equals(entry.getId())
                                      && e.getStatus() == QueueEntryStatus.CLAIMED);
}
```
Entry is no longer in the AI view → guard fails → agent aborts. No device
commands executed. Same pattern as the timeout sweep race.

**Scenario 3 — CLAIMED entry re-routed after action execution:**
Agent has already executed actions successfully. Entry stays CLAIMED (waiting
for feedback loop). Observer detects context drift, re-routes to operator.
Correct behaviour — the AI's actions didn't resolve the situation. The
`aiResolutionResults` and `aiEscalationContext` in the working context serve
as handoff context for the operator.

**No coordination needed.** The observer and agent interact through queue
state, not shared in-memory state. The existing status guard and
`CaseQueueService` concurrency controls handle all races.

---

## §6 Metrics

Consistent with the agent's existing `casehub.iot.ai.resolution.*` namespace:

| Metric | Type | Tags | Purpose |
|--------|------|------|---------|
| `casehub.iot.ai.resolution.reevaluation.checked` | Counter | — | Events that passed all filters and ran CBR retrieval |
| `casehub.iot.ai.resolution.reevaluation.rerouted` | Counter | `from.band`, `to.band`, `target.view` | Actual re-routes |
| `casehub.iot.ai.resolution.reevaluation.debounced` | Counter | — | Events skipped by debounce (tuning signal) |

Three counters. No timers — CBR retrieval has its own timing. No gauges — the
debounce map size is bounded by the AI queue size.

The `rerouted` counter with band tags gives operators visibility into why cases
are re-routed: `from.band=high, to.band=medium` (gradual drift) vs
`from.band=high, to.band=low` (sudden context change).

---

## §7 Configuration

No new configuration. The observer uses existing config:

| Property | Source | Purpose |
|----------|--------|---------|
| `casehub.iot.triage.ai-resolution.min-similarity` | `IoTTriageConfig` | HIGH band floor |
| `casehub.iot.triage.ai-resolution.min-consistency` | `IoTTriageConfig` | HIGH band consistency floor |
| `casehub.iot.tenancy-id` | Platform convention | Tenancy scoping |

The debounce interval (30s) is a named constant, not configurable initially —
no calibration data to justify runtime tuning. Promote to config if operational
experience suggests different deployments need different values.

---

## §8 Testing Strategy

### webapp (integration, CDI)

**`IoTCbrReEvaluationObserverTest`:**

1. **Band drop HIGH → MEDIUM:** Seed CBR case base for high similarity. Create
   case, route to AI queue. Update working context to shift features. Fire
   `CaseContextUpdatedEvent`. Assert entry moved to `iot-operator-assisted`.
   Assert `cbrBestSimilarity` updated in working context.

2. **Band drop HIGH → LOW:** Same setup, shift features further. Assert entry
   moved to `iot-operator-manual`.

3. **Band unchanged — no-op:** Context changes but features still produce high
   similarity. Assert entry stays in `iot-ai-resolution`. Assert no `escalate()`
   call.

4. **Non-working layer change — filtered out:** Fire event with
   `changedLayer = "episodic"`. Assert no CBR retrieval, no queue query.

5. **Case not in AI queue — filtered out:** Fire event for a case in
   `iot-operator-assisted`. Assert no CBR retrieval.

6. **Debounce — rapid events coalesced:** Fire two events for same case within
   30 seconds. Assert CBR retrieval runs once.

7. **PENDING entry re-routed:** Entry is PENDING (not yet claimed). Context
   changes, band drops. Assert entry moved before any agent interaction.

8. **CLAIMED entry re-routed:** Entry is CLAIMED. Context changes, band drops.
   Assert entry moved. Verify entry no longer in AI view with CLAIMED status
   (agent's status guard would fail).

9. **Observer disabled when views not resolved:** `iot-ai-resolution` view
   doesn't exist at startup. Assert observer is a no-op, no errors on events.

### Not tested here

CBR retrieval (covered by `IoTCbrRetrievalServiceTest`), feature extraction
(covered by `IoTCbrFeatureExtractorsTest`), label rules (covered by
`IoTTriageLabelRulesTest`), agent behaviour (covered by
`IoTAiResolutionAgentTest`).

---

## §9 Scope Boundaries

**In scope:**
- `IoTCbrReEvaluationObserver` with event-driven detection, filtering,
  debouncing, CBR re-evaluation, and direct queue escalation
- Downward-only re-routing (AI → operator queues)
- Metrics for observability
- Integration tests for all paths

**Out of scope:**
- Upward re-routing (operator → AI) — excluded by design decision
- Engine-side `PER_EVALUATION` CBR re-injection — future cross-repo work
  that would replace the IoT-specific observer with a general mechanism
- Label consistency — labels remain stale after re-routing until the next
  lifecycle event; accepted trade-off for simplicity
- Multi-turn LLM conversation (#83)
- Custom model fine-tuning or prompt versioning (#84)

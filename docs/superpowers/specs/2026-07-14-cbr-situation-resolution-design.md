# CBR Situation Resolution and Case Queue Pipeline — casehub-iot #50

**Date:** 2026-07-14
**Issue:** casehubio/iot#50
**Epic:** casehubio/iot#48 (Case-Based Reasoning for IoT situation handling)

## Summary

When a ganglia fires a situation, retrieve similar past situations from the case
base and surface resolution suggestions. Suggestions are consumed by both human
operators (via REST/UI) and AI agents (via case queues with LLM resolution).

This spec covers the full pipeline end-to-end — from CBR retrieval to automated
resolution — across multiple repos. Issue #50 implements the retrieval service
and human suggestion UI. Queue infrastructure, case triage, and LLM resolution
are delegated to cross-repo issues.

## Prerequisites (already built)

- **#49** — CBR infrastructure: `JpaCbrCaseMemoryStore`, feature schemas, CbrConfig wiring
- **#57** — Case file population: `IoTCaseInputContributor` feeds deviceClass, roomType,
  eventTimestamp into the case working layer

## Prerequisites (unbuilt — existing TODOs)

The webapp has 13 `TODO` comments across `CaseResource`, `WorkItemResource`,
`SituationResource`, and `HealthResource` indicating missing engine/work/RAS
integration. The suggestions endpoint in §2 does NOT depend on these — it uses
`CaseInstanceCache.get(caseId)` directly to load case instances. Engine case
creation and caching (#49, #57) are the actual prerequisites.

Full `CaseResource.list()` and `CaseResource.get()` engine integration is
independent work tracked separately. The suggestions endpoint can ship before
those TODOs are resolved.

## Garden Context

- **GE-20260612-bd3b4d** — Degenerate CBR: trust-scored routing is Retain+Reuse only.
  This design adds Retrieve (retrieval service) and enables Reuse (LLM resolution).
- **GE-20260713-b879b2** — H2 JSONB → TEXT: use TEXT for JSON-serialized JPA columns
  to avoid H2 deserialization failures in tests.

---

## §1 CBR Retrieval Service (webapp-api, Tier 1)

**Scope: #50**

Core service that queries the case base for similar past resolutions. Lives in
`webapp-api` (Tier 1 — no JPA, no CDI annotations) so it's consumable by both
REST endpoints and programmatic callers like LLM agents.

### IoTCbrRetrievalService

Takes three inputs:
- **CbrConfig** — from the `CaseDefinition` via `CaseDefinitionRegistry`. Contains
  weights, topK, minSimilarity, vectorWeight, domain, caseType. Single source of
  truth — the same config used by `HvacAnomalyCaseHub.augment()` for the retain path.
- **Feature map** — extracted from the case's working layer via `IoTCbrFeatureExtractors`
  (returns `Map<String, Object>`)
- **Tenant ID** — for multi-tenant filtering

Internally:
1. Looks up `CbrFeatureSchema` for the case type from `IoTCbrFeatureSchemas`
2. Converts raw features via `FeatureValue.toFeatureMap(features)`
3. Builds query:
   ```java
   CbrQuery.of(tenantId, new MemoryDomain(cbrConfig.domain()),
                cbrConfig.caseType(), featureMap, cbrConfig.topK())
       .withMinSimilarity(cbrConfig.minSimilarity())
       .withWeights(cbrConfig.weights())
       .withVectorWeight(cbrConfig.vectorWeight())
       .withRetrievalMode(cbrConfig.vectorWeight() > 0.0
           ? RetrievalMode.HYBRID : RetrievalMode.FEATURE_ONLY)
   ```
4. Calls `CbrCaseMemoryStore.retrieveSimilar(query, PlanCbrCase.class)`
5. Maps `List<ScoredCbrCase<PlanCbrCase>>` to `List<ResolutionSuggestion>`

Using `CbrConfig` directly eliminates weight synchronization between retain and
retrieve paths — both read from the same `CaseDefinition`. The `vectorWeight` and
`retrievalMode` are derived from the same config, ensuring the retrieve path
matches the retain path (e.g., `vectorWeight(0.0)` → `FEATURE_ONLY`).

### ResolutionSuggestion (Tier 1 record)

```java
record ResolutionSuggestion(
    String caseId,
    double similarityScore,
    String problem,
    String solution,
    String outcome,
    Double confidence,
    Map<String, Object> matchedFeatures,
    Map<String, Double> featureSimilarities,
    List<PlanTrace> planSteps
)
```

Uses `PlanTrace` directly from `neocortex-memory-api` — the type already carried
by `PlanCbrCase`. No wrapper or mapping layer needed.

### Why Tier 1

The retrieval logic is pure computation — extract features, build query, map results.
`CbrCaseMemoryStore` is injected by the caller (CDI layer in webapp). Keeps the service
testable without a CDI container and consumable by LLM agents.

---

## §2 Suggestion REST Endpoint (webapp)

**Scope: #50**

### Scope note

Issue #50 specifies suggestions on `SituationResource`. This spec surfaces them
on `CaseResource` because CBR retrieval operates on case context — features are
extracted from the case's working layer, and the case base stores resolved cases,
not situations. When a situation fires with `TriggerAction.CreateCase`, the case
exists immediately. The situation detail view links to the associated case where
suggestions are available.

Native situation-level suggestion surfacing (without navigating to the case) is
a follow-up once `SituationResource.listActive()` is built — tracked as a
separate issue.

### GET /api/cases/{caseId}/suggestions

Added to the existing `CaseResource`. Uses `CaseInstanceCache.get(caseId)` directly
to load the case instance — independent of the stub `list()` and `get()` methods.

Flow:
1. Load the case by ID via `CaseInstanceCache.get(caseId)`
2. Read the case's working layer (`caseInstance.getCaseContext().layer("working")`)
3. Determine case type from `caseInstance.getCaseMetaModel().getName()`
4. Look up `CaseDefinition` via `CaseDefinitionRegistry.findByName(caseType)`
5. Call the appropriate `IoTCbrFeatureExtractors` method based on case type
6. Pass (cbrConfig, features, tenantId) to `IoTCbrRetrievalService`
7. Return `List<ResolutionSuggestion>` as JSON

Response:
```json
{
  "caseId": "...",
  "caseType": "hvac-anomaly",
  "suggestionCount": 3,
  "suggestions": [
    {
      "caseId": "past-case-uuid",
      "similarityScore": 0.87,
      "problem": "Sustained temperature rise in living room",
      "solution": "HVAC filter was blocked — replaced filter, reset system",
      "outcome": "RESOLVED",
      "confidence": 0.95,
      "matchedFeatures": { "deviceClass": "thermostat", "roomType": "living_room" },
      "featureSimilarities": { "deviceClass": 1.0, "roomType": 1.0, "hourOfDay": 0.82 },
      "planSteps": [
        { "bindingName": "check-filter", "capabilityName": "device-control",
          "workerName": "set-temperature", "stepOutcome": "SUCCESS",
          "priority": 1, "parameters": {} }
      ]
    }
  ]
}
```

### POST /api/cases/{caseId}/suggestions/{pastCaseId}/accept

Copies the plan steps from the selected past resolution into the current case as
planned actions. Uses the past case's `caseId` (stable identifier) instead of a
positional index.

**PlanTrace → PlannedAction mapping:**
- `description` → `"{capabilityName} via {workerName}"`
- `actionType` → `capabilityName`
- `parameters` → `PlanTrace.parameters()`

Accept does NOT auto-execute. It pre-fills the plan. Execution goes through the
normal action pipeline including `ActionRiskClassifier` gates.

**Idempotency:** The accept endpoint tracks which past cases have been accepted by
writing accepted `pastCaseId` values into the case's working layer context under
the key `acceptedSuggestions` (a `Set<String>`). On accept:
1. Read `acceptedSuggestions` from the working layer
2. If `pastCaseId` is already in the set → return 200, no action
3. If not → copy plan steps as `PlannedAction`s, add `pastCaseId` to the set, return 200

No new table needed — the case context working layer already supports arbitrary
operational state. No changes to `PlannedAction` required.

### No SSE

Suggestions are computed on-demand when the endpoint is called. Retrieval is fast
(SQL filter + Java scoring). SSE push becomes relevant when queue infrastructure
lands — triage results are pushed as queue entry events.

---

## §3 Human Suggestion UI (webapp TypeScript)

**Scope: #50**

Case detail page gets a new **"Resolution Suggestions"** panel between "Worker
Results" and "Actions".

### Dataset

```typescript
dataset("case-suggestions", "/api/cases/{caseId}/suggestions");
```

### Panel

- Table showing top-N suggestions sorted by similarity score descending
- Columns: similarity (percentage), problem summary, solution summary, outcome, confidence
- Expandable row detail: feature similarity breakdown, plan steps
- "Accept" button per suggestion → `POST .../suggestions/{pastCaseId}/accept` → pre-fills case action plan
- Panel header: suggestion count + best match score ("3 similar past cases (best: 87%)")

### Empty state

When case base is empty or no cases score above `minSimilarity`:
"No similar past resolutions found." — visible, not hidden, so operators know the
feature exists.

### Refresh

On-demand only (loaded when case detail opens). No polling.

---

## §4 Subject View Toolkit (casehub-platform)

**Scope: cross-repo — casehub-platform**

**Modules:** `platform-api` (SPI types), `platform-view` (core), `platform-view-jpa`
(JPA-backed), `platform-view-inmem` (in-memory, for test and Pi).

The original spec proposed a generic queue toolkit (`AbstractQueueEntity`,
`AbstractQueueService` with enqueue/claim/release/escalate). What shipped instead is
a **declarative label-based view system** — subjects appear in views as their labels
change, rather than being explicitly enqueued and claimed.

### SubjectViewSpec

```java
record SubjectViewSpec(
    UUID id,
    String name,
    String tenancyId,
    String labelPattern,   // e.g. "severity:high AND type:iot-alert"
    Path scope,
    String sortField,
    String sortDirection,
    String additionalConditions,
    Instant createdAt
) {}
```

A named, persisted filter definition. Operators and AI agents see cases that match
the label pattern — no explicit enqueue step. Cases enter and leave views as labels
are added or removed during case lifecycle.

### SubjectViewQuery<S>

```java
interface SubjectViewQuery<S> {
    List<S> findByView(SubjectViewSpec view);
    List<S> findByView(SubjectViewSpec view, int offset, int limit);
    long countByView(SubjectViewSpec view);
}
```

Generic — any domain provides a concrete implementation for its subject type.

### SubjectViewStore

```java
interface SubjectViewStore {
    SubjectViewSpec save(SubjectViewSpec spec);
    Optional<SubjectViewSpec> findById(UUID id);
    List<SubjectViewSpec> findByTenancy(String tenancyId);
    boolean delete(UUID id);
}
```

CRUD for view definitions. JPA and in-memory implementations provided by platform.

### Why views instead of queues

Queue semantics (claim/release/escalate) proved unnecessary for the case triage
use case. Operators need filtered views of active cases — the case lifecycle itself
handles state transitions (open → working → resolved). Label-based filtering is
more composable: operators create custom views by combining label predicates, without
predefined queue definitions.

Queue semantics remain available in the engine's local `engine/queue` module for
scenarios that need explicit claim/release (e.g., automated processing with
contention). This is engine-local, not a platform SPI.

---

## §5 Case Triage via Subject Views (engine)

**Scope: cross-repo — casehub-engine. Uses: §4 subject view toolkit**

The engine's `engine/queue` module provides case queue management with claim/release
semantics. The subject view toolkit (§4) provides the filtering layer — operators see
cases matching view specs, and the engine queue handles work assignment.

### CaseQueueViewManager

Bridges the subject view toolkit with the engine's case model. Implements
`SubjectViewQuery<CaseInstance>` — resolves view specs against active cases using
label matching.

### CaseQueueEntryManager

Engine-local queue entry management with state machine:
`PENDING → CLAIMED → COMPLETED` or `PENDING → ESCALATED`.

Operations: enqueue, claim (optimistic locking), release, complete, escalate.
These are engine concerns — not exposed as a platform SPI.

### CaseQueueRoutingStrategy SPI

In engine-api. `@DefaultBean` no-op: cases are not queued unless a consumer provides
an implementation. The IoT webapp provides the CBR-aware implementation (§6).

---

## §6 CBR-Aware Case Triage (IoT webapp)

**Scope: cross-repo issue → casehub-iot. Depends on: §1, §5**

### IoTCbrCaseQueueRoutingStrategy

`implements CaseQueueRoutingStrategy` — uses CBR similarity for routing decisions.

When a case is created:
1. Extract features from case context via `IoTCbrFeatureExtractors`
2. Look up `CbrConfig` from `CaseDefinition` via `CaseDefinitionRegistry`
3. Call `IoTCbrRetrievalService` for top-N similar past cases
4. Compute `ResolutionConfidence` from results
5. Route based on confidence level

### Error handling

The routing strategy catches all exceptions from CBR retrieval and falls back to
the `iot-operator-manual` queue:

```java
try {
    // steps 1-5 above
    return QueueRoutingDecision.enqueue(computedQueue, ...);
} catch (Exception e) {
    LOG.warn("CBR routing failed, falling back to manual queue", e);
    return QueueRoutingDecision.enqueue("iot-operator-manual",
        Set.of("iot-operator"), MEDIUM_PRIORITY,
        Map.of("routing-error", e.getClass().getSimpleName()));
}
```

Since `CaseQueueRouter` uses `@ObservesAsync` (§5), exceptions in the routing
strategy are swallowed by CDI's async infrastructure. Without this fallback, a
CBR store outage would cause all new cases to silently pile up unqueued —
created in the engine but invisible to the triage/resolution pipeline.

The `iot-operator-manual` queue is the correct fallback — it's the same queue
used for `ResolutionConfidence.level = NONE`, so operators already handle it.
The `routing-error` label makes failed-routing cases identifiable in the queue
listing.

### Routing rules

| Condition | Queue | Candidate Groups |
|-----------|-------|-------------------|
| Safety-critical case type (`safety-alert`) | `iot-immediate` | `iot-operator` |
| Best ≥ 0.85 similarity AND ≥ 80% outcome consistency | `iot-ai-resolution` | `iot-ai-agent` |
| Best 0.5–0.85 OR mixed outcomes | `iot-operator-assisted` | `iot-operator` |
| Best < 0.5 OR empty case base | `iot-operator-manual` | `iot-operator` |

Safety-critical case types never go to AI regardless of CBR confidence.

### ResolutionConfidence

```java
record ResolutionConfidence(
    double bestSimilarity,
    double outcomeConsistency,
    int matchCount,
    ConfidenceLevel level       // HIGH, MEDIUM, LOW, NONE
)
```

**outcomeConsistency formula:** `count(most_frequent_outcome) / total` using exact
string match on the `outcome` field of `PlanCbrCase`. Given 5 matches with outcomes
[RESOLVED, RESOLVED, RESOLVED_PARTIAL, RESOLVED, FAILED]: most frequent is RESOLVED
(3 occurrences), so consistency = 3/5 = 0.6. No outcome grouping (RESOLVED ≠
RESOLVED_PARTIAL) — semantic grouping adds complexity without calibration data.

Not just similarity — also outcome consistency. A 0.90 match where 3 of 4 past
cases resolved identically is HIGH. A 0.90 match with divergent outcomes is MEDIUM.

### Configuration

```properties
casehub.iot.queue.ai-resolution.candidate-groups=iot-ai-agent
casehub.iot.queue.ai-resolution.min-similarity=0.85
casehub.iot.queue.ai-resolution.min-consistency=0.80
casehub.iot.queue.operator-assisted.candidate-groups=iot-operator
casehub.iot.queue.safety-override-case-types=safety-alert
```

Thresholds are configurable — operators tune as the case base matures.

### Labels

Top suggestion's similarity score and match count are stored as labels on the
`CaseQueueEntry` for queue listing display without recomputing retrieval.

---

## §7 LLM Resolution Agent

**Scope: cross-repo issue → casehub-iot. Depends on: §1, §5, §6**

### IoTAiResolutionAgent

Observes `CaseQueueEntryCreated` for `queueName = "iot-ai-resolution"`:

```java
void onCaseQueueEntryCreated(@ObservesAsync CaseQueueEntryCreated event) {
    if (!"iot-ai-resolution".equals(event.entry().getQueueName())) return;
    // ...
}
```

Flow:
1. **Claim** the queue entry
2. **Write partial context** — store initial `AiEscalationContext` in the queue entry's
   `metadata` field before starting LLM interaction (ensures partial work survives timeout)
3. **Load suggestions** via `IoTCbrRetrievalService`
4. **Build LLM prompt** with current situation + top-N past resolutions + available actions
5. **LLM decides** — three autonomy levels:
   - **Plan selection** (HIGH confidence, ≥ 0.9 consistency): pick best past plan, validate context fit
   - **Plan adaptation** (HIGH confidence, context differs): adjust past plan parameters for current context
   - **Plan generation** (MEDIUM confidence): reason from CBR background, generate new plan
6. **Execute** via normal action pipeline. `ActionRiskClassifier` still gates every action.
   If any action is `GateRequired`, escalate to human queue.
7. **Outcome:**
   - Success → complete queue entry, case proceeds. Retain observer stores the
     AI's resolution in the case base (feedback loop).
   - Failure → release and escalate to `iot-operator-assisted` with context

### Escalation contract

```java
record AiEscalationContext(
    String reason,
    List<ResolutionSuggestion> consideredSuggestions,
    String partialAnalysis,
    List<PlanTrace> partialPlan
)
```

Stored in the queue entry's `metadata` field (TEXT/JSON) on `CaseQueueEntry`.
Written before LLM interaction starts and updated incrementally. Human operator
picks up where AI left off — the `metadata` field is readable from the queue
entry without additional lookups.

### Timeout enforcement

`@Scheduled` sweep runs every 60 seconds, querying for `CaseQueueEntry` where:
- `queueName = "iot-ai-resolution"`
- `status = CLAIMED`
- `claimedAt + timeout < now()`

Stale entries are escalated to `iot-operator-assisted`. The `AiEscalationContext`
in `metadata` preserves partial analysis — the agent writes it before starting
LLM interaction.

Configurable timeout window (default: 5 minutes). The sweep is independent of the
agent — if the agent completes before the sweep, the entry is COMPLETED and ignored.

### LLM integration

The agent is IoT-specific (webapp). LLM client infrastructure uses platform
utilities (`casehub-engine-ai`) or direct Claude API via Anthropic SDK.

---

## §8 Scope and Dependency Map

### #50 builds (this branch, casehub-iot)

| Piece | Module |
|-------|--------|
| `IoTCbrRetrievalService` | webapp-api |
| `ResolutionSuggestion` DTO | webapp-api |
| `ResolutionConfidence` | webapp-api |
| `GET /api/cases/{caseId}/suggestions` | webapp |
| `POST /api/cases/{caseId}/suggestions/{pastCaseId}/accept` | webapp |
| Suggestions panel in case detail UI | webapp |
| Tests: retrieval, REST, confidence scoring | webapp-api + webapp |

### Cross-repo issues

| Issue | Repo | Depends on |
|-------|------|------------|
| casehubio/platform#175 — Generic queue toolkit | casehub-platform | — |
| casehubio/engine#730 — Case queue implementation | casehub-engine | platform#175 |
| #62 — CBR-aware case triage | casehub-iot | engine#730, #50 |
| #63 — LLM resolution agent | casehub-iot | engine#730, #50, #62 |
| #64 — CBR temporal recency weighting | casehub-iot | #50 |
| #65 — Situation-level suggestion surfacing | casehub-iot | #50 |

### Dependency chain

```
#50 (CBR retrieval + human UI)     platform#175 (queue toolkit)
         ↓                                  ↓
  #62 triage (IoT)  ←————————  engine#730 (case queue)
         ↓
  #63 LLM resolution agent
```

#50 and platform queue toolkit proceed in parallel. Engine case queue depends on
platform toolkit. Triage and LLM agent depend on both #50 and engine case queue.

## IoT CBR Use Cases

The four existing case types and their CBR value:

**hvac-anomaly** — highest CBR value. High-volume, repetitive, consistently resolved.
"Last 4 times this thermostat spiked in summer, 3 were blocked filters." Seasonal
context (winter morning spikes = heating schedule, not anomalies) feeds false-positive
suppression (#52).

**safety-alert** — CBR classifies (real vs false positive). Safety systems respond
autonomously (alarms, shutoffs). Human operator review is mandatory — never routed
to AI queue. "8 of 10 kitchen smoke alerts at this time were cooking-related."
Cross-device correlation: smoke + temperature = 90% genuine, smoke alone = 70% false.

**security-alert** — Entry point drives similarity (weight 1.5). "Front door sensor,
2am — past cases: 3 were homeowner (disarmed within 30s), 1 was package delivery."

**generic-response** — Lowest CBR signal (common features only). Catch-all for device
events without specialized schemas.

**Cross-cutting:** resolution time prediction, escalation routing, confidence-based
triage, cold-start bootstrapping (everything goes to humans initially, AI queue grows
as case base matures).

## Cold-Start Behavior

Case base starts empty. The system handles this gracefully:
- Empty retrieval → suggestions panel shows "No similar past resolutions found"
- `ResolutionConfidence.level = NONE` → all cases route to human queue
- As cases complete, `CbrCaseRetainObserver` stores them → case base grows
- AI queue activates organically when confidence thresholds are met

## Out of Scope

| Concern | Tracked |
|---------|---------|
| CBR retention/purge policy | #58 |
| False-positive suppression | #52 |
| Work item outcome prediction | #51 |
| SEMANTIC_ONLY retrieval (embeddings) | Future store implementation |
| Work item queue migration to generic toolkit | Future — extract shared pattern if/when it emerges |
| CBR temporal recency weighting | #60 |
| Situation-level suggestion surfacing | #61 |

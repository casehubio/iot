# Work Item Outcome Prediction via CBR

Partial — covers Retain and Retrieve paths for #51. Predicts likely outcome, resolution time, and best-fit assignees for work items based on historical patterns. Creation-time prediction attachment and SLA/priority integration are deferred to follow-up issues.

## Dependencies

**New dependency for `webapp-api`:** `casehub-work-api` (compile scope). Provides `WorkItemCreator`, `WorkItemCreateRequest`, `WorkItemPriority`. This is an API module, analogous to the existing `casehub-engine-api` dependency — appropriate coupling for a Tier 1 module.

No other cross-repo changes required. Uses existing `FeatureVectorCbrCase` and `CbrCaseMemoryStore` from neocortex.

## Architecture Overview

Work item outcome prediction is the second application of CBR in the IoT domain, alongside case resolution suggestion (#50). Both follow the same pattern — extract features from IoT context, store completed outcomes, retrieve similar cases — but at different granularities:

- **Case-level CBR** (existing): "for this situation, what resolution plan works?" → `PlanCbrCase`
- **Work item-level CBR** (#51): "for this human task, what outcome is likely?" → `FeatureVectorCbrCase`

### Why `FeatureVectorCbrCase`

The CBR store backends (Qdrant, JPA, in-memory) have a closed set of known `cbrType` values with hardcoded serialization. `FeatureVectorCbrCase` (cbrType = `"feature-vector"`) is already supported natively by all three. Its `Map<String, FeatureValue>` features map is rich enough (strings, numbers, structs, struct lists) to carry all work item data — both input features for matching and output data for aggregation.

A custom `WorkItemCbrCase` would require modifying serializer switch statements in `QdrantCbrCaseMemoryStore.reconstructCase()`, `CbrMemoryDeserializer.deserialize()`, and `JpaCbrCaseMemoryStore.reconstruct()`. That refactoring (making stores type-agnostic via a codec registry) is valuable but orthogonal — it should be a separate neocortex issue.

### Case type segregation

`CbrQuery.caseType` = `"iot-work-item"` segregates work item cases from plan cases (`"plan"`) in the same CBR store. No cross-contamination.

## §1 — Retain: Work Item Outcome Recording

**Module:** `webapp`

### WorkItemOutcomeRecorder

`WorkItemOutcomeRecorder implements WorkItemObserver` — `@ApplicationScoped` CDI bean in the webapp module. Discovery is automatic: `WorkItemLifecycleEmitter` (in `casehub-work` runtime) already injects `Instance<WorkItemObserver>` and iterates all CDI-discovered implementations on every status change event.

**Trigger:** `onStatusChange(WorkItemStatusEvent)` for terminal statuses: `COMPLETED`, `REJECTED`, `FAULTED`, `CANCELLED`, `EXPIRED`, `ESCALATED`, `OBSOLETE`.

**Data collection:**

1. Fetch full `WorkItem` via `WorkItemService.findById(event.workItemId())` — the event carries only `workItemId`, `status`, `actor`, `outcome`, `candidateGroups`, `callerRef`, `tenancyId`. The entity has `title`, `description`, `priority`, `types`, `payload`, `createdAt`, `assignedAt`, `updatedAt`. Consistent with §4's data access pattern.
2. Parse IoT context from the work item's `payload` field (JSON) — `caseId`, `caseType`, `workerName`, `deviceClass`, `roomType`, `eventTimestamp`, `situationId`. These are embedded at work item creation time (§5).
3. Build `WorkItemContext` and extract features via `WorkItemFeatureExtractor.extractForRetain()`.

**Fallback for missing payload context:** If the payload doesn't contain IoT fields (e.g., work items created before this feature ships), attempt `CaseInstanceCache` lookup — scan `getAll()` filtered by `tenancyId` matching the work item's tenant, then match `waitingForWorkId` to the work item ID. The tenancy filter prevents cross-tenant context leakage. If that also fails, store the case with work-item-only features (priority, candidateGroups, types) — partial data is better than no data. This fallback is transitional: once §5 ships, all new work items embed IoT context in the payload, and the cache scan path becomes dead code.

**Store call:**

```java
store.store(cbrCase, "iot-work-item", workItemId.toString(),
            MemoryDomain.of("iot"), tenantId, caseId, Path.root());
```

- `entityId` = work item ID (for dedup/erasure)
- `caseId` = parent case ID (links to the case that spawned this work item)

**FeatureVectorCbrCase construction:**

- `problem` = work item title
- `solution` = fallback chain guaranteeing non-blank: `WorkItem.resolution ?? WorkItem.outcome ?? event.detail ?? status.name()`. `resolution` is set for COMPLETED/FAULTED/OBSOLETE; `outcome` covers REJECTED; `event.detail` carries the reason for CANCELLED/EXPIRED; `status.name()` is the final safety net (always non-blank for terminal statuses).
- `outcome` = terminal status name (e.g., `"COMPLETED"`)
- `confidence` = `1.0` (historical fact, not a prediction)
- `features` = `FeatureValue.toFeatureMap(rawFeatures)` where `rawFeatures` is the `Map<String, Object>` from `WorkItemFeatureExtractor.extractForRetain()`. The conversion is required because `FeatureVectorCbrCase` takes `Map<String, FeatureValue>`, not raw objects.

### Configuration

```
casehub.iot.webapp.cbr.work-item.enabled    # boolean, @WithDefault("true")
```

Skip-if-disabled pattern. When disabled, the observer is a no-op.

## §2 — Feature Extraction

**Module:** `webapp-api` (Tier 1 — pure Java, no CDI)

### WorkItemContext

A thin DTO that decouples feature extraction from data acquisition:

```java
public record WorkItemContext(
    // Input fields (for matching)
    String workItemTitle,
    String workItemDescription,
    List<String> workItemTypes,
    String priority,
    String candidateGroups,
    String workerName,
    String caseTypeName,
    String deviceClass,           // nullable
    String roomType,              // nullable
    Instant eventTimestamp,        // nullable — for temporal features
    // Output fields (only populated on Retain path)
    String terminalStatus,        // nullable
    String resolvedBy,            // nullable
    Instant createdAt,            // nullable
    Instant completedAt           // nullable
) {}
```

The observer builds this from the `WorkItem` entity + parsed payload. The REST endpoint builds it from the live work item + case context. Type conversions from `WorkItem` entity: `types` → `workItem.types.stream().map(t -> t.path).toList()`, `priority` → `workItem.priority.name()`.

### WorkItemFeatureExtractor

```java
public final class WorkItemFeatureExtractor {
    // Retain path: input + output features
    public static Map<String, Object> extractForRetain(WorkItemContext ctx) { ... }

    // Retrieve path: input features only (for CbrQuery)
    public static Map<String, Object> extractForRetrieve(WorkItemContext ctx) { ... }
}
```

`extractForRetrieve` is a strict subset of `extractForRetain` — same input extraction, omits output fields.

**Input features** (for similarity matching):

| Feature | Source | FeatureValue type |
|---------|--------|-------------------|
| `caseType` | case definition name | StringVal |
| `workerName` | plan step / worker binding | StringVal |
| `deviceClass` | case context via payload | StringVal |
| `roomType` | case context via payload | StringVal |
| `priority` | work item priority | StringVal |
| `candidateGroups` | work item | StringVal |
| `hourOfDay` | event timestamp | NumberVal |
| `dayType` | weekday / weekend | StringVal |
| `season` | derived from month | StringVal |

**Output features** (stored on Retain, NOT included in query):

| Feature | Source | FeatureValue type |
|---------|--------|-------------------|
| `resolutionDurationMinutes` | completedAt − createdAt | NumberVal |
| `resolvedBy` | `assigneeId` at completion (not `actor` — see note below) | StringVal |
| `terminalStatus` | final work item status | StringVal |

**Identity choice for `resolvedBy`:** `WorkItemStatusEvent` carries both `actor` (who performed the action) and `assigneeId` (who was assigned). We use `assigneeId` because the prediction suggests *who to assign* — we want the person who was responsible for the work, not a system actor or manager who might call `completeFromSystem`. In delegation scenarios, the delegatee becomes the assignee, so `assigneeId` still reflects the actual worker.

### Temporal feature derivation

Refactors `IoTCbrFeatureExtractors.deriveTemporalFeatures()` by extracting the derivation logic into a new overload: `deriveTemporalFeatures(Map<String, Object>, Instant)` that takes a pre-parsed timestamp. The existing `deriveTemporalFeatures(Map<String, Object>, ReadableLayer)` becomes a thin wrapper that reads the timestamp from the `ReadableLayer` and delegates to the new overload. Existing callers (the four `extract*Features` methods) are unchanged — only the internal call path splits.

## §3 — Retrieve: Prediction Service

**Module:** `webapp-api` (Tier 1 — pure Java, no CDI)

### WorkItemPredictionService

```java
public class WorkItemPredictionService {
    private final CbrCaseMemoryStore store;
    private final WorkItemCbrConfig config;

    public WorkItemPredictionService(CbrCaseMemoryStore store, WorkItemCbrConfig config) { ... }

    public WorkItemPrediction predict(Map<String, Object> inputFeatures, String tenantId) { ... }
}
```

Takes pre-extracted input features (the caller does extraction, the service does CBR + aggregation). This keeps the service testable without device registry or case cache dependencies. `WorkItemCbrConfig` is injected via the constructor so `topK` and `minSimilarity` are configurable per deployment.

**Query construction:**

```java
CbrQuery query = CbrQuery.of(
    tenantId,
    MemoryDomain.of("iot"),
    Path.root(),
    "iot-work-item",
    FeatureValue.toFeatureMap(inputFeatures),
    config.topK()
).withMinSimilarity(config.minSimilarity())
 .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
```

- `topK` default `20`: need volume for percentile computation and assignee ranking
- `minSimilarity` default `0.3`: lower than plan CBR. Work item prediction benefits from more data even at lower similarity — the aggregation averages out noise.
- `FEATURE_ONLY`: no embedding vectors for structured work item features

If temporal decay is desired, the caller can pass `CbrConfig` settings, but the default is no decay — work item patterns are more stable over time than situation resolutions.

### Aggregation

Given `List<ScoredCbrCase<FeatureVectorCbrCase>>`, compute predictions weighted by similarity score.

**Outcome distribution:** Count terminal statuses across results. Each result's contribution is weighted by its similarity score. Normalize to probabilities summing to 1.0.

**Resolution time percentiles:** Extract `resolutionDurationMinutes` from results where `terminalStatus` = `COMPLETED` only. CANCELLED/EXPIRED durations represent abandonment time (how long before someone gave up), not actual resolution time — including them inflates estimates. Compute weighted p50 and p90 over the filtered set. Null if fewer than 3 COMPLETED results have duration data.

**Assignee rankings:** Filter to results where `resolvedBy` is non-null (excludes unclaimed items that were cancelled/expired). Group by `resolvedBy`. Per assignee: success rate computed over *controllable* outcomes only — COMPLETED / (COMPLETED + REJECTED + FAULTED). CANCELLED, EXPIRED, ESCALATED, and OBSOLETE are excluded from both numerator and denominator since these outcomes are typically external (manager cancellation, SLA breach, case obsolescence), not assignee performance. Average resolution duration uses COMPLETED items only. Task count = total items with this assignee across all terminal statuses. Rank by success rate descending, then by average duration ascending. Cap at top 5.

**Confidence:** `meanSimilarityScore × min(1.0, log2(sampleSize + 1) / 4)`. This gives: 1 sample → 0.25 of similarity, 4 samples → 0.58, 8 samples → 0.79, 16+ samples → ~1.0. Confidence reflects both match quality (similarity) and statistical reliability (sample size).

**Empty results:** Return `WorkItemPrediction.empty()` — empty distribution, null durations, no assignees, confidence 0.0, sampleSize 0.

### WorkItemPrediction

```java
public record WorkItemPrediction(
    Map<String, Double> outcomeDistribution,
    Duration resolutionTimeP50,         // nullable
    Duration resolutionTimeP90,         // nullable
    List<AssigneeSuggestion> suggestedAssignees,
    double confidence,
    int sampleSize
) {
    public record AssigneeSuggestion(
        String assigneeId,
        double successRate,
        Duration avgResolutionTime,
        int taskCount
    ) {}

    public static WorkItemPrediction empty() {
        return new WorkItemPrediction(Map.of(), null, null, List.of(), 0.0, 0);
    }
}
```

### CDI producer

`WorkItemPredictionServiceProducer` in webapp — follows the `IoTCbrRetrievalServiceProducer` pattern. Injects `CbrCaseMemoryStore` and `WorkItemCbrConfig`, produces `WorkItemPredictionService(store, config)`.

## §4 — REST Endpoint

**Module:** `webapp`

### Endpoint

`GET /api/workitems/{workItemId}/prediction` on the existing `WorkItemResource`.

Requires `iot-viewer` role.

**Dependencies:** Inject `WorkItemService` (from `casehub-work` runtime — `@ApplicationScoped` CDI bean) rather than using `EntityManager` directly. This avoids persistence unit boundary issues — `WorkItem` is managed by `casehub-work`'s persistence context, and `WorkItemService.findById()` handles entity access correctly. The existing placeholder methods on `WorkItemResource` (list, claim, complete) are separate concerns and do not affect the prediction endpoint.

**Implementation:**

1. Query work item via `WorkItemService.findById(workItemId)` → 404 if absent
2. Verify `tenancyId` matches `CurrentPrincipal.tenancyId()` → 404 if mismatch
3. Parse IoT context from work item `payload` (JSON)
4. If payload has IoT fields → build `WorkItemContext` directly
5. If not → fall back to `CaseInstanceCache` scan (tenancy-filtered) for `waitingForWorkId` match → extract from case context
6. Call `WorkItemFeatureExtractor.extractForRetrieve()`
7. Call `WorkItemPredictionService.predict()`
8. Map to `WorkItemPredictionResponse` (REST record)

**Error handling:** CBR retrieval failure → log warning, return empty prediction. Same pattern as `SituationResource.getSuggestions()`. Never let CBR failures break the main flow.

**Empty case base:** Return the empty prediction with `sampleSize: 0`. The UI shows "Not enough historical data for predictions."

### Response shape

```json
{
  "workItemId": "550e8400-e29b-41d4-a716-446655440000",
  "outcomeDistribution": {
    "COMPLETED": 0.80,
    "REJECTED": 0.15,
    "FAULTED": 0.05
  },
  "resolutionTime": {
    "p50": "PT2H",
    "p90": "PT8H"
  },
  "suggestedAssignees": [
    {
      "assigneeId": "tech-1",
      "successRate": 0.95,
      "avgResolutionTime": "PT2H",
      "taskCount": 12
    }
  ],
  "confidence": 0.85,
  "sampleSize": 23
}
```

## §5 — HumanDecisionWorkerFunction Replacement

**Module:** `webapp-api` (Tier 1)

Replace the stub `HumanDecisionWorkerFunction` with a real implementation that creates work items via `WorkItemCreator`.

### Changes

`HumanDecisionWorkerFunction` gains a `WorkItemCreator` dependency (constructor-injected from the case descriptor):

```java
public class HumanDecisionWorkerFunction implements Function<Map<String, Object>, WorkerResult> {
    private final WorkItemCreator workItemCreator;
    // ...
}
```

**Work item creation:**

Build `WorkItemCreateRequest` from the worker function's `input` map:

- `title`: derived from case type + situation context (e.g., "HVAC anomaly — sustained temperature rise in bedroom")
- `types`: `["human-review"]`
- `priority`: mapped from input `urgency` field using: `"critical"` → `URGENT`, `"high"` → `HIGH`, `"medium"` or absent → `MEDIUM`, `"low"` → `LOW`. Each case type sets urgency in the plan definition's data mapping based on its risk classification (e.g., safety alerts default to `"high"`).
- `candidateGroups`: from input — case definition or configurable per case type
- `callerRef`: set by the function using inline encoding `"case:" + caseId + "/pi:" + planItemId` where both values come from the worker input map. This produces the standard `case:<uuid>/pi:<planItemId>` format that the engine adapter uses for work item ↔ plan item correlation. Inlined rather than importing `PlanItemCallerRef.encode()` to avoid adding `casehub-work-engine-adapter` (a runtime adapter module) as a compile dependency on the Tier 1 `webapp-api` module.
- `payload`: JSON containing IoT context for the Retain path:

```json
{
  "caseId": "<uuid>",
  "caseType": "hvac-anomaly",
  "workerName": "human-review",
  "deviceClass": "thermostat",
  "roomType": "bedroom",
  "eventTimestamp": "2026-07-17T14:30:00Z",
  "situationId": "sustained-temp-rise"
}
```

These fields come from the worker's `input` map, which the engine populates from the case context via the plan definition.

### Impact on case descriptors

All four case descriptors (`HvacAnomalyCaseDescriptor`, `SafetyAlertCaseDescriptor`, `SecurityAlertCaseDescriptor`, `GenericResponseCaseDescriptor`) construct `HumanDecisionWorkerFunction` in their `humanReviewWorker()` method, currently declared `private static`. Adding `WorkItemCreator` requires changing `humanReviewWorker()` from `static` to instance and storing `WorkItemCreator` as a descriptor field — consistent with how `deviceCommandWorker()` already works as an instance method accessing `providers` and `deviceRegistry`.

**Scoped change (8 files):**
- 4 descriptors (`webapp-api`): add `WorkItemCreator` constructor parameter, convert `humanReviewWorker()` from `static` to instance
- 4 CaseHub beans (`webapp`): add `@Inject WorkItemCreator` field, pass to descriptor constructor

### WorkerResult

The function returns `WorkerResult.of(Map.of("workItemId", ref.id().toString()))`. The engine uses the `callerRef` (not the return value) for correlation, so the return map is informational.

## §6 — Configuration

All work item prediction config under `casehub.iot.webapp.cbr.work-item`:

```properties
# Enable/disable the outcome recorder (WorkItemObserver)
casehub.iot.webapp.cbr.work-item.enabled=true

# Prediction query parameters
casehub.iot.webapp.cbr.work-item.top-k=20
casehub.iot.webapp.cbr.work-item.min-similarity=0.3
```

Config mapping: `WorkItemCbrConfig` — `@ConfigMapping(prefix = "casehub.iot.webapp.cbr.work-item")` with `enabled` as `@WithDefault("true") boolean`, `topK` as `@WithDefault("20") int`, `minSimilarity` as `@WithDefault("0.3") double`.

### CbrFeatureSchema registration

Add `IoTCbrFeatureSchemas.workItemOutcome()` returning a `CbrFeatureSchema` for case type `"iot-work-item"`:

```java
public static CbrFeatureSchema workItemOutcome() {
    var fields = new ArrayList<>(commonFields());  // deviceClass, roomType, hourOfDay, dayType, season
    fields.add(FeatureField.categorical("caseType"));
    fields.add(FeatureField.categorical("workerName"));
    fields.add(FeatureField.categorical("priority"));
    fields.add(FeatureField.categorical("candidateGroups"));
    return new CbrFeatureSchema("iot-work-item", fields);
}
```

Reuses `commonFields()` (which already defines `deviceClass` with categorical table, `roomType` with categorical table, `hourOfDay` with `GaussianDecay(3.0)`, `dayType` categorical, `season` with categorical table). Work-item-specific fields (`caseType`, `workerName`, `priority`, `candidateGroups`) use default categorical similarity (exact match). Output features (`resolutionDurationMinutes`, `resolvedBy`, `terminalStatus`) are not in the schema — they are stored but not used for similarity matching.

Register in `IoTCbrSchemaRegistration.onStartup()`:

```java
cbrStore.registerSchema(IoTCbrFeatureSchemas.workItemOutcome());
```

## Test Coverage

### WorkItemFeatureExtractor (webapp-api)

- Extract input features: all fields populated → correct feature map
- Extract input features: nullable fields absent → omitted from map
- Extract for retain: includes output features (duration, assignee, status)
- Extract for retrieve: excludes output features
- Temporal derivation: weekday, weekend, season boundaries, null timestamp

### WorkItemPredictionService (webapp-api)

- Aggregation with diverse outcomes → correct distribution
- Aggregation with uniform outcomes → near-1.0 for dominant status
- Resolution time percentiles: p50/p90 computed correctly from COMPLETED items
- Resolution time with mixed outcomes: COMPLETED + CANCELLED results → p50/p90 uses only COMPLETED durations
- Resolution time with insufficient data: fewer than 3 COMPLETED → null percentiles
- Assignee ranking: sorted by success rate, then duration
- Assignee with null resolvedBy: excluded from ranking (no phantom entry)
- Assignee controllable success rate: 3 COMPLETED + 1 CANCELLED → success rate = 1.0 (CANCELLED excluded from denominator)
- Assignee success rate mixed: 3 COMPLETED + 1 REJECTED → success rate = 0.75
- Empty results → WorkItemPrediction.empty()
- Single result → distribution of 1.0 for that status, confidence reflects low sample
- Similarity weighting: high-similarity results dominate distribution
- Config wiring: custom topK and minSimilarity from WorkItemCbrConfig are used in query

### WorkItemOutcomeRecorder (webapp)

- Terminal status → stores CBR case
- Non-terminal status → no-op
- Payload has IoT context → features include deviceClass, roomType
- Payload missing IoT context → fallback to CaseInstanceCache
- Both sources unavailable → stores with work-item-only features
- Disabled via config → no-op

### WorkItemResource prediction endpoint (webapp)

- Valid work item with prediction data → full response
- Valid work item, empty case base → empty prediction response
- Work item not found → 404
- Tenancy mismatch → 404
- CBR failure → empty prediction (logged, not thrown)

### HumanDecisionWorkerFunction (webapp-api)

- Creates work item with correct title, types, priority, candidateGroups
- Payload contains embedded IoT context fields
- Returns WorkerResult with workItemId

## Deferred Issues

Each deferred item is captured as a GitHub issue. Issue numbers to be assigned at implementation time (repo issue tracker currently unavailable for automated filing).

- **Generic store codec registry** (neocortex repo): Making `CbrCaseMemoryStore` type-agnostic so new `CbrCase` types don't require serializer changes.
- **Automated priority/SLA adjustment** (IoT repo): Using predictions to auto-adjust work item priority or set claim deadlines. Depends on queue pipeline (#62, #63) landing first.
- **Temporal decay for work items** (IoT repo): Configurable via `WorkItemCbrConfig.temporalDecayHalfLifeDays`. Deferred until the case base has enough data to calibrate.
- **Per-case-type prediction config** (IoT repo): Different topK, minSimilarity, weights per case type. Current design uses global config. Additive change when needed.
- **Outcome recording via CloudEvents** (IoT repo): Alternative to `WorkItemObserver` — listen for work item CloudEvents on the event bus. More decoupled but requires CloudEvent integration to be wired.
- **Creation-time prediction attachment** (IoT repo, from R1-03): Event listener on work item creation to compute and attach predictions. Deferred in favor of on-demand GET endpoint.
- **SLA/priority integration** (IoT repo, from R1-03): Feed predictions into `WorkItemPriority` and claim SLA machinery. Depends on queue pipeline (#62, #63).

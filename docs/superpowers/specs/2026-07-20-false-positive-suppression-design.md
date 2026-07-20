# False-Positive Suppression via CBR

**Issue:** casehubio/iot#52  
**Date:** 2026-07-20  
**Status:** Draft  
**Depends on:** CBR infrastructure (#49, shipped), ras-api enhancement (casehubio/ras — issue to be filed before implementation, covering: `PolicyDecision` record, `TriggerDecision.SUPPRESS`, `SituationChangeEvent.ChangeType.SUPPRESSED`/`DISMISSED`, metadata field on `SituationChangeEvent`, `SuppressionMetadataKeys`, `RasTriggerPolicy` return type change from `Uni<TriggerDecision>` to `Uni<PolicyDecision>`), engine `WorkflowExecutionCompletedEvent` (cross-platform dependency shared with gate-rejection-outcome-recording, cbr-capability-activation, and cbr-routing-pipeline specs — if not yet available, `CaseOutcomeObserver` SPI is the interim fallback)

## Problem

Operators dismiss situations that the ganglia correctly detect but that are not actionable — motion sensors in hallways at shift change, temperature fluctuations during HVAC cycling, power anomalies during known maintenance windows. These repeat with predictable context patterns (device class, room type, time of day, season). Without feedback, the system keeps escalating them, eroding operator trust and creating alert fatigue.

## Solution

A CBR-based feedback loop from operator dismissals back to the situation trigger pipeline. When a new situation fires and matches a historically-dismissed pattern, the system applies a graduated response: annotate, demote, or suppress — depending on how consistently the pattern has been dismissed.

## Design Decisions

1. **Interception point: `RasTriggerPolicy` SPI** — the designed extension point between detection and execution. Requires enhancing ras-api with `PolicyDecision` (metadata on decisions) and `TriggerDecision.SUPPRESS`. This is the right ras-level design, not an IoT workaround. Cross-repo change to ras.

2. **Graduated model (three tiers):**
   - Tier 1 (annotate): case is created, tagged with suppression metadata in `baseCaseData`. Operator sees the case but knows it's historically dismissed.
   - Tier 2 (demote): no case created. Fires `SituationChangeEvent(SUPPRESSED)` with `tier=demote`. Dashboard shows the suppression **prominently** for easy override — the system is moderately confident this is a false positive but wants operator awareness.
   - Tier 3 (full suppress): no case created. Fires `SituationChangeEvent(SUPPRESSED)` with `tier=full`. Dashboard shows the suppression with lower visibility — the system is highly confident based on sustained dismissal history.
   
   Tiers 2 and 3 both produce `SUPPRESS` at the ras level — the behavioral distinction is intentional. The ras layer provides the mechanism (suppress), the IoT policy communicates confidence via metadata, and the dashboard differentiates presentation. The graduated model governs operator attention, not system behavior: demote means "probably false positive, please review"; full suppress means "almost certainly false positive, still overridable."

3. **CBR for operator evidence only** — the case base stores dismissals, actioned situations, and overrides. System suppression decisions are never stored as CBR cases — that would create a self-reinforcing feedback loop where the system's own decisions amplify future suppression. Suppression decisions go to a separate audit log.

4. **`situationId` as required match** — CBR queries are partitioned by situation type via `caseType = "iot-dismissal:" + situationId`. Dismissing motion-at-night cannot affect temperature-threshold suppression. Cross-situation learning (noisy device) is a device-health concern, not a suppression concern.

5. **Safety gate: never suppress safety-critical situations** — safety-critical situations are exempt from all suppression tiers, regardless of dismissal history. Safety classification works at two levels: (a) for `CreateCase` actions, the case type is checked against `IoTSafetyCaseTypes.SAFETY_CRITICAL`; (b) for all actions including `NotifyOnly`, the situation ID is checked against `IoTSafetyCaseTypes.SAFETY_SITUATION_IDS`. This ensures NotifyOnly safety situations (e.g., a fire alarm sending an immediate notification without case creation) are never suppressed. Both sets are shared with `IoTActionRiskClassifier` as a single source of truth.

6. **Two dismissal sources:** situation-level (operator dismisses from dashboard) and case-level (operator closes a triggered case as false-positive). Both record CBR cases.

## ras-api Changes

### `PolicyDecision` (new)

```java
public record PolicyDecision(TriggerDecision decision, Map<String, Object> metadata) {
    public PolicyDecision(TriggerDecision decision) {
        this(decision, Map.of());
    }
}
```

### `TriggerDecision` — add SUPPRESS

```java
public enum TriggerDecision {
    TRIGGER,
    TRIGGER_AND_CONTINUE,
    CONTINUE_ACCUMULATING,
    DISCARD,
    RESOLVE,
    SUPPRESS
}
```

SUPPRESS is semantically distinct from DISCARD. DISCARD = signal is garbage (chain-mode not satisfied, contradictory signals). SUPPRESS = signal is valid but historical evidence says it is not actionable. Different audit trail, different metrics, different dashboard treatment.

### `RasTriggerPolicy` — return `PolicyDecision`

```java
public interface RasTriggerPolicy {
    Uni<PolicyDecision> evaluate(SituationContext context, SituationDefinition definition);
}
```

### `SituationChangeEvent.ChangeType` — add SUPPRESSED and DISMISSED

```java
public enum ChangeType {
    TRIGGERED,
    RESOLVED,
    DISCARDED,
    SUPPRESSED,
    DISMISSED
}
```

SUPPRESSED = system auto-suppressed based on CBR evidence. DISMISSED = operator explicitly dismissed an active situation.

### `SituationChangeEvent` — add metadata

```java
public record SituationChangeEvent(
        String tenancyId, String situationId, String correlationKey,
        ChangeType changeType, SituationContext context,
        Map<String, Object> metadata) {

    public SituationChangeEvent {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(situationId, "situationId");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(changeType, "changeType");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(metadata, "metadata");
    }

    public SituationChangeEvent(String tenancyId, String situationId,
            String correlationKey, ChangeType changeType, SituationContext context) {
        this(tenancyId, situationId, correlationKey, changeType, context, Map.of());
    }
}
```

The 5-arg constructor provides backward compatibility. `metadata` is always non-null (`Map.of()` default). `context` remains non-null — for DISMISSED events where the original situation has already been resolved, the `DismissalRecorder` constructs a minimal `SituationContext` from available data (see Dismissal Recording section).

### `SuppressionMetadataKeys` (new)

```java
public final class SuppressionMetadataKeys {
    public static final String TIER = "suppression.tier";
    public static final String DISMISSAL_RATE = "suppression.dismissalRate";
    public static final String MATCH_COUNT = "suppression.matchCount";
    public static final String AVERAGE_SIMILARITY = "suppression.averageSimilarity";
    private SuppressionMetadataKeys() {}
}
```

Both `PolicyDecision` and `SituationChangeEvent` use `Map<String, Object>` for metadata — this is intentionally generic to support future policy metadata beyond suppression. `SuppressionMetadataKeys` provides compile-time-safe constants for producers and consumers. Placed in `ras-api` alongside `PolicyDecision`.

## ras Runtime Changes

### `SituationEvaluator`

`processEvent()` changes:

1. **Policy call:** `TriggerDecision decision = triggerPolicy.evaluate(...)` becomes `PolicyDecision policyDecision = triggerPolicy.evaluate(...)`. All downstream code uses `policyDecision.decision()` for the enum and `policyDecision.metadata()` for supplementary data.

2. **Metrics:** `metrics.decision(situationId, tenancyId, decision)` becomes `metrics.decision(situationId, tenancyId, policyDecision.decision())`. The `RasMetrics.decision()` signature is unchanged — it takes `TriggerDecision`.

3. **executeDecision():** signature changes to `executeDecision(TriggerDecision decision, Map<String, Object> policyMetadata, SituationContext context, ...)`. The `policyMetadata` is threaded through to each arm:

For TRIGGER/TRIGGER_AND_CONTINUE with `CreateCase` actions — merge metadata into `CaseTriggerConfig.baseCaseData`:

```java
CaseTriggerConfig config = createCase.config();
if (!policyMetadata.isEmpty()) {
    var merged = new LinkedHashMap<>(config.baseCaseData());
    merged.putAll(policyMetadata);
    config = new CaseTriggerConfig(
            config.caseNamespace(), config.caseName(), config.caseVersion(),
            Map.copyOf(merged));
}
this.caseTrigger.fire(config, context).await().indefinitely();
```

For TRIGGER/TRIGGER_AND_CONTINUE with `NotifyOnly` actions — pass metadata through on the `SituationChangeEvent`:

```java
changeEvent.fireAsync(new SituationChangeEvent(
        tenancyId, situationId, correlationKey,
        SituationChangeEvent.ChangeType.TRIGGERED, context, policyMetadata));
```

For SUPPRESS (new arm in the switch):

```java
case SUPPRESS:
    this.closeGanglia(definition, situationId, correlationKey, tenancyId);
    this.store.remove(situationId, correlationKey, tenancyId).await().indefinitely();
    this.changeEvent.fireAsync(new SituationChangeEvent(
            tenancyId, situationId, correlationKey,
            ChangeType.SUPPRESSED, context, policyMetadata));
    this.metrics.situationSuppressed(situationId, tenancyId);
    return true;
```

The existing CONTINUE_ACCUMULATING, DISCARD, and RESOLVE arms pass empty metadata (no policy metadata to propagate for these decisions). The trigger-claim flow, conflict retries, event buffer cleanup, and ganglia close logic are unchanged — only the metadata threading is new.

### `DefaultRasTriggerPolicy`

Wraps existing returns in `PolicyDecision` with empty metadata. No functional change.

### `RasMetrics`

Add `situationSuppressed(situationId, tenancyId)` counter.

## IoT: CBR Feature Schema

`IoTCbrFeatureSchemas.situationDismissal()`:

```java
public static CbrFeatureSchema situationDismissal() {
    var fields = new ArrayList<>(commonFields());
    fields.add(FeatureField.numeric("detectionConfidence", 0.0, 1.0,
            new SimilaritySpec.GaussianDecay(0.2)));
    return new CbrFeatureSchema("iot-dismissal", fields);
}
```

Fields: deviceClass, roomType, hourOfDay, dayType, season (common), plus detectionConfidence.

CaseType partition: `"iot-dismissal:" + situationId` — hard partition by situation type.

CBR case outcomes: `"dismissed"`, `"actioned"`, `"override-actioned"`.

Feature extraction reuses `IoTCbrFeatureExtractors.deriveTemporalFeatures(Instant)` and device resolution from `DeviceRegistry` via correlationKey.

## IoT: SuppressionEvaluator

In `webapp-api`. Pure domain logic, no CDI. Constructor takes `CbrCaseMemoryStore`.

```java
public class SuppressionEvaluator {

    private final CbrCaseMemoryStore store;
    private final SuppressionConfig config;

    public SuppressionAssessment assess(
            String situationId, Map<String, Object> features, String tenantId) {
        // 1. Query CBR with caseType = "iot-dismissal:" + situationId
        // 2. Compute from results:
        //    totalCases, dismissedCount, dismissalRate, averageSimilarity
        // 3. If totalCases < minCases → NONE
        // 4. If dismissalRate >= fullThreshold → SUPPRESS
        // 5. If dismissalRate >= demotionThreshold → DEMOTE
        // 6. If dismissalRate > 0 → ANNOTATE
        // 7. Else → NONE
    }
}
```

### Configuration

| Property | Default | Meaning |
|----------|---------|---------|
| `casehub.iot.suppression.full-threshold` | 0.9 | Dismissal rate >= 90% and min-cases met → tier 3 |
| `casehub.iot.suppression.demotion-threshold` | 0.7 | Dismissal rate >= 70% and min-cases met → tier 2 |
| `casehub.iot.suppression.min-cases` | 5 | Minimum similar cases before any suppression |
| `casehub.iot.suppression.top-k` | 20 | Similar cases to consider |
| `casehub.iot.suppression.min-similarity` | 0.5 | Ignore cases below 50% similarity |

### Output

```java
public record SuppressionAssessment(
        SuppressionTier tier,
        double dismissalRate,
        int totalCases,
        int dismissedCases,
        double averageSimilarity) {}

public enum SuppressionTier { NONE, ANNOTATE, DEMOTE, SUPPRESS }
```

## IoT: IoTSuppressionTriggerPolicy

In `webapp-api`. Overrides `DefaultRasTriggerPolicy` via CDI (`@ApplicationScoped` vs `@DefaultBean`).

Delegation: the policy creates `new DefaultRasTriggerPolicy()` as a plain object (not CDI-injected, since IoT's policy replaces it in CDI via `@ApplicationScoped` vs `@DefaultBean` priority). **Design contract:** `DefaultRasTriggerPolicy` is pure chain-mode evaluation logic with no injected dependencies — its constructor is trivial, and this must remain so. If `DefaultRasTriggerPolicy` ever gains `@Inject` dependencies, this instantiation will produce an uninitialized instance that fails loudly (NPE), which is the correct failure mode — it forces the developer to restructure the delegation.

Flow:
1. Delegate to `DefaultRasTriggerPolicy` for chain-mode evaluation
2. If result is not TRIGGER or TRIGGER_AND_CONTINUE → pass through unchanged
3. Safety gate: if situation maps to safety-critical case type → pass through unchanged
4. Extract features from `SituationContext` (correlationKey → device lookup, temporal from lastSignal)
5. Call `suppressionEvaluator.assess()`
6. Map assessment:
   - NONE → `PolicyDecision(TRIGGER, {})`
   - ANNOTATE → `PolicyDecision(TRIGGER, {suppression.tier=annotate, suppression.dismissalRate=..., suppression.matchCount=...})`
   - DEMOTE → `PolicyDecision(SUPPRESS, {suppression.tier=demote, ...})`
   - SUPPRESS → `PolicyDecision(SUPPRESS, {suppression.tier=full, ...})`

Safety gate:

```java
private boolean isSafetyCritical(SituationDefinition definition) {
    if (IoTSafetyCaseTypes.SAFETY_SITUATION_IDS.contains(definition.situationId())) {
        return true;
    }
    return definition.triggerAction() instanceof TriggerAction.CreateCase createCase
            && IoTSafetyCaseTypes.SAFETY_CASE_TYPES.contains(createCase.config().caseName());
}
```

Two-level check: situation ID first (catches `NotifyOnly` and `CreateCase` alike), then case type as a secondary catch-all. `TriggerAction` is a sealed interface with `CreateCase` and `NotifyOnly` variants — without the situation ID check, a safety-critical `NotifyOnly` situation (e.g., a fire alarm notification) would bypass the gate entirely.

`IoTSafetyCaseTypes` is a **new class** in `webapp-api` (does not exist today). It extracts and shares the safety-critical constants currently private in `IoTActionRiskClassifier`:

```java
public final class IoTSafetyCaseTypes {
    public static final Set<String> SAFETY_CASE_TYPES = Set.of("safety-alert");
    public static final Set<String> SAFETY_SITUATION_IDS = Set.of(
            "smoke-detected", "co-detected", "water-leak-detected");
    private IoTSafetyCaseTypes() {}
}
```

`IoTActionRiskClassifier` will be refactored to reference `IoTSafetyCaseTypes.SAFETY_CASE_TYPES` instead of its current `private static final` field. The situation ID set covers the specific safety-critical situations defined in issue #52. All safety-critical situations use the `safety-alert` case type, but the situation ID set provides defense-in-depth for `NotifyOnly` configurations.

## IoT: Dismissal Recording

### DismissalRecorder (webapp-api)

Records operator decisions as CBR cases. Two entry points:

**Situation-level dismissal** (invoked from REST endpoint):
1. Attempt to load `SituationContext` from `SituationStore`
2. **If context found:** resolve device from `DeviceRegistry` via correlationKey → extract deviceClass, roomType. Derive temporal features from `context.lastSignal()`. Compute detectionConfidence from context detections (max confidence of positive-signal detections only — `signal.isAtLeast(DetectionSignal.WEAK)`, excluding `ANTI` signals which represent negative evidence). Remove situation from `SituationStore`. Fire `SituationChangeEvent(DISMISSED)`. Ganglia cleanup is handled by a CDI observer in `webapp` that observes `SituationChangeEvent(DISMISSED)` and calls `ganglion.close()` for each referenced ganglion — the DismissalRecorder in `webapp-api` cannot access `SituationDefinitionRegistry` (ras-runtime). This follows the same observer pattern used for `SituationChangeEvent(SUPPRESSED)` → `SuppressionLogEntry`.
3. **If context absent (situation already resolved/expired):** the operator's dismissal intent is still valid CBR evidence. Resolve device from `DeviceRegistry` via correlationKey. Derive temporal features from `Instant.now()`. Set detectionConfidence to 0.0 (unknown). Do NOT fire `SituationChangeEvent(DISMISSED)` — there is no active situation to dismiss.
4. In both cases: store `FeatureVectorCbrCase` with caseType `"iot-dismissal:" + situationId`, outcome `"dismissed"`. Return response indicating whether the situation was still active or already resolved.

**correlationKey convention:** in IoT, `correlationKey` is a device ID by convention. `DeviceRegistry.findById(correlationKey)` resolves the device for feature extraction. If device resolution returns empty (e.g., multi-device situations using a room ID as correlationKey), `deviceClass` and `roomType` features are omitted — the CBR similarity computation handles missing features via the schema's default similarity.

**Case-level false-positive** (CDI observer):
- Observe `WorkflowExecutionCompletedEvent` — a new event to be introduced in engine-common (see Dependencies). This event fires for all completed workflow executions regardless of outcome. `WorkerOutcomeResolvedEvent` fires only for non-success outcomes (per GE-20260706-56a75c) and is not suitable here since actioned cases (success outcomes) must also be recorded as CBR evidence.
- **Fallback if `WorkflowExecutionCompletedEvent` is not yet available:** use the `CaseOutcomeObserver` SPI pattern (used by the CBR routing pipeline) as an interim integration point.
- Check if the case was situation-triggered (case data contains situationId)
- If case outcome indicates false-positive → store CBR case with outcome `"dismissed"`
- If case outcome indicates actioned → store CBR case with outcome `"actioned"`
- The specific outcome values that map to "false positive" are an integration contract with engine — defined at implementation time based on engine's case resolution outcomes

## IoT: Suppression Log

### SuppressionLogEntry (webapp, JPA)

```java
@Entity
@Table(name = "iot_suppression_log")
public class SuppressionLogEntry {
    @Id @GeneratedValue UUID id;
    String situationId;
    String correlationKey;
    String tenancyId;
    Instant suppressedAt;
    @Enumerated(EnumType.STRING) SuppressionTier tier;
    double dismissalRate;
    int matchedCaseCount;
    double averageSimilarity;
    boolean overridden;
    Instant overriddenAt;
    String overriddenBy;
    @Column(columnDefinition = "jsonb")
    SituationContext contextSnapshot;
}
```

`contextSnapshot` stores the full `SituationContext` at suppression time — captured from the `SituationChangeEvent(SUPPRESSED)` which carries the context before `store.remove()` deletes it from the situation store. This snapshot enables the override flow to reconstruct the case trigger without relying on the (now-removed) live situation state.

Written by a CDI observer on `SituationChangeEvent(SUPPRESSED)` — reads metadata and context from the event.

Bounded by `@Scheduled` retention purge (default 90 days), consistent with bridge-persistence-jpa pattern.

### Override flow

When an operator overrides a suppression:
1. Mark log entry: `overridden = true`, `overriddenAt`, `overriddenBy`
2. Store CBR case with outcome `"override-actioned"` (counter-evidence)
3. Re-trigger: read `SituationDefinition` from registry, reconstruct `SituationContext` from `contextSnapshot`, fire `CaseTrigger.fire(createCase.config(), contextSnapshot)` → create the case that was suppressed
4. Return created case ID to the operator

The `contextSnapshot` contains the full detection history, timing, and correlation data that the case needs. For `NotifyOnly` situations, step 3 fires a `SituationChangeEvent(TRIGGERED)` instead of calling `CaseTrigger`.

## IoT: REST Endpoints

All on `SituationResource`.

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/situations/active/{correlationKey}/dismiss` | Dismiss an active situation |
| GET | `/api/situations/suppressions` | List suppression history (default: last 24h, excludes overridden) |
| POST | `/api/situations/suppressions/{id}/override` | Override a suppression, create the suppressed case |
| GET | `/api/situations/{situationId}/suppression-stats` | Aggregated suppression stats for a situation type |

### Dismiss request

```json
{ "situationId": "...", "reason": "..." }
```

Reason is optional, stored as metadata on the CBR case for operator auditability.

### Suppression stats response

```json
{
  "situationId": "...",
  "suppressedCount": 12,
  "demotedCount": 8,
  "overrideCount": 3,
  "currentDismissalRate": 0.78,
  "safetyCritical": false
}
```

**Data sources:** `suppressedCount` and `demotedCount` are `COUNT` from `iot_suppression_log` grouped by `tier` and filtered by `situationId`. `overrideCount` is `COUNT` from `iot_suppression_log WHERE overridden = true`. `currentDismissalRate` is computed from a live CBR query (same as `SuppressionEvaluator.assess()` but read-only). `safetyCritical` is derived from `IoTSafetyCaseTypes`.

`totalEvaluated` and `annotatedCount` from the original design are removed — they would require logging every suppression evaluation (including NONE results) to a persistent store, which is disproportionate cost for dashboard stats. The suppression log + CBR store provide the actionable metrics.

## Cross-Repo Impact

| Repo | Change | Scope |
|------|--------|-------|
| **ras** (api) | `PolicyDecision`, `SuppressionMetadataKeys`, `SUPPRESS`, `SUPPRESSED`, `DISMISSED`, metadata on `SituationChangeEvent` | 5 types |
| **ras** (runtime) | `SituationEvaluator` SUPPRESS handling + metadata threading through all decision arms, `DefaultRasTriggerPolicy` return type change (`Uni<TriggerDecision>` → `Uni<PolicyDecision>`), `RasMetrics.situationSuppressed()` | 3 classes |
| **iot** (webapp-api) | `SuppressionEvaluator`, `IoTSuppressionTriggerPolicy`, `DismissalRecorder`, `IoTSafetyCaseTypes`, feature schema, assessment records | ~6 new classes |
| **iot** (webapp) | `SuppressionLogEntry`, REST endpoints, CDI observers, `IoTCbrSchemaRegistration` update, Flyway migration | ~5 new classes + migration |

`RasTriggerPolicy` is a public SPI — any repo implementing it will need to update the return type from `Uni<TriggerDecision>` to `Uni<PolicyDecision>`. Current implementors: `DefaultRasTriggerPolicy` (ras-runtime), `IoTSuppressionTriggerPolicy` (this spec). The desiredstate-ras adapter delegates to the default policy and will need a mechanical return-type update. No changes to engine, clinical, qhorus, platform, neocortex, or any other repo.

**SituationResource TODOs:** The existing `SituationResource` has two open TODOs — (1) merging classpath definitions with runtime definitions, (2) `listActive()` returning empty. Neither blocks this spec: the dismiss endpoint works by correlationKey/situationId independently of the active-situations listing, and the dashboard receives active situation data via `SituationChangeEvent` stream, not the REST listing. However, `listActive()` is a UX prerequisite for operators to discover situations to dismiss — this dependency is tracked separately from suppression.

## Safety Constraints

1. Safety-critical situations are never suppressed, demoted, or annotated — the suppression system is bypassed entirely. Safety classification uses a two-level gate: situation ID checked against `IoTSafetyCaseTypes.SAFETY_SITUATION_IDS` (catches all action types including `NotifyOnly`), then case type checked against `IoTSafetyCaseTypes.SAFETY_CASE_TYPES` as defense-in-depth for `CreateCase` actions.
2. Suppression requires minimum evidence (`min-cases` threshold) — no suppression on thin data.
3. All suppression decisions are logged and auditable via `SuppressionLogEntry`.
4. Operators can override any suppression, and overrides feed back as counter-evidence.
5. System decisions never enter the CBR case base — only operator evidence.
6. Suppression metrics are exposed via `RasMetrics` for monitoring/alerting.

## Module Placement

| Component | Module | Why |
|-----------|--------|-----|
| `SuppressionEvaluator` | webapp-api | Pure domain logic, no JPA/CDI. Testable in isolation. |
| `IoTSuppressionTriggerPolicy` | webapp-api | Implements ras SPI, needs device registry + CBR store. Tier 1 — no runtime deps. |
| `DismissalRecorder` | webapp-api | Pure domain logic, takes store in constructor. |
| `SuppressionLogEntry` | webapp | JPA entity, needs persistence context. |
| REST endpoints | webapp | Quarkus runtime, JAX-RS. |
| CDI observers | webapp | Quarkus runtime, CDI events. Includes: `SuppressionLogObserver` (SUPPRESSED → log entry), `DismissalGangliaObserver` (DISMISSED → ganglia close via `SituationDefinitionRegistry`). |
| `IoTSafetyCaseTypes` | webapp-api | Shared constant, referenced by policy and risk classifier. |

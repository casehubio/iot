# CBR-Aware Case Triage — casehub-iot #62

**Date:** 2026-07-21
**Issue:** casehubio/iot#62
**Parent spec:** `docs/superpowers/specs/2026-07-14-cbr-situation-resolution-design.md` §6

## Summary

When a case starts, the engine already retrieves CBR experiences and writes them
to the working context (`CaseStartedEventHandler.injectCbrExperiences()`). This
spec adds three scalar summary fields to that context write — `cbrBestSimilarity`,
`cbrMatchCount`, `cbrOutcomeConsistency` — so that IoT `LabelRule`s can route
cases to the appropriate queue view.

No new SPIs, no new routing mechanisms. One new CDI observer
(`IoTQueueViewInitializer`, §4) handles idempotent queue view setup at startup.
The design works entirely within the existing label → SubjectView → CaseQueueEntry
pipeline.

## Architecture deviation from parent spec

The parent spec (§6) designed `IoTCbrCaseQueueRoutingStrategy implements
CaseQueueRoutingStrategy`. The engine queue module (engine#730) was built with
a label + SubjectView routing architecture instead of a strategy SPI:

- `CaseLabelEvaluator` observes `CaseLifecycleEvent`, evaluates `LabelRule`s,
  sets labels on the case instance
- `SubjectViewOrchestrator` maps labels to `SubjectViewSpec`s (the "queues")
- `CaseQueueEvent` fires for each view membership change
- `CaseQueueEntryManager` creates `CaseQueueEntry` rows

There is no `CaseQueueRoutingStrategy` SPI, no `QueueRoutingDecision`, no
`CaseQueueRouter`. This spec adapts the triage design to the actual architecture.

**Prerequisite status:** The engine queue module (engine#730) has a design spec
(`engine/docs/specs/2026-07-19-case-queue-design.md`) and a TDD implementation
plan, but is not yet built. The platform infrastructure it depends on already
exists: `LabelRule` and `LabelAction` (`casehub-platform-api`),
`SubjectViewOrchestrator` and `SubjectViewStore` (`casehub-platform-view`),
`SubjectViewSpec` (`casehub-platform-api`). What is unbuilt is the engine
integration layer: `CaseLabelEvaluator` (observes `CaseLifecycleEvent`, evaluates
`LabelRule`s, calls `SubjectViewOrchestrator`), `CaseQueueEntryManager` (creates
`CaseQueueEntry` rows from view events), and the `casehub-engine-queue` module
itself. This spec's label rules (§3) and queue view setup (§4) depend on that
module. Implementation of this spec is gated on engine#730 completion.

## Key sequencing insight

In `CaseStartedEventHandler.onCaseStarted()`, the ordering is already correct:

```
line 116: .chain(() -> injectCbrExperiences(instance))   ← CBR retrieval + context write
line 119: lifecycleEvents.fireAsync(CaseLifecycleEvent)  ← fires AFTER CBR in context
line 135: eventBus.publish(CONTEXT_CHANGED, ...)         ← fires AFTER lifecycle event
```

CBR data is in the working context before the lifecycle event fires. The
lifecycle event's context snapshot includes the CBR fields. `CaseLabelEvaluator`
evaluates label rules against that snapshot. The composition point already exists.

---

## §1 Engine change: CBR summary stats in working context

**Scope: cross-repo → casehub-engine**

In `CaseStartedEventHandler.injectCbrExperiences()`, after writing the existing
`cbrExperiences` list, add three scalar summary fields:

```java
WritableLayerImpl layer = (WritableLayerImpl) mutableContext.writableLayer("working");

layer.engineSet("cbrBestSimilarity",
    experiences.stream()
        .mapToDouble(RetrievedExperience::similarityScore)
        .max().orElse(0.0));

layer.engineSet("cbrMatchCount", experiences.size());

layer.engineSet("cbrOutcomeConsistency",
    computeOutcomeConsistency(experiences));
```

`computeOutcomeConsistency` groups outcomes by exact string match, takes the most
frequent, divides by total. Returns 0.0 if all outcomes are null.

```java
private static double computeOutcomeConsistency(List<RetrievedExperience> experiences) {
    Map<String, Long> freq = experiences.stream()
        .map(RetrievedExperience::outcome)
        .filter(Objects::nonNull)
        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    if (freq.isEmpty()) {
        return 0.0;
    }
    return (double) Collections.max(freq.values()) / experiences.size();
}
```

When experiences is empty, nothing is written — no zeroes polluting the context.
Label rules handle the absent-key case by defaulting to 0.0.

**Denominator rationale:** `computeOutcomeConsistency` divides by
`experiences.size()` (all experiences), not by the count of non-null outcomes.
This is intentional. Null outcomes represent cases that were never resolved or
whose outcomes were not recorded. If 8 of 10 past cases have no recorded outcome,
dividing by the 2 known outcomes would yield 1.0 consistency — routing to
AI-resolution on the basis of 2 data points. The current formula treats missing
outcomes as a confidence-reducing signal: sparse outcome data genuinely reduces
confidence in automated resolution, which is the correct routing behaviour.

These fields are domain-neutral. Any consumer can define label rules against them.
The engine owns the computation, not the interpretation.

Stats are written once at case start as part of the existing
`injectCbrExperiences()` pipeline. The CaseHubs configure
`CbrRetrievalTiming.PER_EVALUATION`, which governs when the engine's CBR
retrieval service runs. Currently the engine only calls retrieval at case start.
When PER_EVALUATION support is fully implemented (engine re-evaluates CBR on
context changes), these stats will update automatically because they are computed
inline with retrieval. Re-routing based on updated stats is a separate
enhancement — initial queue routing is the scope of this issue.

Engine change is ~20 lines added to `CaseStartedEventHandler`, following the
existing `WritableLayerImpl.engineSet()` pattern used for `cbrExperiences`.

---

## §2 ~~ResolutionConfidence~~ — Deferred

Removed. `ResolutionConfidence` has no consumer in this spec — the routing
pipeline checks context scalars via label rules directly, and queue listing REST
endpoints are out of scope. Ship the display computation in the issue where it is
actually consumed, designed against real display requirements.

---

## §3 IoT LabelRules for queue routing

Each CaseHub's `augment()` adds label rules appropriate to its case type.

### Safety override — structural, not conditional

`SafetyAlertCaseHub` and `SecurityAlertCaseHub` always route to immediate.
No CBR check — the safety gate is per-definition, not per-evaluation:

```java
definition.setLabelRules(List.of(
    new LabelRule("safety-immediate",
        new LambdaExpression<>(ctx -> true),
        List.of(new LabelAction.Add("iot-triage:immediate")))
));
```

### CBR-based routing for non-safety case types

`HvacAnomalyCaseHub` and `GenericResponseCaseHub` use shared triage rules:

```java
definition.setLabelRules(
    IoTTriageLabelRules.cbrTriageRules(
        triageConfig.aiMinSimilarity(), triageConfig.aiMinConsistency()));
```

`IoTTriageLabelRules` is a factory in `webapp-api` (Tier 1). It takes scalar
thresholds, not the `@ConfigMapping` interface — keeps it CDI-free:

```java
public final class IoTTriageLabelRules {

    // Floor similarity for operator-assisted routing. Below this, cases route to
    // operator-manual. Not configurable initially — no calibration data to justify
    // separate tuning. Promote to config when real usage data is available.
    static final double MEDIUM_FLOOR_SIMILARITY = 0.5;

    public static List<LabelRule> cbrTriageRules(
            double aiMinSimilarity, double aiMinConsistency) {
        return List.of(
            new LabelRule("cbr-high",
                new LambdaExpression<>(ctx -> {
                    double sim = doubleOr(ctx, "cbrBestSimilarity", 0.0);
                    double con = doubleOr(ctx, "cbrOutcomeConsistency", 0.0);
                    return sim >= aiMinSimilarity && con >= aiMinConsistency;
                }),
                List.of(new LabelAction.Add("iot-triage:ai-resolution"))),

            new LabelRule("cbr-medium",
                new LambdaExpression<>(ctx -> {
                    double sim = doubleOr(ctx, "cbrBestSimilarity", 0.0);
                    double con = doubleOr(ctx, "cbrOutcomeConsistency", 0.0);
                    return sim >= MEDIUM_FLOOR_SIMILARITY
                        && !(sim >= aiMinSimilarity && con >= aiMinConsistency);
                }),
                List.of(new LabelAction.Add("iot-triage:operator-assisted"))),

            new LabelRule("cbr-low-or-none",
                new LambdaExpression<>(ctx ->
                    doubleOr(ctx, "cbrBestSimilarity", 0.0) < MEDIUM_FLOOR_SIMILARITY),
                List.of(new LabelAction.Add("iot-triage:operator-manual")))
        );
    }

    private static double doubleOr(Map<String, Object> ctx, String key, double def) {
        Object v = ctx.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }
}
```

Rules are mutually exclusive by construction — exactly one fires per evaluation.

---

## §4 SubjectViewSpecs for queue views

| View Name | Label Pattern | Purpose |
|-----------|--------------|---------|
| `iot-immediate` | `iot-triage:immediate` | Safety/security — operator only |
| `iot-ai-resolution` | `iot-triage:ai-resolution` | High confidence — AI agent |
| `iot-operator-assisted` | `iot-triage:operator-assisted` | Medium — operator with suggestions |
| `iot-operator-manual` | `iot-triage:operator-manual` | Low/none — operator unassisted |

Created at startup by `IoTQueueViewInitializer` (`@Observes StartupEvent`) in
`webapp`. Queries `SubjectViewStore.findByTenancy()` for existing views and creates
missing ones via `SubjectViewOrchestrator.saveView()` — idempotent across restarts.

**Label path mapping:** Label strings produced by `LabelAction.Add` (e.g.,
`"iot-triage:immediate"`) are the label paths passed to
`SubjectViewOrchestrator.evaluateAndTrack(subjectId, tenancyId, labelPaths)`.
`SubjectViewSpec.labelPattern` is matched against these paths. The colon-delimited
naming convention (`namespace:value`) is a project convention, not a platform
requirement.

**Tenancy model:** The initializer injects a single `tenancyId` via config
property. IoT is deployed as single-tenant per instance — each deployment serves
one tenancy. Multi-tenant deployments would require per-tenant view creation,
which is a deployment architecture change, not a code change in this spec.

```java
@ApplicationScoped
public class IoTQueueViewInitializer {

    @Inject SubjectViewStore viewStore;
    @Inject SubjectViewOrchestrator orchestrator;

    @Inject @ConfigProperty(name = "casehub.iot.tenancy-id")
    String tenancyId;

    void onStartup(@Observes StartupEvent event) {
        List<SubjectViewSpec> existing = viewStore.findByTenancy(tenancyId);
        ensureView(existing, "iot-immediate", "iot-triage:immediate");
        ensureView(existing, "iot-ai-resolution", "iot-triage:ai-resolution");
        ensureView(existing, "iot-operator-assisted", "iot-triage:operator-assisted");
        ensureView(existing, "iot-operator-manual", "iot-triage:operator-manual");
    }

    private void ensureView(List<SubjectViewSpec> existing, String name, String labelPattern) {
        boolean found = existing.stream().anyMatch(v -> name.equals(v.name()));
        if (!found) {
            orchestrator.saveView(new SubjectViewSpec(
                UUID.randomUUID(),    // id
                name,                 // name
                tenancyId,            // tenancyId
                labelPattern,         // labelPattern — matched against label paths
                null,                 // scope — no path restriction
                "enqueuedAt",         // sortField — oldest first
                "ASC",                // sortDirection
                null,                 // additionalConditions — none
                Instant.now()         // createdAt
            ));
        }
    }
}
```

---

## §5 Configuration

```properties
casehub.iot.triage.ai-resolution.min-similarity=0.85
casehub.iot.triage.ai-resolution.min-consistency=0.80
```

Mapped via `@ConfigMapping`:

```java
@ConfigMapping(prefix = "casehub.iot.triage")
public interface IoTTriageConfig {

    @WithName("ai-resolution.min-similarity")
    @WithDefault("0.85")
    double aiMinSimilarity();

    @WithName("ai-resolution.min-consistency")
    @WithDefault("0.80")
    double aiMinConsistency();
}
```

Injected into each CaseHub and passed to `IoTTriageLabelRules.cbrTriageRules()`.

The medium/low boundary (`MEDIUM_FLOOR_SIMILARITY = 0.5`) is a named constant in
`IoTTriageLabelRules`, not configurable initially — no calibration data to justify
separate tuning. Can be promoted to config later.

Safety-critical case types are routed structurally: each safety CaseHub
(`SafetyAlertCaseHub`, `SecurityAlertCaseHub`) sets an unconditional
`ctx -> true` label rule in its `augment()` method (§3). No configuration
drives safety routing — it is a per-CaseHub structural property.
`IoTSafetyCaseTypes.SAFETY_CASE_TYPES` exists for risk classification and
suppression policy (`IoTActionRiskClassifier`, `IoTSuppressionTriggerPolicy`)
but is not used by the triage label-rule pipeline.

---

## §6 Testing strategy

### webapp-api (unit, no CDI)

- **IoTTriageLabelRulesTest** — evaluate rules against context maps with known
  scalars; verify exactly one label fires per context; verify mutual exclusivity
  across all four routing paths (safety-immediate, ai-resolution, operator-assisted,
  operator-manual); verify boundary values at MEDIUM_FLOOR_SIMILARITY and
  configurable AI thresholds

### webapp (integration, CDI)

- **IoTQueueViewInitializerTest** — startup creates four views; second startup
  idempotent; views have correct label patterns
- **IoTCbrTriageIntegrationTest** — end-to-end: seed CBR case base → create case
  → verify CaseQueueEntry in correct view based on CBR confidence. All four paths:
  safety-alert → immediate, high similarity + consistent outcomes → ai-resolution,
  medium similarity → operator-assisted, low/empty → operator-manual

### engine (cross-repo, unit)

- **CaseStartedEventHandlerTest** — verify injectCbrExperiences writes
  cbrBestSimilarity, cbrMatchCount, cbrOutcomeConsistency to working context;
  verify max similarity is correct; verify outcome consistency computation; verify
  empty experiences writes nothing

---

## §7 Module and dependency impact

### IoT modules affected

| Module | Change |
|--------|--------|
| `webapp-api` | `IoTTriageLabelRules` (scalar params, CDI-free) |
| `webapp` | `IoTTriageConfig` (`@ConfigMapping`), `IoTQueueViewInitializer`, CaseHub augment() updates, config properties, add `casehub-engine-queue` dependency |

### Cross-repo

| Repo | Change |
|------|--------|
| `casehub-engine` | ~20 lines in `CaseStartedEventHandler.injectCbrExperiences()` — adds three `WritableLayerImpl.engineSet()` calls after existing `cbrExperiences` write |
| `casehub-engine` | engine#730 (case queue module) — **prerequisite, unbuilt**. Provides `CaseLabelEvaluator`, `CaseQueueEntryManager`, `casehub-engine-queue` module. Platform infrastructure (`LabelRule`, `SubjectViewOrchestrator`, `SubjectViewSpec`) already exists. |

### New dependency

`webapp/pom.xml` adds `casehub-engine-queue` — needed for `CaseQueueEntry` and
`SubjectViewOrchestrator` access. The dependency already exists in the local Maven
repo (0.2-SNAPSHOT).

---

## §8 Scope boundaries

**In scope:**
- Engine CBR summary stats (§1)
- IoT label rules for triage (§3)
- Queue view setup (§4)
- Configuration (§5)

**Out of scope (future work):**
- Re-routing on context changes — CBR stats re-evaluated when engine implements
  full PER_EVALUATION support (TODO: create issue)
- Queue listing REST endpoints — display `CaseQueueEntry` data with triage
  metadata (TODO: create issue)
- AI resolution agent claiming from `iot-ai-resolution` queue (#63)
- CaseQueueEntry metadata/labels for CBR scores — enrichment for queue listing
  display (TODO: create issue)

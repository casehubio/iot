# CBR Infrastructure Design — casehub-iot #49

**Date:** 2026-07-12
**Issue:** casehubio/iot#49
**Epic:** casehubio/iot#48 (Case-Based Reasoning for IoT situation handling)

## Summary

Wire the CaseHub engine's existing CBR framework for the IoT webapp. The engine
owns the full lifecycle (Retain via `CbrCaseRetainObserver`, Retrieve via
`CbrRetrievalService`, Score via `CbrSimilarityScorer`). What's missing is a
concrete `CbrCaseMemoryStore` backend and IoT-specific wiring (feature schemas,
`CbrConfig` on CaseHub definitions).

The JPA store is a reusable platform module (`neocortex-memory-cbr-jpa`) following
the established neocortex CBR backend pattern (`memory-cbr-inmem`,
`memory-cbr-crossencoder`, `memory-cbr-embedding`). The IoT webapp depends on it
and provides application-specific schemas, extractors, and CbrConfig wiring.

## Engine CBR Framework (existing — not built here)

| Component | Role |
|-----------|------|
| `CbrCaseMemoryStore` | SPI: store, retrieveSimilar, erase, registerSchema |
| `NoOpCbrCaseMemoryStore` | Default `@DefaultBean` — returns empty results |
| `CbrCaseRetainObserver` | `CaseOutcomeObserver` — stores `PlanCbrCase` on case completion |
| `CbrRetrievalService` | Orchestrates retrieval — extracts features, builds query, dispatches by `cbrType` |
| `CbrSimilarityScorer` | Weighted multi-field similarity (categorical tables, gaussian/step/exponential decay, DTW, edit distance) |
| `CbrFeatureSchema` + `FeatureField` | Typed field definitions (Numeric, Categorical, Text, TimeSeries, DiscreteSequence, etc.) |
| `CbrConfig` | On `CaseDefinition` — feature extractor, weights, topK, minSimilarity, domain, cbrType |
| `CbrCaseTypeRegistration` | CDI SPI for registering custom case types |

## What We Build

Three pieces across three modules.

### Piece 1 — JPA CbrCaseMemoryStore (neocortex-memory-cbr-jpa)

New platform module `neocortex-memory-cbr-jpa` in `casehub-neocortex`, following the
established backend pattern:

| Module | Store | Activation |
|--------|-------|-----------|
| `neocortex-memory` | `NoOpCbrCaseMemoryStore` | `@DefaultBean` |
| `neocortex-memory-cbr-inmem` | `InMemoryCbrCaseMemoryStore` | `@Alternative @Priority(2)` |
| **`neocortex-memory-cbr-jpa`** | **`JpaCbrCaseMemoryStore`** | **`@Alternative @Priority(3)`** |
| `neocortex-memory-cbr-crossencoder` | `RerankingCbrCaseMemoryStore` | decorator |

`JpaCbrCaseMemoryStore implements CbrCaseMemoryStore`, annotated `@Alternative @Priority(3)`.
Overrides both `NoOpCbrCaseMemoryStore` and `InMemoryCbrCaseMemoryStore` when on the classpath.

#### Entity: CbrCaseEntity

Table: `cbr_case`

| Column | Type | Constraints |
|--------|------|-------------|
| id | UUID | PK, gen_random_uuid() |
| tenant_id | VARCHAR(255) | NOT NULL |
| domain | VARCHAR(255) | NOT NULL |
| case_type | VARCHAR(255) | NOT NULL |
| cbr_type | VARCHAR(50) | NOT NULL, default 'plan' |
| entity_id | VARCHAR(255) | NOT NULL (see entity_id semantics below) |
| case_id | VARCHAR(255) | nullable |
| problem | TEXT | NOT NULL |
| solution | TEXT | NOT NULL |
| outcome | TEXT | nullable |
| confidence | DOUBLE PRECISION | nullable |
| features | JSONB | NOT NULL, default '{}' |
| plan_traces | JSONB | nullable |
| stored_at | TIMESTAMPTZ | NOT NULL, default now() |

Indexes:
- `(tenant_id, domain, case_type)` — retrieval lookup
- `(entity_id, tenant_id)` — eraseEntity
- `(stored_at)` — notBefore filter

#### entity_id semantics

`CbrCaseRetainObserver` passes `"case-retain"` as `entityId` for all plan-type CBR
cases. This is a static marker, not a per-entity identifier. Consequently,
`eraseEntity("case-retain", tenantId)` would delete ALL CBR cases for a tenant.

This is intentional for the current design: plan-type CBR cases represent resolved
case outcomes, not entity-scoped memory. Entity-scoped erasure (e.g., "forget
everything about device X") is not a meaningful operation for plan CBR. Case base
management (purging old cases, trimming per case_type) should use `erase()` with
domain/tenant/caseType filtering — see #58.

Flyway migration: `V1__create_cbr_case.sql` in the module's `db/migration/` resources.
Consuming webapps include this via classpath — Quarkus aggregates migration locations
from all dependencies automatically.

#### Retrieval: filter-then-score

1. **SQL filter:** `tenant_id = ?`, `domain = ?`, `case_type = ?`, `stored_at >= ?` (if notBefore set)
2. **Java filter:** apply `CbrFilter` constraints on loaded feature maps
3. **Java score:** `CbrSimilarityScorer.score(queryFeatures, caseFeatures, weights, schema)` using the registered `CbrFeatureSchema`
4. **Java post-process:** filter by `minSimilarity`, sort descending, take `topK`
5. **Return:** `ScoredCbrCase<C>` with per-feature similarity breakdown in `featureSimilarities`

Supports `FEATURE_ONLY` retrieval mode natively. For `HYBRID`, the store degrades
gracefully to `FEATURE_ONLY` (logs an info message that the semantic component is
unavailable) since the feature-only component is always satisfiable. `SEMANTIC_ONLY`
throws `UnsupportedOperationException` — the store genuinely cannot satisfy any part
of a purely semantic request.

This graceful degradation is necessary because `CbrQuery.of()` defaults to
`RetrievalMode.HYBRID` and `CbrRetrievalService` does not override the retrieval mode.
Without degradation, every retrieval would throw and be silently swallowed by the
service's `recoverWithItem`, rendering CBR non-functional.

#### Schema caching

`registerSchema()` stores schemas in `ConcurrentHashMap<String, CbrFeatureSchema>` by
caseType. Looked up during scoring.

**Missing schema handling:** `CbrSimilarityScorer.scoreDetailed()` returns a perfect
score of 1.0 when schema is null — every candidate matches perfectly, producing
arbitrary topK ordering. The JPA store guards against this: if no schema is registered
for the query's caseType, `retrieveSimilar()` logs a warning and returns an empty list
rather than calling the scorer with a null schema. Schema presence is guaranteed under
normal operation by CDI `@Startup` registration (schemas are registered before any
case processing begins).

#### Feature validation

Following the `InMemoryCbrCaseMemoryStore` pattern, the JPA store validates features
against the registered schema using `CbrFeatureValidator` (from `neocortex-memory-api`):

- **On `store()`:** `CbrFeatureValidator.validateStoreFeatures(cbrCase.features(), schema)`
  — validates type compatibility (Categorical→String, Numeric→Number, etc.)
- **On `retrieveSimilar()`:** `CbrFeatureValidator.validateQueryFeatures(query.features(), schema)`
  — validates query feature types match schema field types
- **On `retrieveSimilar()` with filters:** `CbrFeatureValidator.validateFilters(query.filters(), schema)`
  — validates filter types match field types (e.g., Contains requires CategoricalList).
  Throws `IllegalStateException` if filters are present but no schema is registered.

Validation is skipped when no schema is registered for the caseType (store path only —
retrieval with missing schema returns empty per §Schema caching).

#### CbrFilter evaluation

Evaluated in Java after loading candidates, before scoring. The sealed `CbrFilter` hierarchy
maps directly to JSONB-deserialized feature map checks:

| Filter | Evaluation |
|--------|-----------|
| Contains(value) | feature value list contains value |
| ContainsAll(values) | feature value list contains all values |
| ContainsAny(values) | feature value list contains any value |
| HasMatch(subFields) | object list has element matching all sub-fields |
| NotContains(value) | feature value list does not contain value |
| NotContainsAny(values) | feature value list does not contain any values |
| ContainsRange(range) | list contains a value in [min, max] |
| AllOf(filters) | all child filters match |

#### Reconstruction

The store persists the full CbrCase data (problem, solution, outcome, confidence, features,
plan traces). On retrieval, it reconstructs the requested `Class<C>`:

- `PlanCbrCase.class` → full reconstruction including plan traces
- `FeatureVectorCbrCase.class` → projection (drop plan traces)
- Other types → construct from common CbrCase fields

This handles the fact that `CbrCaseRetainObserver` always stores `PlanCbrCase` (it has
access to plan traces), while consumers may request different types via `CbrConfig.cbrType()`.

### Piece 2 — IoT Feature Schemas (webapp-api)

`IoTCbrFeatureSchemas` — static utility class defining `CbrFeatureSchema` per case type.
Pure Java, Tier 1 (no JPA, no CDI).

#### Common features (all case types)

| Feature | Field Type | Similarity | Default Weight |
|---------|-----------|------------|----------------|
| deviceClass | Categorical | similarity table | 2.0 |
| roomType | Categorical | similarity table | 1.5 |
| hourOfDay | Numeric [0, 23] | gaussian decay (σ=3) | 1.0 |
| dayType | Categorical | exact match | 0.5 |
| season | Categorical | similarity table | 0.5 |

#### Device class similarity table

Encodes cross-device similarity for features that are functionally related:

| | thermostat | hvac | temperature_sensor |
|---|---|---|---|
| thermostat | 1.0 | 0.6 | 0.4 |
| hvac | 0.6 | 1.0 | 0.3 |
| temperature_sensor | 0.4 | 0.3 | 1.0 |

(Full table covers all device classes in the Matter Device Type Library vocabulary.)

#### Room type similarity table

| | bedroom | office | kitchen | dining |
|---|---|---|---|---|
| bedroom | 1.0 | 0.3 | 0.1 | 0.1 |
| office | 0.3 | 1.0 | 0.1 | 0.1 |
| kitchen | 0.1 | 0.1 | 1.0 | 0.7 |
| dining | 0.1 | 0.1 | 0.7 | 1.0 |

(Full table covers common residential room types.)

#### Season similarity table

| | spring | summer | autumn | winter |
|---|---|---|---|---|
| spring | 1.0 | 0.5 | 0.5 | 0.2 |
| summer | 0.5 | 1.0 | 0.2 | 0.1 |
| autumn | 0.5 | 0.2 | 1.0 | 0.5 |
| winter | 0.2 | 0.1 | 0.5 | 1.0 |

#### Per-type extensions

**hvac-anomaly:**
- `temperatureDelta` — Numeric [-20, 20], gaussian decay (σ=2), weight 1.5
- `outdoorTemperatureRange` — Categorical (freezing/cold/mild/warm/hot), weight 0.8

**safety-alert:**
- `alertType` — Categorical (smoke/co2/water_leak/gas), no cross-similarity, weight 3.0

**security-alert:**
- `entryPoint` — Categorical (front_door/back_door/window/garage), weight 1.5

**generic-response:**
- Common features only.

### Piece 3 — CbrConfig Wiring (webapp)

Each CaseHub's `augment()` method sets `CbrConfig` on the definition.

#### CbrConfig parameters

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| domain | "iot" | Single CBR domain for all IoT case types |
| caseType | `definition.getName()` | Dynamic — always matches YAML definition name (see alignment below) |
| cbrType | (default "plan") | PlanCbrCase carries plan traces — strictly richer than FeatureVectorCbrCase |
| topK | 5 | Sufficient for suggestion display without overwhelming |
| minSimilarity | 0.3 | Low enough to surface imperfect matches; high enough to exclude noise |
| timing | PER_EVALUATION | Fresh retrieval each evaluation — situations evolve quickly |
| vectorWeight | 0.0 | No vector/embedding component — JPA store is feature-only |
| weights | Per-type from schema definitions | See tables above |

#### caseType alignment

Schema caseType values must match the YAML definition names exactly. `CbrRetrievalService`
resolves caseType as `config.caseType() != null ? config.caseType() : definition.getName()`.
Using `definition.getName()` in augment() makes this coupling dynamic and self-maintaining
— if the YAML definition is renamed, the CbrConfig caseType updates automatically.

| YAML definition | CbrConfig.caseType | Schema caseType |
|----------------|-------------------|-----------------|
| hvac-anomaly | `definition.getName()` → "hvac-anomaly" | "hvac-anomaly" |
| safety-alert | `definition.getName()` → "safety-alert" | "safety-alert" |
| security-alert | `definition.getName()` → "security-alert" | "security-alert" |
| generic-response | `definition.getName()` → "generic-response" | "generic-response" |

Schema factory methods (e.g., `IoTCbrFeatureSchemas.hvacAnomaly()`) still hardcode their
caseType — these must match the YAML names. This is a compile-time constant validated by
integration tests, not a fragile runtime coupling.

#### Feature extractors

Static methods in `IoTCbrFeatureExtractors` (webapp-api, Tier 1). Each extractor pulls
from the CaseContext working layer:

- `deviceClass` ← `working.deviceClass`
- `roomType` ← `working.roomType`
- `hourOfDay` ← derived from `working.eventTimestamp` or `working.detectedAt`
- `dayType` ← derived from timestamp (weekday/weekend)
- `season` ← derived from timestamp and hemisphere config
- Type-specific fields from type-specific working layer keys

#### CbrConfig wiring — augment() example

`HvacAnomalyCaseHub.augment()` after CBR integration:

```java
@Override
protected void augment(final CaseDefinition definition) {
    final var descriptor = new HvacAnomalyCaseDescriptor(providers, registry);
    descriptor.workers().forEach(definition.getWorkers()::add);

    definition.setCbrConfig(CbrConfig.builder()
        .domain("iot")
        .caseType(definition.getName())
        .featureExtractor(IoTCbrFeatureExtractors::extractHvacAnomalyFeatures)
        .weight("deviceClass", 2.0)
        .weight("roomType", 1.5)
        .weight("temperatureDelta", 1.5)
        .weight("hourOfDay", 1.0)
        .weight("outdoorTemperatureRange", 0.8)
        .weight("dayType", 0.5)
        .weight("season", 0.5)
        .topK(5)
        .minSimilarity(0.3)
        .vectorWeight(0.0)
        .timing(CbrRetrievalTiming.PER_EVALUATION)
        .build());
}
```

Each CaseHub follows this pattern — `featureExtractor` is a lambda or JQ expression map,
weights match the schema's field set, and `caseType` is dynamically resolved from the
definition name.

#### Schema registration

A CDI `@Startup` bean in webapp registers all IoT schemas with the store:

```java
@Startup
void registerSchemas(@Observes StartupEvent event) {
    cbrStore.registerSchema(IoTCbrFeatureSchemas.hvacAnomaly());
    cbrStore.registerSchema(IoTCbrFeatureSchemas.safetyAlert());
    cbrStore.registerSchema(IoTCbrFeatureSchemas.securityAlert());
    cbrStore.registerSchema(IoTCbrFeatureSchemas.genericResponse());
}
```

## Module Placement

| Artifact | Module | Tier |
|----------|--------|------|
| `JpaCbrCaseMemoryStore` | neocortex-memory-cbr-jpa | 3 (JPA, CDI) |
| `CbrCaseEntity` | neocortex-memory-cbr-jpa | 3 (JPA) |
| Flyway V1 (cbr_case) | neocortex-memory-cbr-jpa | 3 |
| `IoTCbrFeatureSchemas` | webapp-api | 1 (pure Java) |
| `IoTCbrFeatureExtractors` | webapp-api | 1 (pure Java) |
| CbrConfig in CaseHubs | webapp | 3 (CDI augment) |
| Schema registration | webapp | 3 (CDI startup) |

## Data Flow

```
Situation fires → Ganglion detects → Case created
    ↓ (CaseTriggerConfig.baseCaseData carries device metadata → case file)
Workers execute → populate working layer with device context (#57)
    ↓
Case completes
    ↓
RETAIN — CbrCaseRetainObserver (engine):
    → Receives CaseOutcomeEvent with caseFileSnapshot (full case file)
    → CbrConfig.featureExtractor runs against snapshot:
        • JQ: evaluates against full snapshot as JsonNode
        • Lambda: wraps snapshot in SnapshotCaseContext
          (layer("working") returns the full snapshot data)
    → Constructs PlanCbrCase with features + plan traces
    → Calls JpaCbrCaseMemoryStore.store() → PostgreSQL cbr_case table
    ↓
Next similar situation fires → Case created
    ↓
RETRIEVE — CbrRetrievalService (engine):
    → CbrConfig.featureExtractor runs against live CaseContext:
        • JQ: evaluates against context.layer("working").asJsonNode()
        • Lambda: receives live CaseContext directly
    → Builds CbrQuery with features, weights, filters
    → Calls JpaCbrCaseMemoryStore.retrieveSimilar()
        → SQL filter: tenant/domain/caseType/notBefore
        → Java: CbrFilter evaluation
        → Java: CbrSimilarityScorer.score() per candidate (with registered schema)
        → Return top-K above minSimilarity
    → Maps to RetrievedExperience for downstream consumers
```

**Note:** Both retain and retrieve paths extract features from the case file data. The
retain path operates on the completed case's snapshot; the retrieve path operates on the
live case's working layer. Feature extractors must work against both — JQ expressions
should target top-level keys present in both contexts. Prerequisite: device metadata
must be populated in the case file (#57).

## Out of Scope

| Concern | Why deferred | Tracked |
|---------|-------------|---------|
| Case file population with device metadata | **Prerequisite** — CBR is inert without this | #57 (blocks #49) |
| Retention/purge policy | Table grows unboundedly; needs purge strategy | #58 |
| SEMANTIC_ONLY retrieval | Requires embedding model + vector store | Future store implementation |
| Situation resolution suggestion | Consumer of this infrastructure | #50 |
| Work item outcome prediction | Consumer of this infrastructure | #51 |
| False-positive suppression | Consumer of this infrastructure | #52 |

## Cold-Start Behavior

The case base starts empty. The framework handles this gracefully:

- **Empty case base:** `retrieveSimilar()` returns an empty list. No errors, no warnings.
- **Empty features:** both retain and retrieve paths return early when feature extraction
  yields an empty map — this is logged at WARN level.
- **Sub-threshold results:** if all candidates score below `minSimilarity`, an empty list
  is returned.

The first N situations processed after deployment get no CBR benefit. The case base
populates organically as cases complete and are retained. Cold-start strategy (seeding
from operator knowledge, suggestion-only mode, historical data import) is a consumer
concern addressed in downstream issues (#50, #51, #52) per the epic's bootstrapping
section (#48).

## Garden Context

- **GE-20260612-bd3b4d** — Degenerate CBR: trust-scored routing is Retain+Reuse only. This
  infrastructure adds the missing Retrieve step for IoT.

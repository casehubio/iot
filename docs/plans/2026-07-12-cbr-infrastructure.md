# CBR Infrastructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #49 — feat: CBR infrastructure — case base storage, similarity engine, retrieval API
**Issue group:** #49

**Goal:** Provide a JPA-backed `CbrCaseMemoryStore` in `casehub-neocortex` and IoT-specific CBR wiring (feature schemas, extractors, CbrConfig) in `casehub-iot`, enabling the engine's existing Retain/Retrieve/Score lifecycle to function end-to-end.

**Architecture:** The engine already provides `CbrCaseRetainObserver` (stores PlanCbrCase on case completion), `CbrRetrievalService` (orchestrates retrieval by cbrType), and `CbrSimilarityScorer` (weighted multi-field similarity). This plan builds: (1) a reusable JPA store module in `casehub-neocortex` following the existing `memory-cbr-inmem`/`memory-cbr-crossencoder` pattern, and (2) IoT feature schemas + CbrConfig wiring in `casehub-iot`.

**Tech Stack:** Java 22, Quarkus 3.x, JPA/Hibernate/Panache, PostgreSQL (JSONB), Flyway, Jackson, JUnit 5, AssertJ, Testcontainers

## Global Constraints

- All CBR SPIs come from `casehub-neocortex-memory-api` (0.2-SNAPSHOT)
- JPA store uses `@Alternative @Priority(3)` to override `NoOpCbrCaseMemoryStore` (`@DefaultBean`) and `InMemoryCbrCaseMemoryStore` (`@Alternative @Priority(2)`)
- Features use `FeatureValue` sealed type hierarchy, not raw `Map<String, Object>`
- `CbrSimilarityScorer.scoreDetailed()` is the canonical scoring function — never reimplement scoring
- `CbrFeatureValidator` must be called on store and retrieve paths (contract requirement from `InMemoryCbrCaseMemoryStore`)
- Filter evaluation follows the `InMemoryCbrCaseMemoryStore` reference implementation exactly
- `HYBRID` mode degrades to `FEATURE_ONLY` (not throw); `SEMANTIC_ONLY` returns empty list
- Cross-repo: Task 1 in `casehub-neocortex`, Tasks 2-3 in `casehub-iot`

---

### Task 1: JPA CbrCaseMemoryStore module (casehub-neocortex)

**Repo:** `/Users/mdproctor/claude/casehub/neocortex`

**Files:**
- Create: `memory-cbr-jpa/pom.xml`
- Create: `memory-cbr-jpa/src/main/java/io/casehub/neocortex/memory/cbr/jpa/CbrCaseEntity.java`
- Create: `memory-cbr-jpa/src/main/java/io/casehub/neocortex/memory/cbr/jpa/JpaCbrCaseMemoryStore.java`
- Create: `memory-cbr-jpa/src/main/java/io/casehub/neocortex/memory/cbr/jpa/FeatureValueJsonConverter.java`
- Create: `memory-cbr-jpa/src/main/resources/db/cbr/migration/V1__create_cbr_case.sql`
- Create: `memory-cbr-jpa/src/test/java/io/casehub/neocortex/memory/cbr/jpa/JpaCbrCaseMemoryStoreTest.java`
- Create: `memory-cbr-jpa/src/test/resources/application.properties`
- Modify: `pom.xml` (parent — add module + dependency management)

**Interfaces:**
- Consumes: `CbrCaseMemoryStore` SPI, `CbrSimilarityScorer.scoreDetailed()`, `CbrFeatureValidator`, `CbrFeatureSchema`, `FeatureValue`, `CbrFilter`, `CbrQuery`, `ScoredCbrCase`, `PlanCbrCase`, `FeatureVectorCbrCase`, `TextualCbrCase`, `CbrCaseMemoryStoreContractTest`
- Produces: `JpaCbrCaseMemoryStore` CDI bean (`@Alternative @Priority(3) @ApplicationScoped`), Flyway migration at `db/cbr/migration/`

- [ ] **Step 1: Create module directory structure**

```bash
mkdir -p /Users/mdproctor/claude/casehub/neocortex/memory-cbr-jpa/src/main/java/io/casehub/neocortex/memory/cbr/jpa
mkdir -p /Users/mdproctor/claude/casehub/neocortex/memory-cbr-jpa/src/main/resources/db/cbr/migration
mkdir -p /Users/mdproctor/claude/casehub/neocortex/memory-cbr-jpa/src/test/java/io/casehub/neocortex/memory/cbr/jpa
mkdir -p /Users/mdproctor/claude/casehub/neocortex/memory-cbr-jpa/src/test/resources
```

- [ ] **Step 2: Create pom.xml**

Follow the `memory-jpa` pattern. Key dependencies:
- `casehub-neocortex-memory-api` (compile)
- `quarkus-hibernate-orm-panache` (compile)
- `quarkus-flyway` (compile)
- `quarkus-jdbc-postgresql` (optional compile)
- `quarkus-jackson` (compile)
- `casehub-neocortex-memory-testing` (test) — provides `CbrCaseMemoryStoreContractTest`
- `casehub-neocortex-memory` (test) — provides `NoOpCbrCaseMemoryStore` needed for CDI
- `quarkus-junit5` (test)
- `quarkus-jdbc-h2` (test)

Build plugins: `quarkus-maven-plugin` (generate-code only, NOT build — same pattern as `memory-jpa` to avoid CDI augmentation failures), `jandex-maven-plugin`.

- [ ] **Step 3: Add module to parent pom.xml**

Add `<module>memory-cbr-jpa</module>` after `memory-cbr-crossencoder` in the modules list.
Add `<dependency>` entry in `<dependencyManagement>` for `casehub-neocortex-memory-cbr-jpa`.

- [ ] **Step 4: Create Flyway migration V1__create_cbr_case.sql**

```sql
CREATE TABLE cbr_case (
    id          UUID            NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   VARCHAR(255)    NOT NULL,
    domain      VARCHAR(255)    NOT NULL,
    case_type   VARCHAR(255)    NOT NULL,
    cbr_type    VARCHAR(50)     NOT NULL DEFAULT 'plan',
    entity_id   VARCHAR(255)    NOT NULL,
    case_id     VARCHAR(255),
    problem     TEXT            NOT NULL,
    solution    TEXT            NOT NULL,
    outcome     TEXT,
    confidence  DOUBLE PRECISION,
    features    JSONB           NOT NULL DEFAULT '{}',
    plan_traces JSONB,
    stored_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT cbr_case_pk PRIMARY KEY (id)
);

CREATE INDEX cbr_case_lookup_idx ON cbr_case (tenant_id, domain, case_type);
CREATE INDEX cbr_case_entity_idx ON cbr_case (entity_id, tenant_id);
CREATE INDEX cbr_case_stored_at_idx ON cbr_case (stored_at);
```

- [ ] **Step 5: Create CbrCaseEntity**

JPA entity using `PanacheEntityBase` (matching `MemoryEntry` pattern from `memory-jpa`).

Key fields: `id` (UUID PK), `tenantId`, `domain`, `caseType`, `cbrType`, `entityId`, `caseId`, `problem`, `solution`, `outcome`, `confidence`, `features` (String — serialized JSONB), `planTraces` (String — serialized JSONB), `storedAt` (Instant).

The `features` and `planTraces` columns store serialized JSON strings. The JPA store handles serialization/deserialization via `FeatureValueJsonConverter`.

- [ ] **Step 6: Create FeatureValueJsonConverter**

Utility class with two methods:
- `serializeFeatures(Map<String, FeatureValue>)` → JSON string
- `deserializeFeatures(String json)` → `Map<String, FeatureValue>`
- `serializePlanTraces(List<PlanTrace>)` → JSON string
- `deserializePlanTraces(String json)` → `List<PlanTrace>`

Uses Jackson `ObjectMapper` with a custom `FeatureValueModule` that handles the sealed type hierarchy. FeatureValue subtypes serialize as:
- `StringVal("x")` → `"x"` (plain JSON string)
- `NumberVal(1.0)` → `1.0` (plain JSON number)
- `RangeVal(1,2)` → `{"__type":"range","min":1.0,"max":2.0}`
- `StringListVal(["a"])` → `["a"]` (JSON array of strings)
- `NumberListVal([1.0])` → `{"__type":"numlist","values":[1.0]}`
- `StructVal({...})` → `{"__type":"struct","fields":{...}}`
- `StructListVal([{...}])` → `{"__type":"structlist","items":[{...}]}`

On deserialization, disambiguate:
- JSON string → `StringVal`
- JSON number → `NumberVal`
- JSON array of strings → `StringListVal`
- JSON array of numbers — check context (could be NumericListVal or something else)
- JSON object with `__type` → dispatch by type marker
- JSON object without `__type` → plain Map (fallback)

**Important:** `PlanTrace.parameters()` is `Map<String, Object>` (not FeatureValue), and PlanTrace fields `capabilityName`, `stepOutcome` may be FeatureValue in newer API versions — check the actual record constructor and handle both.

- [ ] **Step 7: Create JpaCbrCaseMemoryStore**

`@Alternative @Priority(3) @ApplicationScoped` implementing `CbrCaseMemoryStore`.

Inject: `EntityManager`, `FeatureValueJsonConverter`.

Schema cache: `ConcurrentHashMap<String, CbrFeatureSchema>`.

**`registerSchema()`**: Store in cache.

**`store()`**: Validate features (if schema registered), serialize to entity, persist.

**`retrieveSimilar()`**: Follow `InMemoryCbrCaseMemoryStore` logic exactly:
1. Handle retrieval mode: SEMANTIC_ONLY → return empty; HYBRID → log info, degrade to FEATURE_ONLY
2. Validate query features against schema (if registered)
3. Validate filters against schema (throw if filters present but no schema)
4. JPQL query: `SELECT e FROM CbrCaseEntity e WHERE e.tenantId = :t AND e.domain = :d AND e.caseType = :ct` (+ `AND e.storedAt >= :notBefore` if set)
5. Deserialize features from JSONB
6. Filter by `caseClass.isInstance()` — reconstruct CbrCase from entity and check
7. Apply CbrFilter matching (port from InMemoryCbrCaseMemoryStore.matchesFilters)
8. Score with `CbrSimilarityScorer.scoreDetailed(queryFeatures, caseFeatures, weights, schema, Map.of())`
9. Filter by minSimilarity, sort descending, take topK
10. Return `ScoredCbrCase<C>` with featureSimilarities

**Reconstruction logic** (entity → CbrCase):
- If stored cbrType is "plan" and PlanCbrCase.class requested → reconstruct PlanCbrCase with deserialized plan traces
- If stored cbrType is "plan" and FeatureVectorCbrCase.class requested → project (drop traces)
- If stored cbrType is "feature-vector" → reconstruct FeatureVectorCbrCase
- If stored cbrType is "textual" → reconstruct TextualCbrCase
- If CbrCase.class requested (wildcard) → reconstruct based on stored cbrType

**`erase()`**: DELETE WHERE entityId, domain, tenantId, caseId (nullable).

**`eraseEntity()`**: DELETE WHERE entityId, tenantId.

- [ ] **Step 8: Create contract test**

```java
package io.casehub.neocortex.memory.cbr.jpa;

import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.testing.CbrCaseMemoryStoreContractTest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class JpaCbrCaseMemoryStoreTest extends CbrCaseMemoryStoreContractTest {

    @Inject
    CbrCaseMemoryStore store;

    @Override
    protected CbrCaseMemoryStore store() {
        return store;
    }
}
```

The contract test (`CbrCaseMemoryStoreContractTest`) has 60+ tests covering:
- Store/retrieve round-trip
- Tenant/domain/caseType filtering
- Categorical exact match and similarity tables
- Numeric similarity decay (linear, gaussian, step, exponential)
- topK and minSimilarity thresholds
- Weighted scoring
- PlanCbrCase store and plan trace round-trip
- PlanCbrCase coexistence with FeatureVectorCbrCase
- notBefore temporal filtering
- CbrFilter: Contains, ContainsAll, ContainsAny, NotContains, NotContainsAny, ContainsRange, HasMatch, AllOf
- Structured fields: CategoricalList, NestedObject, ObjectList, NumericList
- TimeSeries DTW similarity
- DiscreteSequence edit distance
- Feature validation (type mismatches, unknown fields, structured field constraints)
- Retrieval modes (FEATURE_ONLY, HYBRID degradation, SEMANTIC_ONLY empty)
- Schema validation (duplicate fields, inner field constraints)

- [ ] **Step 9: Create test application.properties**

```properties
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:mem:cbr-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
quarkus.hibernate-orm.database.generation=none
quarkus.flyway.migrate-at-start=true
quarkus.flyway.locations=classpath:db/cbr/migration
```

- [ ] **Step 10: Build and verify**

```bash
mvn -C /Users/mdproctor/claude/casehub/neocortex -pl memory-cbr-jpa -am --batch-mode install
```

All 60+ contract tests must pass. If H2 compatibility issues arise with JSONB, switch to Testcontainers PostgreSQL (following the `memory-jpa` pattern with split test executions).

- [ ] **Step 11: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/neocortex add memory-cbr-jpa/ pom.xml
git -C /Users/mdproctor/claude/casehub/neocortex commit -m "feat: JPA CbrCaseMemoryStore — PostgreSQL backend for CBR case base (#49)"
```

---

### Task 2: IoT CBR Feature Schemas and Extractors (casehub-iot webapp-api)

**Repo:** `/Users/mdproctor/claude/casehub/iot`

**Files:**
- Create: `webapp-api/src/main/java/io/casehub/iot/webapp/cbr/IoTCbrFeatureSchemas.java`
- Create: `webapp-api/src/main/java/io/casehub/iot/webapp/cbr/IoTCbrFeatureExtractors.java`
- Create: `webapp-api/src/main/java/io/casehub/iot/webapp/cbr/package-info.java`
- Create: `webapp-api/src/test/java/io/casehub/iot/webapp/cbr/IoTCbrFeatureSchemasTest.java`
- Create: `webapp-api/src/test/java/io/casehub/iot/webapp/cbr/IoTCbrFeatureExtractorsTest.java`

**Interfaces:**
- Consumes: `CbrFeatureSchema`, `FeatureField`, `SimilaritySpec`, `FeatureValue`, `CaseContext` (from casehub-engine-api)
- Produces: `IoTCbrFeatureSchemas.hvacAnomaly()`, `.safetyAlert()`, `.securityAlert()`, `.genericResponse()` — static factory methods returning `CbrFeatureSchema`. `IoTCbrFeatureExtractors.extractHvacAnomalyFeatures(CaseContext)` etc. — static methods returning `Map<String, FeatureValue>`.

- [ ] **Step 1: Write schema test**

Test that each schema factory method:
- Returns a non-null schema with correct caseType
- Has the expected common fields (deviceClass, roomType, hourOfDay, dayType, season)
- Has type-specific fields (temperatureDelta for hvac-anomaly, alertType for safety-alert, etc.)
- Field types match spec (Categorical with similarity tables, Numeric with gaussian decay)
- Similarity tables are symmetric (if A→B=0.6, then B→A=0.6)
- Weights are in expected ranges

```java
@Test
void hvacAnomalySchema_hasCorrectFields() {
    var schema = IoTCbrFeatureSchemas.hvacAnomaly();
    assertThat(schema.caseType()).isEqualTo("hvac-anomaly");
    assertThat(schema.fields()).extracting(FeatureField::name)
        .contains("deviceClass", "roomType", "hourOfDay", "dayType", "season",
                  "temperatureDelta", "outdoorTemperatureRange");
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
mvn --batch-mode -C /Users/mdproctor/claude/casehub/iot -pl webapp-api test -Dtest=IoTCbrFeatureSchemasTest
```

Expected: compilation failure — `IoTCbrFeatureSchemas` does not exist.

- [ ] **Step 3: Implement IoTCbrFeatureSchemas**

Static utility class. Four factory methods, each returning `CbrFeatureSchema.of(caseType, fields...)`.

Common fields (shared by all schemas):
```java
private static List<FeatureField> commonFields() {
    return List.of(
        FeatureField.categorical("deviceClass", deviceClassSimilarity()),
        FeatureField.categorical("roomType", roomTypeSimilarity()),
        FeatureField.numeric("hourOfDay", 0, 23, new SimilaritySpec.GaussianDecay(3.0)),
        FeatureField.categorical("dayType"),
        FeatureField.categorical("season", seasonSimilarity())
    );
}
```

Similarity tables per the spec. `deviceClassSimilarity()` returns a `SimilaritySpec.CategoricalTable` covering Matter Device Type Library classes.

- [ ] **Step 4: Run schema tests — verify they pass**

```bash
mvn --batch-mode -C /Users/mdproctor/claude/casehub/iot -pl webapp-api test -Dtest=IoTCbrFeatureSchemasTest
```

- [ ] **Step 5: Write extractor test**

Test that each extractor:
- Returns a map with the expected keys when working layer has data
- Returns empty map when working layer is empty
- Derives hourOfDay, dayType, season from timestamp
- Handles missing optional fields gracefully

```java
@Test
void extractHvacFeatures_returnsExpectedKeys() {
    var ctx = mockCaseContext(Map.of(
        "deviceClass", "thermostat",
        "roomType", "bedroom",
        "eventTimestamp", "2026-01-15T03:00:00Z",
        "temperatureDelta", 4.5,
        "outdoorTemperatureRange", "cold"
    ));
    var features = IoTCbrFeatureExtractors.extractHvacAnomalyFeatures(ctx);
    assertThat(features).containsKeys("deviceClass", "roomType", "hourOfDay",
                                       "dayType", "season", "temperatureDelta",
                                       "outdoorTemperatureRange");
    assertThat(features.get("hourOfDay")).isEqualTo(FeatureValue.number(3));
    assertThat(features.get("dayType")).isEqualTo(FeatureValue.string("weekday"));
    assertThat(features.get("season")).isEqualTo(FeatureValue.string("winter"));
}
```

- [ ] **Step 6: Run extractor tests — verify they fail**

- [ ] **Step 7: Implement IoTCbrFeatureExtractors**

Static methods, one per case type. Each pulls from `CaseContext.layer("working")`:

```java
public static Map<String, FeatureValue> extractHvacAnomalyFeatures(CaseContext ctx) {
    var working = ctx.layer("working");
    var features = new LinkedHashMap<String, FeatureValue>();
    putIfPresent(features, "deviceClass", working, String.class);
    putIfPresent(features, "roomType", working, String.class);
    deriveTemporalFeatures(features, working);
    putNumericIfPresent(features, "temperatureDelta", working);
    putIfPresent(features, "outdoorTemperatureRange", working, String.class);
    return Map.copyOf(features);
}
```

Helper methods: `putIfPresent`, `putNumericIfPresent`, `deriveTemporalFeatures` (extracts hourOfDay, dayType, season from timestamp).

- [ ] **Step 8: Run extractor tests — verify they pass**

- [ ] **Step 9: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/iot add webapp-api/src/main/java/io/casehub/iot/webapp/cbr/ webapp-api/src/test/java/io/casehub/iot/webapp/cbr/
git -C /Users/mdproctor/claude/casehub/iot commit -m "feat: IoT CBR feature schemas and extractors (#49)"
```

---

### Task 3: CbrConfig Wiring and Integration (casehub-iot webapp)

**Repo:** `/Users/mdproctor/claude/casehub/iot`

**Files:**
- Modify: `webapp/pom.xml` — add `casehub-neocortex-memory-cbr-jpa` dependency
- Modify: `webapp/src/main/java/io/casehub/iot/webapp/app/engine/HvacAnomalyCaseHub.java` — add CbrConfig in augment()
- Modify: `webapp/src/main/java/io/casehub/iot/webapp/app/engine/SafetyAlertCaseHub.java` — add CbrConfig
- Modify: `webapp/src/main/java/io/casehub/iot/webapp/app/engine/SecurityAlertCaseHub.java` — add CbrConfig
- Modify: `webapp/src/main/java/io/casehub/iot/webapp/app/engine/GenericResponseCaseHub.java` — add CbrConfig
- Create: `webapp/src/main/java/io/casehub/iot/webapp/app/cbr/IoTCbrSchemaRegistration.java`
- Modify: `webapp/src/main/resources/application.properties` — add Flyway location for CBR migration
- Create: `webapp/src/test/java/io/casehub/iot/webapp/app/cbr/IoTCbrSchemaRegistrationTest.java`

**Interfaces:**
- Consumes: `IoTCbrFeatureSchemas` (from Task 2), `IoTCbrFeatureExtractors` (from Task 2), `CbrConfig.builder()`, `CbrCaseMemoryStore.registerSchema()`, `CaseDefinition.setCbrConfig()`, `CbrConfig.CbrRetrievalTiming`
- Produces: CbrConfig on all four IoT CaseDefinitions; schema registration at startup

- [ ] **Step 1: Add memory-cbr-jpa dependency to webapp pom.xml**

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-neocortex-memory-cbr-jpa</artifactId>
</dependency>
```

And add Flyway location to `application.properties`:
```properties
quarkus.flyway."iot-webapp".locations=classpath:db/iot-webapp/migration,classpath:db/cbr/migration
```

- [ ] **Step 2: Write schema registration test**

```java
@QuarkusTest
class IoTCbrSchemaRegistrationTest {
    @Inject CbrCaseMemoryStore store;

    @Test
    void schemasRegisteredAtStartup() {
        // Store a case with hvac-anomaly features — should not throw validation errors
        var fv = new FeatureVectorCbrCase("test", "solution", null, null,
            Map.of("deviceClass", FeatureValue.string("thermostat"),
                   "roomType", FeatureValue.string("bedroom"),
                   "hourOfDay", FeatureValue.number(3)));
        assertThatCode(() -> store.store(fv, "hvac-anomaly", "test", 
            new MemoryDomain("iot"), "test-tenant", "case-1"))
            .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 3: Implement IoTCbrSchemaRegistration**

```java
@ApplicationScoped
public class IoTCbrSchemaRegistration {
    @Inject CbrCaseMemoryStore cbrStore;

    void onStartup(@Observes StartupEvent event) {
        cbrStore.registerSchema(IoTCbrFeatureSchemas.hvacAnomaly());
        cbrStore.registerSchema(IoTCbrFeatureSchemas.safetyAlert());
        cbrStore.registerSchema(IoTCbrFeatureSchemas.securityAlert());
        cbrStore.registerSchema(IoTCbrFeatureSchemas.genericResponse());
    }
}
```

- [ ] **Step 4: Wire CbrConfig in HvacAnomalyCaseHub.augment()**

Add to the existing `augment()` method:

```java
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
```

- [ ] **Step 5: Wire CbrConfig in remaining CaseHubs**

Repeat Step 4 for SafetyAlertCaseHub, SecurityAlertCaseHub, GenericResponseCaseHub — each with its type-specific extractor and weights.

- [ ] **Step 6: Run registration and build tests**

```bash
mvn --batch-mode -C /Users/mdproctor/claude/casehub/iot -pl webapp test -Dtest=IoTCbrSchemaRegistrationTest
```

Then full build:
```bash
mvn --batch-mode -C /Users/mdproctor/claude/casehub/iot install
```

- [ ] **Step 7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/iot add webapp/pom.xml webapp/src/ 
git -C /Users/mdproctor/claude/casehub/iot commit -m "feat: CbrConfig wiring and schema registration in IoT CaseHubs (#49)"
```

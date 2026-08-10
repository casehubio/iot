# YAML Fixture Loading for iot-testing

**Issue:** casehubio/iot#8
**Date:** 2026-06-13
**Status:** Approved

---

## Context

CaseHub follows a dual-stack pattern: YAML and Java DSL are paired, first-class authoring paths that both produce the same canonical model. The `case-definition-layers` protocol establishes this for casehub-engine — YAML resource files and fluent Java DSL builders are equal production-grade paths to `CaseDefinition`.

C2 (test infrastructure) shipped Java fixture factories as the primary mechanism. This issue adds the YAML loading path — making `Fixtures.standardHome()` (Java DSL) and `DeviceFixtureLoader.load("fixtures/standard-home.yaml")` (YAML) equivalent peers producing `List<DeviceEntity>`.

The deferral of YAML in C2 was about work ordering, not about YAML being secondary.

---

## Design

### YAML Format

A fixture file is a self-contained device set definition. A top-level `defaults` block eliminates repetition. Each device declares a `type` discriminator that selects the concrete class.

```yaml
defaults:
  tenancyId: default-tenant
  lastUpdated: "2026-01-01T00:00:00Z"
  available: true

devices:
  - type: switch
    deviceId: switch-hallway-1
    label: Hallway Switch
    on: false

  - type: thermostat
    deviceId: thermostat-living-1
    label: Living Room Thermostat
    currentTemperature: { value: 21, unit: CELSIUS }
    targetTemperature: { value: 22, unit: CELSIUS }
    mode: HEAT

  - type: sensor
    deviceId: sensor-outdoor-1
    label: Outdoor Temperature
    sensorType: TEMPERATURE
    numericValue: 15
    unit: C

  - type: presence_sensor
    deviceId: presence-front-1
    label: Front Door Presence
    present: false
    lastSeen: "2026-01-01T00:00:00Z"

  - type: power_sensor
    deviceId: power-solar-1
    label: Solar Panel
    power: 3200

  - type: lock
    deviceId: lock-front-1
    label: Front Door Lock
    locked: true

  - type: cover
    deviceId: cover-bedroom-1
    label: Bedroom Blinds
    moving: false

  - type: media_player
    deviceId: media-living-1
    label: Living Room Speaker
    playing: false

  - type: fan
    deviceId: fan-bedroom-1
    label: Bedroom Fan
    on: false

  - type: light
    deviceId: light-living-1
    label: Living Room Light
    on: false
```

**Type discriminator convention:**

| Scope | Format | Examples |
|-------|--------|----------|
| Common types | lowercase of `DeviceClass` enum | `switch`, `light`, `thermostat`, `sensor`, `presence_sensor`, `power_sensor`, `lock`, `cover`, `media_player`, `fan` |
| Supplement types | `provider:base_class` | `openhab:light`, `openhab:thermostat`, `openhab:cover`, `homeassistant:light`, `homeassistant:thermostat`, `homeassistant:lock` |

The `deviceClass` field is inferred from the type — never set explicitly in YAML. `openhab:light` implies `DeviceClass.LIGHT`. If a `deviceClass` key is present in a device entry, the loader throws with a message: "deviceClass is inferred from type; do not set explicitly."

**One supplement per DeviceClass per provider.** The `provider:base_class` discriminator convention encodes a 1:1 mapping between a provider and a DeviceClass. This holds for all current supplements and is made explicit here as a design constraint.

**YAML 1.2 required.** The format uses `on` as a field key (e.g., `on: false` for switches). In YAML 1.1, `on` is a boolean alias for `true`. Jackson's `YAMLFactory` with Quarkus 3.x uses SnakeYAML Engine (YAML 1.2), where `on` is a plain string key. This works today but is a known cross-ecosystem gotcha.

### Architecture

Three components, following the two-layer mapper pattern from the `case-definition-layers` protocol:

```
YAML text
   │  Jackson YAMLFactory
   ▼
JsonNode tree (intermediate model)
   │  DeviceTypeHandler implementations
   ▼
DeviceEntity instances (canonical model via builders)
```

The `JsonNode` tree is the intermediate layer — parallel to engine's generated schema model, but simpler because the fixture format is flat. No custom DTO classes needed.

**`DeviceTypeHandler`** — SPI interface. One implementation per concrete device type. Reads fields from a `JsonNode` and calls the appropriate builder. Also provides `applyCommonFields` as a static interface method — shared by all handlers (common and supplement) to populate the 6 base `DeviceEntity` fields.

```java
public interface DeviceTypeHandler {
    String typeName();
    DeviceClass deviceClass();
    DeviceEntity fromYaml(JsonNode node, DeviceFixtureDefaults defaults);

    static <B extends DeviceEntity.Builder<?, B>> B applyCommonFields(
        B builder, JsonNode node, DeviceFixtureDefaults defaults, DeviceClass deviceClass) { ... }
}
```

**`DeviceFixtureLoader`** — the public API. Two usage modes:

Static convenience method (common case) — internally creates a `DeviceTypeRegistry` via ServiceLoader discovery:

```java
public final class DeviceFixtureLoader {
    // Static convenience — ServiceLoader-discovered registry
    public static List<DeviceEntity> load(String classpathResource) {
        return new DeviceFixtureLoader(DeviceTypeRegistry.discover()).loadResource(classpathResource);
    }

    // Constructor for explicit handler registration (tests, custom registries)
    public DeviceFixtureLoader(DeviceTypeRegistry registry) { ... }

    public List<DeviceEntity> loadResource(String classpathResource) { ... }
    public List<DeviceEntity> loadStream(InputStream yaml) { ... }
}
```

**ClassLoader strategy:** `loadResource()` uses `Thread.currentThread().getContextClassLoader()` first, falling back to `DeviceFixtureLoader.class.getClassLoader()`. Context classloader is correct in `@QuarkusTest` (where Quarkus creates an isolated test classloader that sees consumer `src/test/resources/`). The loader's own classloader is correct in plain JUnit. This is the standard Java library pattern for loading user-provided classpath resources.

```java
InputStream stream = Thread.currentThread().getContextClassLoader()
    .getResourceAsStream(classpathResource);
if (stream == null) {
    stream = DeviceFixtureLoader.class.getClassLoader()
        .getResourceAsStream(classpathResource);
}
if (stream == null) {
    throw new IllegalArgumentException("Resource not found: " + classpathResource);
}
```

**`DeviceTypeRegistry`** — maps type names to handlers. `discover()` factory method populates via `ServiceLoader<DeviceTypeHandler>`. The 10 common type handlers are registered via `iot-testing`'s own `META-INF/services/io.casehub.iot.testing.DeviceTypeHandler` file alongside the supplement handlers from provider modules — all handlers use the same discovery mechanism. No "built-in" vs "supplement" distinction in registration; the distinction is where the handlers live (iot-testing vs provider modules), not how they're registered.

### Defaults

`DeviceFixtureDefaults` — a value object populated by `DeviceFixtureLoader` via manual `JsonNode` extraction from the YAML `defaults:` block. Not a Jackson-deserialized record — manual extraction avoids the record + primitive boolean gotcha (GE-20260602-2cff5e) and is consistent with how handlers read fields throughout the pipeline.

```java
public final class DeviceFixtureDefaults {
    private final String tenancyId;
    private final Instant lastUpdated;
    private final boolean available;

    public DeviceFixtureDefaults(String tenancyId, Instant lastUpdated, boolean available) {
        this.tenancyId = tenancyId;
        this.lastUpdated = lastUpdated;
        this.available = available;
    }

    public String tenancyId() { return tenancyId; }
    public Instant lastUpdated() { return lastUpdated; }
    public boolean available() { return available; }
}
```

Loader parses defaults manually from `JsonNode`:

```java
String tenancyId = defaultsNode.has("tenancyId")
    ? defaultsNode.get("tenancyId").asText() : "default-tenant";
Instant lastUpdated = defaultsNode.has("lastUpdated")
    ? Instant.parse(defaultsNode.get("lastUpdated").asText())
    : Instant.parse("2026-01-01T00:00:00Z");
boolean available = defaultsNode.has("available")
    ? defaultsNode.get("available").asBoolean() : true;
```

| Field | Default when absent from `defaults:` block | Rationale |
|-------|---------------------------------------------|-----------|
| `tenancyId` | `"default-tenant"` | Matches `Fixtures.DEFAULT_TENANT` |
| `lastUpdated` | `"2026-01-01T00:00:00Z"` | Matches `Fixtures.EPOCH` |
| `available` | `true` | Matches every fixture in `Fixtures` |

Per-device fields override defaults. If a device omits `tenancyId`, it inherits from defaults. If it specifies one, the per-device value wins.

### Common field handling

Every handler reads the 6 base `DeviceEntity` fields the same way. The `DeviceTypeHandler.applyCommonFields()` static interface method populates the builder base fields from `JsonNode` + defaults. Each handler calls this first, then sets its type-specific fields from the node.

### Jackson configuration

The entire parsing pipeline uses `readTree()` → `JsonNode` → manual field extraction → builders. No `treeToValue()` deserialization into records or POJOs.

- `ObjectMapper(new YAMLFactory())` — tree-level parse only
- `findAndRegisterModules()` is **not needed** for this pipeline — it only matters when Jackson deserializes directly to records/POJOs via `treeToValue()`. Since all field extraction is manual from `JsonNode`, the garden entry GE-20260602-a4d290 does not apply to the core parsing path

### Error handling

- Missing required field (`deviceId`, `label`) → exception naming the device entry and missing field
- Unknown `type` value → exception listing registered types
- Malformed nested objects (bad Temperature value, unknown enum) → exception with field path
- Duplicate type registration → fail fast at `DeviceTypeRegistry` construction

---

## Module Placement and Dependencies

**All in `iot-testing`:**
- `DeviceTypeHandler` interface
- `DeviceFixtureLoader`
- `DeviceTypeRegistry`
- `DeviceFixtureDefaults`
- All 10 common type handlers
- `standard-home.yaml` at `fixtures/standard-home.yaml`

**New dependency for `iot-testing`:** `jackson-dataformat-yaml` (Jackson core and databind already on classpath via Quarkus BOM).

**Supplement handlers in provider modules:**

Each provider module ships its supplement handlers in main source tree under a `.testing` subpackage:
- `io.casehub.iot.homeassistant.testing` — `HomeAssistantLightHandler`, `HomeAssistantThermostatHandler`, `HomeAssistantLockHandler`
- `io.casehub.iot.openhab.testing` — `OpenHabLightHandler`, `OpenHabThermostatHandler`, `OpenHabRollershutterHandler`

Each registers via `META-INF/services/io.casehub.iot.testing.DeviceTypeHandler`.

**Dependency direction:** Provider modules gain an `<optional>true</optional>` compile-scope dependency on `casehub-iot-testing` (for the `DeviceTypeHandler` interface):

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-iot-testing</artifactId>
    <optional>true</optional>
</dependency>
```

This preserves the "iot-testing is never a compile or runtime dependency" constraint for downstream consumers:
1. Provider modules compile against `DeviceTypeHandler` — optional deps are present during the declaring module's build
2. Handler classes ship in the provider JAR
3. Downstream consumers (e.g., casehub-life) do **not** get `iot-testing` transitively — they add it explicitly at test scope (which they already do for `Fixtures`)
4. At test time, ServiceLoader finds the interface (from iot-testing) and handler implementations (from provider JARs) — both on the test classpath
5. At production runtime, handler classes are inert dead code — nobody calls `ServiceLoader.load(DeviceTypeHandler.class)` because `DeviceFixtureLoader` is test infrastructure

---

## Supplement Handler Registration — ServiceLoader

**Why ServiceLoader (not CDI):** `iot-testing` is a pure-Java library jar — no CDI container, no Quarkus runtime. `DeviceFixtureLoader` is called from test code, not injected by CDI. ServiceLoader is the standard Java SPI mechanism for this exact pattern, and the same mechanism used by MicroProfile Config SPIs in Quarkus.

**No-op when absent:** If neither `iot-homeassistant` nor `iot-openhab` is on the classpath, only the 10 common handlers are available. A YAML file referencing `openhab:light` gets a clear error: "Unknown device type 'openhab:light'. Registered types: [switch, light, ...]".

**Collision guard:** Two handlers registering the same `typeName()` → `DeviceTypeRegistry` throws at construction time.

**Native image and fat JAR concerns do not apply:** `iot-testing` is test-scope only — never packaged in a production artifact.

---

## Testing Strategy

### Equivalence test — common types

The core guarantee that YAML and Java DSL are true peers:

```java
void yamlStandardHomeMatchesJavaFixtures() {
    List<DeviceEntity> fromYaml = DeviceFixtureLoader.load("fixtures/standard-home.yaml");
    List<DeviceEntity> fromJava = Fixtures.standardHome();
    assertThat(fromYaml).usingRecursiveComparison()
        .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
        .isEqualTo(fromJava);
}
```

Structural equivalent of engine's `*EquivalenceTest` pattern.

**BigDecimal scale-insensitive comparison required.** AssertJ's `usingRecursiveComparison()` uses `BigDecimal.equals()` which is scale-sensitive — `new BigDecimal("21")` and `new BigDecimal("21.0")` are not equal despite representing the same mathematical value. This contradicts `Temperature.equals()`, which deliberately uses `compareTo()` for scale-insensitive equality. Jackson YAML produces `21.0` as scale 1 while Java `new BigDecimal("21")` produces scale 0. `withComparatorForType(BigDecimal::compareTo, BigDecimal.class)` aligns the test with the platform's own semantics. This applies to all equivalence tests — common and supplement — since BigDecimal appears in Temperature, SensorDevice, PowerSensor, OpenHabHsbType, and OpenHabThermostat.

**Ordering constraint:** `usingRecursiveComparison().isEqualTo()` on lists compares elements by index. The `standard-home.yaml` device order must match `Fixtures.standardHome()` exactly. This is intentional — the YAML file exists to be the exact equivalent of the Java factory, and order is part of that equivalence.

### Loader tests

- Load valid fixture file → correct device count, correct types, correct field values
- Defaults applied when per-device fields omitted
- Per-device fields override defaults
- Missing required field (`deviceId`, `label`) → clear error message
- Unknown `type` → error listing registered types
- Malformed nested objects → error with field path
- Empty device list → empty `List<DeviceEntity>`
- No `defaults:` block → built-in defaults apply

### Handler tests

Each of the 10 common handlers tested individually:
- Correct `typeName()` and `deviceClass()` returned
- All fields populated from JsonNode
- Optional fields absent → null/default
- Complex value types handled correctly (Temperature record, enums)

### Supplement handler tests

In each provider module's test tree:

**OpenHAB:**
- `openhab:light` → `OpenHabLight` with `hsb` field
- `openhab:thermostat` → `OpenHabThermostat` with `heatingDemand`/`coolingDemand`
- `openhab:cover` → `OpenHabRollershutter` with `upDown`

**Home Assistant:**
- `homeassistant:light` → `HomeAssistantLight` with `rgbColor`, `effect`, `supportedColorModes`
- `homeassistant:thermostat` → `HomeAssistantThermostat` with `presetMode`, `swingMode`, `hvacAction`
- `homeassistant:lock` → `HomeAssistantLock` with `changedBy`, `codeSlot`

### Supplement equivalence tests

Each provider module ships its own YAML fixture file and equivalence test — proving YAML/Java DSL parity holds for supplements, not just common types:

```java
// In iot-homeassistant test tree
void yamlSupplementMatchesJavaConstruction() {
    var fromYaml = DeviceFixtureLoader.load("fixtures/ha-devices.yaml");
    var fromJava = List.of(
        HomeAssistantLight.builder()...build(),
        HomeAssistantThermostat.builder()...build(),
        HomeAssistantLock.builder()...build());
    assertThat(fromYaml).usingRecursiveComparison()
        .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
        .isEqualTo(fromJava);
}
```

Fixture files: `fixtures/ha-devices.yaml` in iot-homeassistant, `fixtures/oh-devices.yaml` in iot-openhab.

### ServiceLoader discovery test

Verify supplement types are auto-registered when provider module is on the classpath — no explicit configuration needed.

---

## Garden Entries to Observe

- **GE-20260602-a4d290:** `new ObjectMapper(new YAMLFactory())` silently produces null record fields — not applicable to this design (all parsing is `readTree()` → manual `JsonNode` extraction), but relevant if any future path adds `treeToValue()` deserialization
- **GE-20260602-2cff5e:** Jackson cannot set absent YAML key into primitive boolean record field — avoided by design: `DeviceFixtureDefaults` uses manual `JsonNode` extraction, not record deserialization
- **GE-20260525-606069:** YAML string values with special chars produce invalid YAML unless single-quoted — relevant for fixture YAML authoring

## Post-Close

- **ARC42STORIES §9.4 naming alignment:** Update L5 C4 diagram names to match implemented classes — `OpenHABThermostatItem` → `OpenHabThermostat`, `OpenHABColorItem` → `OpenHabLight`, `OpenHABRollershutter` casing → `OpenHabRollershutter`

---

## Out of Scope

- Round-trip serialization (DeviceEntity → YAML) — additive, file as future issue if needed
- YAML schema validation tooling — the loader validates structurally; JSON Schema validation is not needed for test fixtures
- CDI integration (`@Inject DeviceFixtureLoader`) — the loader is a plain utility class, not a CDI bean

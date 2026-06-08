# Chapter 2 — Test Infrastructure Design

**Issue:** casehubio/casehub-iot#2
**Modules:** iot-api (C1 API enhancements), iot-testing (new C2 code)
**Depends on:** C1 Core API (complete)

---

## Overview

Test infrastructure for `casehub-iot` — enables downstream consumers (casehub-life Layer 9, future apps) to write device-dependent tests without a live Home Assistant or OpenHAB instance.

Two concerns, two modules:
- **iot-api enhancements** — `capabilities()`, `toBuilder()`, and `deriveChangedCapabilities()` make the DeviceEntity hierarchy self-describing and state-transition-friendly. These serve tests, providers, and consumers equally.
- **iot-testing** — MockDeviceProvider, MockDeviceRegistry, StateChangeEventPublisher, and Java fixture factories. Test-scope only.

---

## C1 API Enhancements (iot-api)

### Temperature.equals() — C1 fix required before deriveChangedCapabilities() is correct

`Temperature` is a Java record with no custom `equals()`/`hashCode()`. The auto-generated `equals()` delegates to `BigDecimal.equals()`, which is **scale-sensitive**: `new BigDecimal("21") != new BigDecimal("21.0")` even though they represent the same value. `deriveChangedCapabilities()` calls `Objects.equals()` on capability map values. A provider that constructs `Temperature(new BigDecimal("21.5"), CELSIUS)` from one platform event and `Temperature(new BigDecimal("21.50"), CELSIUS)` from the next will produce a spurious `"currentTemperature"` entry in `changedCapabilities` for an unchanged temperature.

The existing `TemperatureTest.recordEquality()` does not catch this — it uses `BigDecimal.valueOf(20)` in both instances, which always produces scale 0.

**Fix — add to Temperature.java:**

```java
@Override
public boolean equals(Object o) {
    if (!(o instanceof Temperature other)) return false;
    return unit == other.unit && value.compareTo(other.value) == 0;
}

@Override
public int hashCode() {
    return Objects.hash(unit, value.stripTrailingZeros());
}
```

**Add to TemperatureTest:**
```java
@Test
void equalityIsScaleInsensitive() {
    var a = new Temperature(new BigDecimal("21"), Temperature.TemperatureUnit.CELSIUS);
    var b = new Temperature(new BigDecimal("21.0"), Temperature.TemperatureUnit.CELSIUS);
    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
}
```

This fix must be applied to C1 before `deriveChangedCapabilities()` is trustworthy.

---

### DeviceEntity.capabilities()

Concrete method on `DeviceEntity`. The base implementation returns a `LinkedHashMap` containing the availability capability. Subclasses override, call `super.capabilities()`, and add their domain fields.

**Base implementation on DeviceEntity:**

```java
public static final String CAP_AVAILABLE = "available";

public Map<String, Object> capabilities() {
    Map<String, Object> caps = new LinkedHashMap<>();
    caps.put(CAP_AVAILABLE, available);
    return caps;
}
```

**Subclass override — LightDevice example:**

```java
@Override
public Map<String, Object> capabilities() {
    Map<String, Object> caps = super.capabilities();
    caps.put(CAP_ON, on);
    caps.put(CAP_BRIGHTNESS, brightness);   // null when absent — key presence means capability exists
    caps.put(CAP_COLOR_TEMP, colorTemp);    // null when absent
    return caps;
}
```

**All 10 concrete subclasses override capabilities():**

| Device | Capabilities included (beyond CAP_AVAILABLE) |
|---|---|
| `SwitchDevice` | `CAP_ON` |
| `LightDevice` | `CAP_ON`, `CAP_BRIGHTNESS`, `CAP_COLOR_TEMP` |
| `ThermostatDevice` | `CAP_CURRENT_TEMPERATURE`, `CAP_TARGET_TEMPERATURE`, `CAP_MODE` |
| `SensorDevice` | `CAP_NUMERIC_VALUE`, `CAP_BINARY_VALUE` |
| `PresenceSensor` | `CAP_PRESENT`, `CAP_LAST_SEEN` |
| `PowerSensor` | `CAP_POWER`, `CAP_ENERGY` |
| `LockDevice` | `CAP_LOCKED` |
| `CoverDevice` | `CAP_POSITION`, `CAP_MOVING` |
| `MediaPlayerDevice` | `CAP_PLAYING`, `CAP_VOLUME` |
| `FanDevice` | `CAP_ON`, `CAP_SPEED` |

**`CAP_UNIT` removed from `SensorDevice`:** `unit` is static sensor configuration (the physical quantity a sensor measures), not a changeable capability state. It does not appear in `changedCapabilities`. A `CAP_*` constant that never appears in `capabilities()` is dead code — it misleads readers into thinking it tracks runtime state. `CAP_UNIT` is deleted. `unit` remains a field and accessor on `SensorDevice`; it is copied faithfully in `toBuilder()`.

**`CAP_AVAILABLE` on `DeviceEntity`:** Availability is a runtime state — a device transitions from `available=true` to `available=false` when it goes offline. With `CAP_AVAILABLE` in the base capabilities, `deriveChangedCapabilities(before, after)` will correctly include `"available"` in the changed set when a device goes offline or comes back online. Consumers can pattern-match on `"available"` to detect connectivity transitions without separate event types.

**Rules:**
- Only domain-specific fields that can change independently at runtime — the things that appear in `changedCapabilities`. `unit` and `sensorType` on SensorDevice are configuration and classification respectively; neither belongs in capabilities().
- Null-valued entries are included. A null `brightness` is semantically distinct from "this device has no brightness capability." The key's presence means the capability exists; the value is its current state (which may be null/absent).
- **Allocation contract:** `capabilities()` must always allocate a fresh `LinkedHashMap`. Never cache. The supplement override chain (C3/C4) calls `super.capabilities()` and adds entries to the returned map — if the base returned a cached instance, every call would accumulate entries from all previous callers.

**Supplement chain protocol (C3/C4):** Vendor supplement types override `capabilities()`, call `super.capabilities()`, add their supplement-specific CAP_ entries, and return the map. For a 3-level chain (DeviceEntity → LightDevice → HomeAssistantLight), allocation happens once at the root; each level adds to the map and returns it:

```java
// HomeAssistantLight (C3) — vendor supplement
@Override
public Map<String, Object> capabilities() {
    Map<String, Object> caps = super.capabilities();  // delegates up to LightDevice → DeviceEntity
    caps.put(CAP_RGB_COLOR, rgbColor);
    caps.put(CAP_EFFECT, effect);
    return caps;
}
```

---

### DeviceEntity.toBuilder()

**Not declared on `DeviceEntity`.** Each concrete subclass declares and implements `toBuilder()` independently, returning its own typed builder. There is no abstract method on the base class.

Rationale: an abstract `toBuilder()` on `DeviceEntity` would require a `Builder<?, ?>` wildcard return type. Callers with a `DeviceEntity` reference would receive a builder whose `build()` returns `DeviceEntity`, not the concrete subtype — losing type information silently. Since `toBuilder()` is only meaningful when the caller knows the concrete type (needed to set type-specific fields and receive the correct return type), the method belongs on the concrete class, not the abstract base. The cost is that `toBuilder()` is not polymorphic via `DeviceEntity` references — callers needing to copy-with-modify must hold a typed reference.

**LightDevice (AbstractBuilder type — supplement extension supported):**

```java
public LightDevice.Builder toBuilder() {
    return new Builder()
        .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
        .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId())
        .on(on).brightness(brightness).colorTemp(colorTemp);
}
```

Private fields (`on`, `brightness`, `colorTemp`) are accessed directly inside `LightDevice`. Base class fields are accessed via public accessors.

**SwitchDevice (flat Builder type — no supplement planned):**

```java
public SwitchDevice.Builder toBuilder() {
    return SwitchDevice.builder()
        .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
        .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId())
        .on(on);
}
```

**SensorDevice (flat Builder, `unit` copied for round-trip fidelity):**

```java
public SensorDevice.Builder toBuilder() {
    return SensorDevice.builder()
        .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
        .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId())
        .sensorType(sensorType).numericValue(numericValue).unit(unit).binaryValue(binaryValue);
}
```

`unit` is still set in `toBuilder()` even though it is not a capability. It is a field on the device and must be preserved in round-trip copies.

**All 10 concrete subclasses implement `toBuilder()`** returning their concrete builder. LightDevice, ThermostatDevice, LockDevice, and CoverDevice use `new Builder()` (their inner concrete Builder class) because they have `AbstractBuilder` for vendor supplement extension. The remaining six use their typed static factory.

**AbstractBuilder invariant:** `AbstractBuilder` is present only where vendor supplement subclasses are planned in C3/C4. Adding `AbstractBuilder` to types with no confirmed supplement use case is unnecessary complexity. Current mapping:

| Device | AbstractBuilder | Reason |
|---|---|---|
| `LightDevice` | ✓ | HomeAssistantLight, OpenHABColorItem planned |
| `ThermostatDevice` | ✓ | HomeAssistantThermostat, OpenHABThermostatItem planned |
| `LockDevice` | ✓ | HomeAssistantLock planned |
| `CoverDevice` | ✓ | OpenHABRollershutter planned |
| `SwitchDevice`, `SensorDevice`, `PresenceSensor`, `PowerSensor`, `MediaPlayerDevice`, `FanDevice` | — | No vendor supplement planned |

**Supplement `toBuilder()` pattern (C3/C4):** Supplement types declare their own `toBuilder()` returning their supplement builder. They access parent class private fields via public accessor methods (since they live in a different class), and their own private fields directly:

```java
// HomeAssistantLight (C3) — accessing parent fields via accessors
public HomeAssistantLight.Builder toBuilder() {
    return new Builder()
        .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
        .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId())
        .on(isOn()).brightness(brightness().orElse(null)).colorTemp(colorTemp().orElse(null))
        .rgbColor(rgbColor).effect(effect).supportedColorModes(supportedColorModes);
}
```

**Why:** State transitions on immutable value objects are the fundamental operation in IoT. Without `toBuilder()`, creating "the same device with one field changed" requires rebuilding from scratch. Providers (C3/C4) and consumers both need this. In tests, fixtures use `toBuilder()` to produce variants: `Fixtures.frontDoorLock().toBuilder().locked(false).build()`.

---

### StateChangeEvent.deriveChangedCapabilities()

Static utility on `StateChangeEvent`:

```java
public static Set<String> deriveChangedCapabilities(
        DeviceEntity before, DeviceEntity after) {
    if (before.getClass() != after.getClass()) {
        throw new IllegalArgumentException(
            "Cannot derive changed capabilities across different types: "
            + before.getClass().getSimpleName() + " vs "
            + after.getClass().getSimpleName());
    }
    Map<String, Object> capsBefore = before.capabilities();
    Map<String, Object> capsAfter = after.capabilities();
    Set<String> changed = new LinkedHashSet<>();
    for (var entry : capsAfter.entrySet()) {
        Object prev = capsBefore.get(entry.getKey());
        if (!Objects.equals(prev, entry.getValue())) {
            changed.add(entry.getKey());
        }
    }
    return Set.copyOf(changed);
}
```

Handles null→non-null, non-null→null, and value changes.

**Why iterating `capsAfter` is exhaustive:** The same-type precondition (`before.getClass() != after.getClass()` → throws) guarantees that `before.capabilities()` and `after.capabilities()` produce identical key sets. Two `LightDevice` instances always return the same keys from `capabilities()`. Therefore iterating `capsAfter` and checking against `capsBefore` covers every key — there are no keys in `capsBefore` absent from `capsAfter`. The iteration is complete without iterating both directions.

**`StateChangeEvent.of()` factory is not included.** `deriveChangedCapabilities()` is the reusable static utility. Callers that need to construct a complete `StateChangeEvent` — including `StateChangeEventPublisher` and production providers — use the record constructor directly, supplying their own `Instant` (platform timestamp for providers, `Instant.now()` for test helpers):

```java
// In StateChangeEventPublisher (test helper):
new StateChangeEvent(
    before, after,
    StateChangeEvent.deriveChangedCapabilities(before, after),
    Instant.now(), providerId)

// In HomeAssistantProvider (C3, using platform timestamp):
new StateChangeEvent(
    before, after,
    StateChangeEvent.deriveChangedCapabilities(before, after),
    haEvent.lastChanged(), providerId)
```

A convenience factory in `StateChangeEvent` that calls `Instant.now()` would silently discard the platform-provided timestamp when production providers adopted it. Keeping the constructor explicit forces each caller to be intentional about `occurredAt`.

---

### CdiDeviceRegistryTest — required rewrite (iot-api/test)

`CdiDeviceRegistryTest.stateChangeUpdatesRegistry()` currently calls `((CdiDeviceRegistry) registry).onStateChange(...)` directly — a package-private method call that bypasses CDI event infrastructure entirely. This test demonstrates an anti-pattern rather than the actual production pathway.

C2 rewrites this test to use raw CDI `Event<StateChangeEvent>` injection. `StateChangeEventPublisher` from iot-testing cannot be used here (circular dependency: iot-testing depends on iot-api). The rewrite uses CDI directly, which is what `StateChangeEventPublisher` wraps:

```java
@Inject
Event<StateChangeEvent> events;

@Test
void stateChangeUpdatesRegistry() throws Exception {
    var before = (SwitchDevice) registry.findById("sw1").orElseThrow();
    var after = before.toBuilder().on(false).lastUpdated(Instant.now()).build();

    events.fireAsync(new StateChangeEvent(
        before, after,
        StateChangeEvent.deriveChangedCapabilities(before, after),
        Instant.now(), "test"))
    .toCompletableFuture().join();

    var updated = (SwitchDevice) registry.findById("sw1").orElseThrow();
    assertThat(updated.isOn()).isFalse();
}
```

**Join is mandatory.** `CdiDeviceRegistry.onStateChange()` is an `@ObservesAsync` handler. The CDI specification guarantees that the `CompletionStage` returned by `Event.fireAsync()` completes only after all asynchronous observers have finished. Without `.join()`, the test assertion races against the registry update.

---

## Test Infrastructure (iot-testing)

### MockDeviceProvider

Implements `DeviceProvider`. Plain Java POJO — no CDI dependency by design.

**Design decision — CDI-free:** Mixing CDI concerns into the mock would prevent it from being instantiated in pure unit tests without a CDI container. Firing CDI events requires a container. Event firing is the responsibility of `StateChangeEventPublisher`. MockDeviceProvider handles only device state management and command recording.

```java
public class MockDeviceProvider implements DeviceProvider {
    private final String providerId;
    private final Map<String, DeviceEntity> devices = new LinkedHashMap<>();
    private final List<DeviceCommand> dispatchedCommands = new ArrayList<>();
    private ProviderStatus status = ProviderStatus.CONNECTED;
    private CommandResult dispatchResult = CommandResult.SENT;
}
```

**Device management:**
- `addDevice(DeviceEntity)` — adds to internal map keyed by deviceId
- `removeDevice(String deviceId)` — removes from map
- `clear()` — removes all devices

**SPI implementation:**
- `discover()` — returns `List.copyOf(devices.values())`
- `dispatch(DeviceCommand)` — records in dispatchedCommands, returns dispatchResult
- `status()` — returns current status
- `providerId()` — returns constructor-provided id

**Test control:**
- `setStatus(ProviderStatus)` — control status() return value
- `setDispatchResult(CommandResult)` — control dispatch() return value
- `dispatchedCommands()` — unmodifiable view of all dispatched commands
- `clearDispatchedCommands()` — reset command log

**CDI wiring in `@QuarkusTest`:** MockDeviceProvider is not a CDI bean by default. To use it in a `@QuarkusTest`, declare it as an inner static class annotated `@ApplicationScoped @Alternative @Priority(1)`. This is the established CaseHub pattern (already present in `CdiDeviceRegistryTest.TestProvider`):

```java
@QuarkusTest
class MyConsumerTest {

    @ApplicationScoped
    @Alternative
    @Priority(1)
    static class MockProvider extends MockDeviceProvider {
        MockProvider() {
            super("test");
            addDevice(Fixtures.livingRoomLight());  // populate in constructor — called once by CDI
        }
        // discover() not overridden — super returns List.copyOf(devices.values())
    }

    @Inject
    DeviceRegistry registry;
    // ...
}
```

**Do not override `discover()` to call `addDevice()`.** `CdiDeviceRegistry.refresh()` calls `discover()` on every provider — at startup and on every explicit `refresh()` call. An `addDevice()` inside `discover()` would re-add the device on each refresh. Because `LinkedHashMap.put()` overwrites on the same key it won't duplicate, but the pattern is semantically wrong and misleading. Populate in the constructor — `@ApplicationScoped` beans are constructed once per CDI container lifetime.

Devices must be pre-loaded before the CDI container fires `StartupEvent` (which triggers the initial `refresh()`). Constructor population satisfies this automatically.

**@QuarkusTest state bleed:** MockDeviceProvider is `@ApplicationScoped` — the same CDI bean instance is shared across all test methods in a `@QuarkusTest` class. Both `devices` and `dispatchedCommands` persist across test method boundaries. Test classes using MockDeviceProvider must clear both in `@BeforeEach`:

```java
@Inject MockProvider mockProvider;  // the @Alternative subclass

@BeforeEach
void reset() {
    mockProvider.clear();
    mockProvider.clearDispatchedCommands();
}
```

Omitting this causes device state and command history from one test method to leak into the next. (Garden: GE-20260427-edbacd — "@QuarkusTest state bleed via missing clear() calls".)

**Registry consistency:** After the CDI container discovers MockDeviceProvider and the registry populates, adding further devices to the mock does not automatically update the registry. Call `registry.refresh()` to force re-discovery. In test scenarios testing event observation, consumers should use `event.after()`, not re-read from the registry — this is the correct production pattern (documented as an anti-pattern in ARC42STORIES §8).

---

### MockDeviceRegistry

Implements `DeviceRegistry`. Plain Java, no CDI. For unit tests that need a registry without a CDI container.

```java
public class MockDeviceRegistry implements DeviceRegistry {
    private final Map<String, DeviceEntity> devices = new LinkedHashMap<>();
}
```

**Test setup:**
- `addDevice(DeviceEntity)` — adds to map
- `addDevices(DeviceEntity...)` — varargs convenience
- `addDevices(List<DeviceEntity>)` — batch add
- `clear()` — removes all

**SPI implementation:**
- `findById(String)` — `Optional.ofNullable(devices.get(deviceId))`
- `findByClass(Class<T>)` — filter by `isInstance`
- `findByTenancyId(String)` — filter by tenancyId
- `findAll()` — `List.copyOf(devices.values())`
- `refresh()` — no-op. The mock is populated programmatically, not from a provider. No-op is intentional.

**When to use which registry:**
- **Unit tests:** instantiate `MockDeviceRegistry`, populate with fixtures, pass to class under test
- **`@QuarkusTest`:** `CdiDeviceRegistry` discovers `MockDeviceProvider` automatically — use MockDeviceProvider directly, no MockDeviceRegistry needed

**DeviceRegistryContractTest disposition:** `DeviceRegistryContractTest` in iot-api/test contains an inline `SimpleDeviceRegistry` used to validate the registry contract. With `MockDeviceRegistry` shipping in C2, `SimpleDeviceRegistry` is subsumed and the contract test is redundant. Delete `DeviceRegistryContractTest` from iot-api/test. The registry contract is now validated by:
- `MockDeviceRegistryTest` in iot-testing/test — validates `DeviceRegistry` contract against `MockDeviceRegistry`
- `CdiDeviceRegistryTest` in iot-api/test — validates `CdiDeviceRegistry` CDI behavior and startup discovery

---

### StateChangeEventPublisher

CDI helper for `@QuarkusTest`. Fires `StateChangeEvent` via `Event.fireAsync()` with auto-derived `changedCapabilities`.

```java
@ApplicationScoped
public class StateChangeEventPublisher {
    @Inject
    Event<StateChangeEvent> event;

    public CompletionStage<StateChangeEvent> publish(
            DeviceEntity before, DeviceEntity after, String providerId) {
        var sce = new StateChangeEvent(
            before, after,
            StateChangeEvent.deriveChangedCapabilities(before, after),
            Instant.now(), providerId);
        return event.fireAsync(sce);
    }
}
```

Returns `CompletionStage<StateChangeEvent>` — the return value of `fireAsync()`. Tests join on this to avoid racing between async event delivery and assertion:

```java
publisher.publish(lightOn, lightOff, "test").toCompletableFuture().join();
assertThat(handler.lastEvent()).isNotNull();
```

**Join guarantee:** The CDI specification guarantees that the `CompletionStage` returned by `fireAsync()` completes only after all `@ObservesAsync` handlers — including `CdiDeviceRegistry.onStateChange()` — have finished executing. After `.join()`, the registry reflects the updated device state. Fire-and-assert without join will race.

---

### Fixtures

Static factory methods producing pre-built devices for a standard home. Fixed IDs, fixed tenant, deterministic timestamps.

```java
public final class Fixtures {
    public static final String DEFAULT_TENANT = "default-tenant";
    public static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    public static SwitchDevice hallwaySwitch() { ... }
    public static LightDevice livingRoomLight() { ... }
    public static ThermostatDevice livingRoomThermostat() { ... }
    public static SensorDevice outdoorTemperature() { ... }
    public static PresenceSensor frontDoorPresence() { ... }
    public static PowerSensor solarPanel() { ... }
    public static LockDevice frontDoorLock() { ... }
    public static CoverDevice bedroomBlinds() { ... }
    public static MediaPlayerDevice livingRoomSpeaker() { ... }
    public static FanDevice bedroomFan() { ... }

    public static List<DeviceEntity> standardHome() {
        return List.of(hallwaySwitch(), livingRoomLight(),
            livingRoomThermostat(), outdoorTemperature(),
            frontDoorPresence(), solarPanel(), frontDoorLock(),
            bedroomBlinds(), livingRoomSpeaker(), bedroomFan());
    }
}
```

**Initial domain states — all 10 fixtures:**

| Fixture | Domain state at construction |
|---|---|
| `hallwaySwitch()` | `on=false` |
| `livingRoomLight()` | `on=false`, `brightness=null`, `colorTemp=null` |
| `livingRoomThermostat()` | `currentTemperature=21°C`, `targetTemperature=22°C`, `mode=HEAT` |
| `outdoorTemperature()` | `sensorType=TEMPERATURE`, `numericValue=15.0`, `unit="°C"`, `binaryValue=null` |
| `frontDoorPresence()` | `present=false`, `lastSeen=EPOCH` |
| `solarPanel()` | `power=0.0W`, `energy=0.0kWh` |
| `frontDoorLock()` | `locked=true` |
| `bedroomBlinds()` | `position=0` (closed), `moving=false` |
| `livingRoomSpeaker()` | `playing=false`, `volume=null` |
| `bedroomFan()` | `on=false`, `speed=null` |

All devices: `available=true`, `lastUpdated=EPOCH`, `tenancyId=DEFAULT_TENANT`.

**Device IDs:** `{type}-{location}-{n}` pattern — `switch-hallway-1`, `light-living-1`, `thermostat-living-1`, `sensor-outdoor-1`, `presence-front-1`, `power-solar-1`, `lock-front-1`, `cover-bedroom-1`, `media-living-1`, `fan-bedroom-1`.

**Design choices:**
- Methods, not constants — each call returns a fresh instance (no shared mutable state across tests).
- `standardHome()` returns all 10 types — one per device class. Device IDs are distinct across all 10.
- Tests needing variants use `toBuilder()`: `Fixtures.frontDoorLock().toBuilder().locked(false).build()`, `Fixtures.hallwaySwitch().toBuilder().available(false).build()`.
- YAML fixture loading deferred (file as GitHub issue — additive, Java factories are the primary mechanism).

---

## Module Structure

```
iot-testing/
  src/main/java/io/casehub/iot/testing/
    MockDeviceProvider.java
    MockDeviceRegistry.java
    StateChangeEventPublisher.java
    Fixtures.java
  src/test/java/io/casehub/iot/testing/
    MockDeviceProviderTest.java
    MockDeviceRegistryTest.java
    StateChangeEventPublisherTest.java    (@QuarkusTest)
    FixturesTest.java
```

**Dependencies — iot-testing/pom.xml requires three additions beyond the existing `casehub-iot-api` compile dep:**

1. **`quarkus-junit` — test scope (explicit, not transitive).** quarkus-junit is declared `test`-scoped in iot-api/pom.xml. Maven does not propagate test-scoped transitive dependencies. iot-testing's `StateChangeEventPublisherTest` requires `@QuarkusTest` and will not compile without it.

2. **`assertj-core` — test scope.** Required for `assertThat(...)` assertions in unit tests. Currently only declared in iot-api; not transitively available.

3. **Jandex Maven plugin — build plugin.** `StateChangeEventPublisher` is `@ApplicationScoped`. For Quarkus CDI to discover it when iot-testing is on a consumer's test classpath, the iot-testing JAR must contain a Jandex index (`META-INF/jandex.idx`). Without it, CDI won't scan iot-testing's classes and `StateChangeEventPublisher` will be invisible as a CDI bean — injection fails at test startup. (Garden: GE-20260525-d06282 — "testing module must be indexed in @QuarkusTest or beans are undiscovered".) Add to iot-testing/pom.xml build section:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.smallrye</groupId>
            <artifactId>jandex-maven-plugin</artifactId>
            <executions>
                <execution>
                    <id>make-index</id>
                    <goals><goal>jandex</goal></goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

**Consumer usage:**
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-iot-testing</artifactId>
    <scope>test</scope>
</dependency>
```

---

## Test Strategy

| What | Where | Type |
|---|---|---|
| `DeviceEntity.capabilities()` base — contains CAP_AVAILABLE | `iot-api/src/test/` | Unit |
| `capabilities()` on all 10 subclasses — correct keys and values | `iot-api/src/test/` | Unit |
| `toBuilder()` round-trip on all 10 subclasses — all fields preserved | `iot-api/src/test/` | Unit |
| `toBuilder()` modify-one-field — returns correct concrete type | `iot-api/src/test/` | Unit |
| `deriveChangedCapabilities()` — value change, null transitions, no change, `available` transition | `iot-api/src/test/` | Unit |
| `deriveChangedCapabilities()` — mismatched types throw `IllegalArgumentException` | `iot-api/src/test/` | Unit |
| `CdiDeviceRegistryTest.stateChangeUpdatesRegistry()` rewrite — uses `Event<StateChangeEvent>` + join | `iot-api/src/test/` | `@QuarkusTest` |
| MockDeviceProvider — add/remove/discover/dispatch recording | `iot-testing/src/test/` | Unit |
| MockDeviceRegistry — add/find/findByClass/findByTenancyId/clear | `iot-testing/src/test/` | Unit |
| StateChangeEventPublisher — fires CDI event, changedCapabilities correct, registry updated after join | `iot-testing/src/test/` | `@QuarkusTest` |
| Fixtures — all 10 produce valid devices, correct initial states, standardHome has 10 distinct IDs | `iot-testing/src/test/` | Unit |

---

## Deferred Work

- **YAML fixture loading** — additive; Java fixtures are the primary mechanism. YAML loader would parse a YAML device set into `List<DeviceEntity>` using the same builder API. File as GitHub issue.

---

## Platform Coherence Review

| Check | Result |
|---|---|
| Does this already exist? | No — no IoT test infrastructure exists elsewhere |
| Is this the right repo? | Yes — iot-testing is an iot-api module |
| Consolidation opportunity? | DeviceRegistryContractTest / SimpleDeviceRegistry deleted; MockDeviceRegistryTest subsumes the contract verification |
| Consistent with platform patterns? | Yes — module-tier-structure (iot-testing is pure Java + CDI annotations, no JPA); maven-submodule-folder-naming (#6 tracks prefix rename) |
| Platform-level doc update? | No — PLATFORM.md already references iot-testing |
| Vendor supplement extensibility? | `capabilities()` returns mutable map; supplements call `super.capabilities()` and add entries. `toBuilder()` on supplement types (C3/C4) copies supplement fields and accesses parent class fields via public accessors. |

---

## Consequential Changes to ARC42STORIES

C2 reverses three decisions documented in ARC42STORIES §9.4 Layer L5. ARC42STORIES must be updated at C2 close:

**C1 fix: Temperature.equals() must use compareTo()** — see §Temperature.equals() above. This is a C1 correctness fix triggered by C2's introduction of `deriveChangedCapabilities()`. Add to ARC42STORIES §9.4 L1 gotchas at C2 close.

| ARC42STORIES entry | Original | Revised |
|---|---|---|
| L5 component name | `TestDeviceRegistry` pre-populated from YAML | `MockDeviceRegistry` populated programmatically via `addDevice()` |
| L5 key files | `TestDeviceRegistry.java`, `home-standard-fixtures.yaml` | `MockDeviceRegistry.java`, `Fixtures.java` |
| L5 architectural decision | "Why YAML rather than Java builders: readable by non-developers" | Java factories — type-safe, IDE-navigable, composable. This is a developer-only foundation library; non-developer accessibility is not a requirement. YAML loading remains deferred. |
| L5 "What it adds" | `MockDeviceProvider.fireStateChange(before, after)` | Removed — `StateChangeEventPublisher` owns CDI event firing. MockDeviceProvider is CDI-free. |
| L5 pattern-to-replicate step 1 | "manual state change firing (`fireStateChange`)" | Remove; replace with "CDI event firing delegated to StateChangeEventPublisher" |

ARC42STORIES §9.3 Chapter 2 entry should be updated at chapter close to reflect the above and stamp the "Completed" date.

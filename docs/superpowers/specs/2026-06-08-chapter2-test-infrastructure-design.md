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

### DeviceEntity.capabilities()

Abstract method on `DeviceEntity`. Each subclass returns a `Map<String, Object>` of its capability fields keyed by `CAP_*` constants.

```java
// DeviceEntity
public abstract Map<String, Object> capabilities();

// LightDevice
@Override
public Map<String, Object> capabilities() {
    Map<String, Object> caps = new LinkedHashMap<>();
    caps.put(CAP_ON, on);
    caps.put(CAP_BRIGHTNESS, brightness);   // nullable — null means "not set"
    caps.put(CAP_COLOR_TEMP, colorTemp);    // nullable
    return caps;
}
```

**Rules:**
- Only domain-specific fields that can change independently — the things that appear in `changedCapabilities`.
- Base class fields (deviceId, label, available, lastUpdated, tenancyId) are identity/metadata, not capabilities.
- Null-valued entries are included. A null brightness is semantically distinct from "this device has no brightness capability." The key's presence means the capability exists; the value is its current state.
- Return a fresh mutable map (caller may need to merge with supplement capabilities in C3/C4).

### DeviceEntity.toBuilder()

Abstract method returning a pre-populated builder with all current field values copied in. The return type is the concrete subclass's builder.

```java
// DeviceEntity
public abstract Builder<?, ?> toBuilder();

// LightDevice
@Override
public LightDevice.Builder toBuilder() {
    return new Builder()
        .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
        .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId())
        .on(on).brightness(brightness).colorTemp(colorTemp);
}
```

Each of the 10 subclasses implements this, copying base fields via accessors and subclass fields directly. The AbstractBuilder subclasses (LightDevice, ThermostatDevice, LockDevice, CoverDevice) return their concrete `Builder`, not the abstract builder — so `light.toBuilder().brightness(100).build()` returns a `LightDevice`.

**Why:** State transitions on immutable value objects are the fundamental operation in IoT. Without toBuilder(), creating "the same device with one field changed" requires rebuilding from scratch. Providers (C3/C4) and consumers both need this.

### StateChangeEvent.deriveChangedCapabilities()

Static utility on `StateChangeEvent`:

```java
public static Set<String> deriveChangedCapabilities(
        DeviceEntity before, DeviceEntity after) {
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

Handles null→non-null, non-null→null, and value changes. Keys present in `before` but absent in `after` are not flagged (capability removal is a type change, not a state change).

**Precondition:** `before` and `after` must be the same `DeviceEntity` subclass. If `before.getClass() != after.getClass()`, throw `IllegalArgumentException`. Comparing a LightDevice before with a SwitchDevice after is a caller error — the capability maps have different keys and the diff would be meaningless.

### StateChangeEvent.of() convenience factory

```java
public static StateChangeEvent of(
        DeviceEntity before, DeviceEntity after, String providerId) {
    return new StateChangeEvent(
        before, after,
        deriveChangedCapabilities(before, after),
        Instant.now(), providerId);
}
```

---

## Test Infrastructure (iot-testing)

### MockDeviceProvider

Implements `DeviceProvider`. Plain Java POJO — not a CDI bean by default. Tests can register it as a CDI bean via `@Alternative` in `@QuarkusTest` when needed.

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

No event firing. MockDeviceProvider is a programmatic stub — devices go in, commands are recorded, status is controllable.

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
- `refresh()` — no-op

**When to use which registry:**
- **Unit tests:** instantiate `MockDeviceRegistry`, populate with fixtures, pass to class under test
- **`@QuarkusTest`:** `CdiDeviceRegistry` discovers `MockDeviceProvider` automatically — use MockDeviceProvider directly, no MockDeviceRegistry needed

### StateChangeEventPublisher

CDI helper for `@QuarkusTest`. Fires `StateChangeEvent` via `Event.fireAsync()` with auto-derived changedCapabilities.

```java
@ApplicationScoped
public class StateChangeEventPublisher {
    @Inject
    Event<StateChangeEvent> event;

    public CompletionStage<StateChangeEvent> publish(
            DeviceEntity before, DeviceEntity after, String providerId) {
        var sce = StateChangeEvent.of(before, after, providerId);
        return event.fireAsync(sce);
    }
}
```

Returns `CompletionStage<StateChangeEvent>` — the return value of `fireAsync()`. Tests join on this to avoid racing between async event delivery and assertion:

```java
publisher.publish(lightOn, lightOff, "test").toCompletableFuture().join();
assertThat(handler.lastEvent()).isNotNull();
```

### Fixtures

Static factory methods producing pre-built devices for a standard home. Fixed IDs, fixed tenant, deterministic timestamp.

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

**Design choices:**
- Methods, not constants — each call returns a fresh instance (no shared mutable state across tests).
- Device IDs: `{type}-{location}-{n}` pattern (e.g. `light-living-1`, `lock-front-1`).
- All devices start `available(true)`. Tests needing unavailable devices: `fixture.toBuilder().available(false).build()`.
- `standardHome()` returns all 10 types — one per device class.
- YAML fixture loading deferred (see Deferred Work below).

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

**Dependencies:** One compile dependency — `casehub-iot-api`. CDI available transitively via iot-api's `quarkus-arc` dependency.

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
| `capabilities()` on all 10 subclasses | `iot-api/src/test/` | Unit |
| `toBuilder()` round-trip on all 10 subclasses | `iot-api/src/test/` | Unit |
| `deriveChangedCapabilities()` — value change, null transitions, no change | `iot-api/src/test/` | Unit |
| `StateChangeEvent.of()` factory | `iot-api/src/test/` | Unit |
| MockDeviceProvider — add/remove/discover/dispatch recording | `iot-testing/src/test/` | Unit |
| MockDeviceRegistry — add/find/clear | `iot-testing/src/test/` | Unit |
| StateChangeEventPublisher — fires CDI event, changedCapabilities correct | `iot-testing/src/test/` | `@QuarkusTest` |
| Fixtures — all 10 produce valid devices, standardHome has 10 distinct IDs | `iot-testing/src/test/` | Unit |

---

## Deferred Work

- **YAML fixture loading** — additive; Java fixtures are the primary mechanism. YAML loader would parse a YAML device set into `List<DeviceEntity>` using the same builder API. File as GitHub issue.

---

## Platform Coherence Review

| Check | Result |
|---|---|
| Does this already exist? | No — no IoT test infrastructure exists elsewhere |
| Is this the right repo? | Yes — iot-testing is an iot-api module |
| Consolidation opportunity? | DeviceRegistryContractTest's inline SimpleDeviceRegistry is subsumed by MockDeviceRegistry |
| Consistent with platform patterns? | Yes — module-tier-structure (iot-testing is pure Java + CDI annotations, no JPA); maven-submodule-folder-naming (#6 tracks prefix rename) |
| Platform-level doc update? | No — PLATFORM.md already references iot-testing |
| Vendor supplement extensibility? | capabilities() returns mutable map — supplements add entries. toBuilder() on supplement types (C3/C4) copies supplement fields. |

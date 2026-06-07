# Chapter 1 — Core API Design Spec

**Date:** 2026-06-07
**Issue:** casehubio/casehub-iot#1
**Module:** `iot-api` (`casehub-iot-api`)
**Foundation spec:** `docs/superpowers/specs/2026-06-05-iot-foundation-design.md`

---

## Overview

Implements the full `casehub-iot-api` public API surface: typed device class hierarchy (10 subtypes aligned with Matter Device Type Library), DeviceProvider and DeviceRegistry SPIs, StateChangeEvent and DeviceCommand records, and CdiDeviceRegistry default implementation. Everything a consumer or provider needs to build against the IoT abstraction layer.

**Semver from first release.** Community automations in casehub-life depend on this module.

---

## Module Structure

Single module: `iot-api/` with artifactId `casehub-iot-api`.

**CDI dependency:** Uses `@DefaultBean` from Quarkus Arc (`io.quarkus.arc`) and annotation types from `jakarta.inject-api` / `jakarta.enterprise.cdi-api`. This is an intentional dependency — every consumer of casehub-iot is a Quarkus application. The updated `module-tier-structure` protocol permits CDI annotation JARs in Tier 1 api modules since they are inert without a CDI container and force no infrastructure configuration. Quarkus Arc is a stronger dep than annotations alone, but `@DefaultBean` is the platform-standard pattern for displaceable defaults and no non-Quarkus consumer exists.

No JPA, no Mutiny, no datasource, no Flyway.

---

## Package Structure

| Package | Contents |
|---|---|
| `io.casehub.iot.api` | DeviceEntity, typed subtypes, DeviceClass, Temperature, ThermostatMode, StateChangeEvent, DeviceCommand, CommandResult, ProviderStatus, ProviderStatusEvent |
| `io.casehub.iot.api.spi` | DeviceProvider, DeviceRegistry (consumer-facing SPI interfaces) |
| `io.casehub.iot.spi` | CdiDeviceRegistry (CDI-annotated default implementation) |

SPIs in `api.spi` per `consumer-spi-placement.md`. CDI-annotated default in `io.casehub.iot.spi` — consumers who only need the interface don't see the implementation.

---

## DeviceEntity Hierarchy

Abstract base class with concrete immutable subtypes. Immutability via final fields — no setters. Providers construct via builders (self-referential generics for inheritance). Vendor supplements extend subtypes (Chapters 3/4).

### DeviceEntity (abstract base)

```java
public abstract class DeviceEntity {
    private final String deviceId;
    private final DeviceClass deviceClass;
    private final String label;
    private final boolean available;
    private final Instant lastUpdated;
    private final String tenancyId;

    protected DeviceEntity(Builder<?, ?> builder)

    public String deviceId()
    public DeviceClass deviceClass()
    public String label()
    public boolean available()
    public Instant lastUpdated()
    public String tenancyId()

    protected abstract static class Builder<T extends DeviceEntity, B extends Builder<T, B>> {
        public B deviceId(String deviceId)
        public B deviceClass(DeviceClass deviceClass)
        public B label(String label)
        public B available(boolean available)   // defaults to true — devices are available unless stated otherwise
        public B lastUpdated(Instant lastUpdated)
        public B tenancyId(String tenancyId)
        protected abstract B self();
        public abstract T build();
    }
}
```

`equals`/`hashCode` on `deviceId` — identity is the stable device identifier. `toString` includes deviceClass and label for diagnostics.

### DeviceClass enum

```java
public enum DeviceClass {
    SWITCH, LIGHT, THERMOSTAT, SENSOR, PRESENCE_SENSOR,
    POWER_SENSOR, LOCK, COVER, MEDIA_PLAYER, FAN
}
```

Aligned with Matter Device Type Library vocabulary.

### Typed subtypes

Each is a concrete class extending DeviceEntity with its own builder. Builder chains to parent builder. Optional fields use nullable builder parameters with `Optional<>` return types on accessors.

Each subtype defines `CAP_*` string constants for its capability names — used in `StateChangeEvent.changedCapabilities` and by consumers for pattern matching.

| Type | Additional fields | Constants | Notes |
|---|---|---|---|
| `SwitchDevice` | `boolean on` | `CAP_ON` | `isOn()` |
| `LightDevice` | `boolean on`, `@Nullable Integer brightness`, `@Nullable Integer colorTemp` | `CAP_ON`, `CAP_BRIGHTNESS`, `CAP_COLOR_TEMP` | brightness 0–255, colorTemp in mireds |
| `ThermostatDevice` | `Temperature currentTemperature`, `Temperature targetTemperature`, `ThermostatMode mode` | `CAP_CURRENT_TEMPERATURE`, `CAP_TARGET_TEMPERATURE`, `CAP_MODE` | All required |
| `SensorDevice` | `SensorType sensorType`, `@Nullable BigDecimal numericValue`, `@Nullable String unit`, `@Nullable Boolean binaryValue` | `CAP_NUMERIC_VALUE`, `CAP_UNIT`, `CAP_BINARY_VALUE` | Sub-classified by SensorType |
| `PresenceSensor` | `boolean present`, `Instant lastSeen` | `CAP_PRESENT`, `CAP_LAST_SEEN` | Drives automation triggers |
| `PowerSensor` | `BigDecimal power`, `BigDecimal energy` | `CAP_POWER`, `CAP_ENERGY` | power in watts, energy in kWh |
| `LockDevice` | `boolean locked` | `CAP_LOCKED` | `isLocked()` |
| `CoverDevice` | `int position`, `boolean moving` | `CAP_POSITION`, `CAP_MOVING` | position 0–100%, `isMoving()` |
| `MediaPlayerDevice` | `boolean playing`, `@Nullable Integer volume` | `CAP_PLAYING`, `CAP_VOLUME` | `isPlaying()`, volume 0–100 |
| `FanDevice` | `boolean on`, `@Nullable Integer speed` | `CAP_ON`, `CAP_SPEED` | speed 0–100% |

**Capability constant convention:** Named after the accessor method without parens. `CAP_ON = "isOn"`, `CAP_BRIGHTNESS = "brightness"`, `CAP_CURRENT_TEMPERATURE = "currentTemperature"`. These constants are used by providers when populating `changedCapabilities` and by consumers when pattern-matching on `StateChangeEvent`.

**Constant ownership:** Each subtype defines only the constants for its own capabilities. `SwitchDevice.CAP_ON`, `LightDevice.CAP_ON`, and `FanDevice.CAP_ON` are separate `static final` fields on each class — not inherited from DeviceEntity. Values are identical (`"isOn"`), but the type context makes consumer code self-documenting.

**Builder usage:**

```java
LightDevice.builder()
    .deviceId("light.living_room")
    .deviceClass(DeviceClass.LIGHT)
    .label("Living Room Light")
    .available(true)
    .lastUpdated(Instant.now())
    .tenancyId("tenant-1")
    .on(true)
    .brightness(128)
    .colorTemp(4000)
    .build()
```

### SensorType enum

```java
public enum SensorType {
    TEMPERATURE, HUMIDITY, MOTION, DOOR_WINDOW, CO2, LUX, GENERIC
}
```

Sub-classifies `SensorDevice` — consumers filter by sensor type without parsing unit strings. `GENERIC` handles the long tail. Extensible via minor version bumps (new enum values are backwards-compatible).

### Value types

**Temperature:**
```java
public record Temperature(BigDecimal value, TemperatureUnit unit) {
    public enum TemperatureUnit { CELSIUS, FAHRENHEIT }
    public Temperature toCelsius()
    public Temperature toFahrenheit()
}
```

**ThermostatMode:**
```java
public enum ThermostatMode { HEAT, COOL, AUTO, OFF, FAN_ONLY }
```

---

## SPI Contracts

Both in `io.casehub.iot.api.spi`. All methods blocking — pure Java, no Mutiny.

### DeviceProvider

```java
public interface DeviceProvider {
    String providerId();
    List<DeviceEntity> discover();
    CommandResult dispatch(DeviceCommand command);
    ProviderStatus status();
}
```

**`discover()` contract:** Called at startup by CdiDeviceRegistry and on `refresh()`. Blocking — runs in startup context (`@Observes StartupEvent`). On failure (platform unreachable, auth error, timeout), providers return an empty list rather than throwing — partial discovery is better than a startup crash. Providers log the error and set status to `DISCONNECTED`.

**`dispatch()` contract:** Sends a command to the platform. Blocking — called from application `@Blocking` handlers. Error handling: providers catch transport errors and return `CommandResult.FAILED`; catch timeouts and return `CommandResult.TIMEOUT`. Only bugs (NPE, illegal state) propagate as exceptions. Returns `CommandResult.FAILED` immediately when status is `DISCONNECTED`.

`status()` is synchronous — reads cached connection state.

**Reactive variant deferred:** dual-variant `ReactiveDeviceProvider` with `Uni<>` returns added when a reactive consumer exists. YAGNI now — all consumers are `@Blocking`.

### DeviceRegistry

```java
public interface DeviceRegistry {
    Optional<DeviceEntity> findById(String deviceId);
    <T extends DeviceEntity> List<T> findByClass(Class<T> deviceClass);
    List<DeviceEntity> findByTenancyId(String tenancyId);
    List<DeviceEntity> findAll();
    void refresh();
}
```

`findByClass` uses `instanceof` filtering — returns devices assignable to the given class, including vendor supplement subclasses (e.g. `findByClass(LightDevice.class)` returns `HomeAssistantLight` instances).

`findByTenancyId` filters the device map by tenancy. Every multi-tenant consumer (casehub-life, future property management, elder care) needs this — centralising it prevents N copies of the same filter.

`refresh()` re-calls `discover()` on all providers and rebuilds the device map.

---

## Event and Command Model

All in `io.casehub.iot.api`.

### StateChangeEvent

```java
public record StateChangeEvent(
    DeviceEntity before,        // nullable — null on first discovery of a new device
    DeviceEntity after,
    Set<String> changedCapabilities,
    Instant occurredAt,
    String providerId
) {
    public StateChangeEvent {
        Objects.requireNonNull(after, "after");
        changedCapabilities = Set.copyOf(changedCapabilities);
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(providerId, "providerId");
    }
}
```

`changedCapabilities` names the fields that changed, using the `CAP_*` constant values (accessor method names without parens). E.g. `{"isOn", "brightness"}` — not `{"on", "getBrightness"}`.

Populated by providers: HA diffs old/new state maps; OpenHAB derives from the item event. Providers use the `CAP_*` constants from the device type to populate the set.

Fired via CDI `Event<StateChangeEvent>.fireAsync()` by provider modules. Consumers use `@ObservesAsync`. iot-api does not fire events — it only defines the record.

### DeviceCommand

```java
public record DeviceCommand(
    String targetDeviceId,
    String action,
    Map<String, Object> parameters,
    String dispatchedBy,
    String correlationId
) {
    public DeviceCommand {
        Objects.requireNonNull(targetDeviceId, "targetDeviceId");
        Objects.requireNonNull(action, "action");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
```

**Parameter constraint:** `parameters` values must be JSON-serialisable primitives (`String`, `Number`, `Boolean`, `null`). No nested objects or collections. `DeviceCommand` crosses the bridge WebSocket in Chapter 5 — non-serialisable parameters would silently break.

**Action constants and factories:**

```java
public record DeviceCommand(...) {
    public static final String ACTION_TURN_ON = "turn_on";
    public static final String ACTION_TURN_OFF = "turn_off";
    public static final String ACTION_SET_TEMPERATURE = "set_temperature";
    public static final String ACTION_LOCK = "lock";
    public static final String ACTION_UNLOCK = "unlock";
    public static final String ACTION_SET_POSITION = "set_position";
    public static final String ACTION_SET_VOLUME = "set_volume";

    public static DeviceCommand turnOn(String targetDeviceId, Map<String, Object> parameters) { ... }
    public static DeviceCommand turnOff(String targetDeviceId) { ... }
    public static DeviceCommand setTemperature(String targetDeviceId, Temperature target) { ... }
    public static DeviceCommand lock(String targetDeviceId) { ... }
    public static DeviceCommand unlock(String targetDeviceId) { ... }
    public static DeviceCommand setPosition(String targetDeviceId, int position) { ... }
    public static DeviceCommand setVolume(String targetDeviceId, int volume) { ... }
}
```

Static factories enforce the contract for common actions (correct action string, typed parameters). The generic record constructor stays for vendor-specific or future commands. Factories accept `dispatchedBy` and `correlationId` as chained methods or overloads — TDD will determine the ergonomic choice.

### Enums and events

```java
public enum CommandResult { SENT, FAILED, TIMEOUT }

public enum ProviderStatus { CONNECTED, CONNECTING, DISCONNECTED }

public record ProviderStatusEvent(
    String providerId,
    ProviderStatus previousStatus,
    ProviderStatus currentStatus
) {}
```

---

## CDI Wiring

In `io.casehub.iot.spi`.

### CdiDeviceRegistry

```java
@ApplicationScoped
@DefaultBean
public class CdiDeviceRegistry implements DeviceRegistry {
    @Any Instance<DeviceProvider> providers;
    private volatile Map<String, DeviceEntity> devices = Map.of();

    void onStartup(@Observes StartupEvent event) {
        refresh();
    }

    @Override
    public synchronized void refresh() {
        Map<String, DeviceEntity> next = new HashMap<>();
        for (DeviceProvider p : providers) {
            p.discover().forEach(d -> next.put(d.deviceId(), d));
        }
        devices = Map.copyOf(next);
    }

    void onStateChange(@ObservesAsync StateChangeEvent event) {
        updateDevice(event.after());
    }

    private synchronized void updateDevice(DeviceEntity device) {
        Map<String, DeviceEntity> current = devices;
        Map<String, DeviceEntity> next = new HashMap<>(current);
        next.put(device.deviceId(), device);
        devices = Map.copyOf(next);
    }
}
```

**Atomic swap:** `devices` is a `volatile` reference to an unmodifiable `Map.copyOf()` snapshot. Readers never see partial state. Writers build a new map and swap the reference.

**State freshness:** `@ObservesAsync StateChangeEvent` keeps the device map current between full discoveries. Without this, `findById()` returns stale state from the last `discover()` — the §8 anti-pattern "stale device from registry after state change."

**All writes synchronized on the same monitor:** Both `refresh()` and `updateDevice()` are `synchronized` — prevents two concurrent event handlers from losing each other's updates, and prevents event/refresh interleaving from reverting to stale state. Contention is negligible: refresh is rare (startup + manual), IoT events are human-scale (tens/second), critical section is a HashMap copy + one put + `Map.copyOf()` — microseconds.

`@DefaultBean` — displaced by any custom registry a consumer provides. Handles the zero-provider case naturally: `@Any Instance<DeviceProvider>` resolves to an empty iterable, device map stays empty, all queries return empty results.

`NoOpDeviceRegistry` is unnecessary — CdiDeviceRegistry with no providers IS the no-op case.

Uses `@Observes StartupEvent` instead of `@PostConstruct` to ensure all provider beans are fully initialized before discovery.

---

## Design Decisions

### Why blocking SPIs, not Uni

`discover()` runs at startup — blocking is fine. `dispatch()` is a single REST call from `@Blocking` context. Mutiny `Uni<>` would force a Quarkus runtime dependency on iot-api beyond the CDI annotation pragmatism (Mutiny is a runtime library with transitive deps, not just annotations). Platform precedent: `WorkItemStore`, `SlaBreachPolicy`, `LedgerEntryRepository` — all blocking in their api modules.

### Why abstract DeviceEntity + concrete subtypes

Records are `final` — can't extend for vendor supplements (`HomeAssistantLight extends LightDevice`). Sealed interfaces lose shared-state constructor chain. Abstract base + concrete subtypes: providers construct via builders, supplements extend, `instanceof` pattern matching works naturally.

### Why builders with self-referential generics

9 positional constructor parameters with mixed `boolean`/`int` types are unreadable and error-prone. `new LightDevice("id", DeviceClass.LIGHT, "label", true, now, "t1", true, 128, 4000)` — which `true` is `available`? Which is `on`? Builders make every parameter named. The self-referential generic pattern (`Builder<T, B>`) preserves builder chaining across inheritance — `LightDevice.Builder` inherits `deviceId()`, `label()`, etc. from `DeviceEntity.Builder`.

### Why CdiDeviceRegistry in iot-api (not a separate runtime module)

`@DefaultBean` (Quarkus Arc) and CDI annotations are acceptable since every consumer is a Quarkus application. A separate runtime module adds structural complexity for a purity benefit no real consumer needs. Acknowledged honestly: this IS a Quarkus dependency, not just annotations.

### Why @Observes StartupEvent, not @PostConstruct

CDI bean construction order is not guaranteed. `@PostConstruct` on CdiDeviceRegistry may run before provider beans are fully initialized (especially if providers have their own `@PostConstruct` that establishes connections). `StartupEvent` fires after all beans are constructed.

### Why no NoOpDeviceRegistry

CdiDeviceRegistry handles the zero-provider case: `@Any Instance<DeviceProvider>` resolves to an empty iterable, device map stays empty, all queries return empty results. A separate NoOpDeviceRegistry would create CDI ambiguity (`AmbiguousResolutionException` — two `@DefaultBean` implementations of the same type).

### Why SensorType enum

PresenceSensor and PowerSensor were promoted to first-class types because they drive automation triggers and energy optimization. The remaining sensor types (temperature, humidity, CO2, lux, door/window) share a generic structure (`numericValue`/`unit`/`binaryValue`) but consumers need to distinguish them without parsing provider-dependent unit strings. `SensorType` enum sub-classifies sensors cheaply. `GENERIC` handles the long tail.

### Device ID uniqueness contract

Providers guarantee globally unique device IDs — no composite key (`providerId + platformId`). The device map is keyed by `deviceId` alone. If two providers discover the same physical device (unlikely — HA and OpenHAB don't run simultaneously in practice), the second discovery overwrites the first. Providers are responsible for stable, deterministic ID generation.

### Multi-capability devices

A physical device with multiple capabilities (e.g. a smart plug with switch + power metering) is modeled as multiple `DeviceEntity` instances — one `SwitchDevice` and one `PowerSensor` — sharing the same physical device identity through a naming convention (e.g. `"plug.kitchen"` and `"plug.kitchen.power"`). This matches how both HA and OpenHAB expose multi-capability devices (multiple entities/items per physical device).

### Registry event observation

CdiDeviceRegistry observes `StateChangeEvent` to keep the device map fresh. This belongs in the registry — not deferred to Chapter 3/4 — because stale reads are a correctness issue, not a feature issue. The registry IS the canonical device state; if it diverges from events, consumers get wrong answers.

---

## Alternatives Explored and Rejected

**Capability-based composition** (single DeviceEntity with pluggable capability records instead of a type hierarchy) — aligned with Matter's cluster model, solves multi-capability devices naturally. Rejected: records are final (vendor supplements can't extend capability records either), trades type-safe `device.brightness()` for verbose `device.getCapability(BrightnessCapability.class).map(...)`, and 10 device types across 2-3 inheritance levels is well within the range where hierarchy works cleanly.

**State separated from identity** (DeviceIdentity + DeviceState as separate concepts) — clean separation of stable identity vs volatile state. Rejected: doubles the lookup surface for consumers, and the same "records are final" problem blocks vendor supplement extension on the state side.

Both alternatives optimize for a scale or composability that isn't justified at 10 device types. If the hierarchy grows past 20-30 types or multi-capability devices become common, revisit.

---

## Out of Scope

- Vendor supplement types — Chapters 3/4 (#3, #4)
- CDI event firing — provider module concern (providers own the CDI container)
- Connection lifecycle — provider module concern
- Test infrastructure — Chapter 2 (#2)
- Bridge runtime — Chapter 5 (#5)
- Submodule folder renaming — #6

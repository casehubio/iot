# casehub-iot — Foundation Design Spec
**Date:** 2026-06-05  
**Research:** `casehubio/parent` — `docs/superpowers/research/2026-06-05-home-automation-research.md`  
**Application spec:** `casehubio/life` — `docs/superpowers/specs/2026-06-05-life-layer9-home-automation.md`

---

## Overview

`casehub-iot` is a foundation repo — peer to `casehub-connectors` — providing a typed device abstraction layer over IoT platforms. It is consumed by application-tier repos (initially `casehub-life`) and produces no application logic of its own.

**Design principles:**
- Common interface first: the typed device class hierarchy in `iot-api` carries everything that has a cross-vendor mapping. Vendor supplement subclasses exist only for genuinely unmappable fields.
- Device class vocabulary aligned with the Matter Device Type Library.
- `casehub-iot-api` is a public API surface — community automations in casehub-life depend on it. Semver discipline from day one.

---

## Module Structure

```
casehub-iot
  iot-api/              Core SPIs and typed device class hierarchy (public API)
  iot-homeassistant/    Home Assistant provider + HA supplement types
  iot-openhab/          OpenHAB provider + OpenHAB supplement types
  iot-testing/          MockDeviceProvider, fixture devices, TestStateChangePublisher
  iot-bridge/           Lightweight bridge runtime for cloud/hybrid deployment
```

GroupId: `io.casehub`. Publishes before `casehub-life` in the build order.

---

## Core Abstractions (`iot-api`)

### DeviceEntity hierarchy

Common base — all fields mappable from both HA and OpenHAB:

```java
public abstract class DeviceEntity {
    String deviceId();        // stable cross-platform identifier
    DeviceClass deviceClass();
    String label();
    boolean available();      // ONLINE vs OFFLINE / UNAVAILABLE
    Instant lastUpdated();
    String tenancyId();
}
```

Common device class subtypes — vocabulary aligned with Matter Device Type Library:

```java
SwitchDevice
    isOn(): boolean

LightDevice
    isOn(): boolean
    brightness(): Optional<Integer>   // 0–255
    colorTemp(): Optional<Integer>    // mireds

ThermostatDevice
    currentTemperature(): Temperature
    targetTemperature(): Temperature
    mode(): ThermostatMode            // HEAT / COOL / AUTO / OFF / FAN_ONLY

SensorDevice
    numericValue(): Optional<BigDecimal>
    unit(): Optional<String>
    binaryValue(): Optional<Boolean>

PresenceSensor                        // first-class: drives most automation triggers
    isPresent(): boolean
    lastSeen(): Instant

PowerSensor                           // first-class: drives OptaPlanner energy optimization
    power(): BigDecimal               // watts
    energy(): BigDecimal              // kWh

LockDevice
    isLocked(): boolean

CoverDevice
    position(): int                   // 0–100%
    isMoving(): boolean

MediaPlayerDevice
    isPlaying(): boolean
    volume(): Optional<Integer>

FanDevice
    isOn(): boolean
    speed(): Optional<Integer>        // 0–100%
```

### Vendor supplement types

Extend common types **only** for fields with no cross-vendor equivalent:

```java
// iot-homeassistant
HomeAssistantThermostat extends ThermostatDevice
    presetMode(): Optional<String>
    swingMode(): Optional<String>
    hvacAction(): Optional<String>    // "heating", "cooling", "idle"

HomeAssistantLight extends LightDevice
    rgbColor(): Optional<int[]>       // [r, g, b]
    effect(): Optional<String>
    supportedColorModes(): Set<String>

HomeAssistantLock extends LockDevice
    changedBy(): Optional<String>
    codeSlot(): Optional<Integer>

// iot-openhab
OpenHABThermostatItem extends ThermostatDevice
    heatingDemand(): Optional<BigDecimal>
    coolingDemand(): Optional<BigDecimal>

OpenHABRollershutter extends CoverDevice
    upDown(): UpDownType              // OpenHAB-specific type

OpenHABColorItem extends LightDevice
    hsb(): HSBType                    // OpenHAB-specific type
```

---

### StateChangeEvent

```java
public record StateChangeEvent(
    DeviceEntity before,
    DeviceEntity after,
    Set<String> changedCapabilities,  // e.g. {"targetTemperature"}
    Instant occurredAt,
    String providerId                 // "homeassistant" | "openhab"
) {}
```

Fired via CDI `Event<StateChangeEvent>.fireAsync()`. Consumers use `@ObservesAsync StateChangeEvent`.

**`changedCapabilities` population:**
- OpenHAB: populated directly from the item event — it already knows exactly which field changed
- HA: populated by diffing old/new entity attribute maps (HA events include previous state)

This preserves OpenHAB's field-level precision and makes HA produce the same richness. Workers pattern-match on `changedCapabilities` without caring which platform fired the event.

---

### DeviceCommand

```java
public record DeviceCommand(
    String targetDeviceId,
    String action,              // "turn_on" | "turn_off" | "set_temperature" |
                                // "lock" | "unlock" | "set_position" | "set_volume"
    Map<String, Object> parameters,
    String dispatchedBy,        // actorId from CurrentPrincipal
    String correlationId
) {}
```

---

### DeviceProvider SPI

```java
public interface DeviceProvider {
    String providerId();
    Uni<List<DeviceEntity>> discover();
    Uni<CommandResult> dispatch(DeviceCommand command);
    ProviderStatus status();
}
```

`CommandResult`: `SENT`, `FAILED`, `TIMEOUT`.  
`ProviderStatus`: `CONNECTED`, `CONNECTING`, `DISCONNECTED`.

Providers are `@ApplicationScoped` CDI beans, discovered via `@Any Instance<DeviceProvider>`.

---

### DeviceRegistry SPI

```java
public interface DeviceRegistry {
    Optional<DeviceEntity> findById(String deviceId);
    <T extends DeviceEntity> List<T> findByClass(Class<T> deviceClass);
    List<DeviceEntity> findAll();
    void refresh();
}
```

`CdiDeviceRegistry @DefaultBean` — discovers all `DeviceProvider` implementations, calls `discover()` at startup, maintains the in-memory device map.

`NoOpDeviceRegistry @DefaultBean` in `iot-api` — for test and tutorial deployments without a live provider.

---

## Provider Implementations

### Connection lifecycle (both providers)

- `@ApplicationScoped`, connects at `@PostConstruct`
- Exponential backoff reconnection on disconnect (base 5s, max 5min)
- Fires `ProviderStatusEvent` via CDI `Event.fireAsync()` on CONNECTED / CONNECTING / DISCONNECTED transitions
- Application layer observes `ProviderStatusEvent` to handle connectivity alerts

### Home Assistant provider (`iot-homeassistant`)

**Config:**
```properties
casehub.iot.homeassistant.url=http://homeassistant.local:8123
casehub.iot.homeassistant.token=<long-lived-access-token>
```

**Discovery:** `GET /api/states` — maps HA domain + `device_class` to the common type hierarchy.

**State subscription:** WebSocket API (`/api/websocket`). Subscribes to `state_changed` events. Each event carries `old_state` and `new_state` — diff produces `changedCapabilities`. HA-specific fields populate the supplement subtype.

**Command dispatch:** `POST /api/services/{domain}/{service}` with `entity_id` and service data mapped from `DeviceCommand.parameters`.

---

### OpenHAB provider (`iot-openhab`)

**Config:**
```properties
casehub.iot.openhab.url=http://openhab.local:8080
casehub.iot.openhab.token=<api-token>
# or Basic Auth:
casehub.iot.openhab.username=admin
casehub.iot.openhab.password=<password>
```

**Prerequisite:** Semantic model must be configured — Equipment items (Groups tagged as Equipment) with member Point items. Hard prerequisite; document prominently. Thing-scoped discovery fallback is deferred.

**Discovery:** `GET /rest/items?tags=Equipment&recursive=true` — returns Equipment Groups with all member item states in one call. Maps Equipment semantic tag + member item types to the common device class hierarchy.

**State subscription:** SSE event stream (`GET /rest/events?topics=openhab/items/*/statechanged`). Each `ItemStateChangedEvent` carries item name, old value, new value. Provider resolves the owning Equipment from the discovery cache, updates the device state cache, and fires `StateChangeEvent` for the assembled `DeviceEntity`.

**State cache:** `Map<String, DeviceEntity>` keyed by Equipment Group name. Item events are internal to the provider. Multiple item events for the same Equipment within a 50ms window are coalesced into one `StateChangeEvent`.

**Command dispatch:** `POST /rest/items/{itemName}` with value mapped from `DeviceCommand.parameters`. Provider resolves the target item from the Equipment's member items using semantic Point/Property tags relevant to the action.

---

## Deployment Modes

### Mode 1 — Local

Provider connects directly to HA/OpenHAB on the same machine or local network. No internet dependency. All processing on-premises.

### Mode 2 — Bridge (SaaS)

`iot-bridge` Quarkus app runs locally. Connects to HA/OpenHAB locally. Forwards `StateChangeEvent`s to cloud CaseHub via WebSocket. Relays `DeviceCommand`s back.

```properties
casehub.iot.bridge.cloud-endpoint=wss://casehub.io/bridge
casehub.iot.bridge.tenant-id=<tenancy-id>
casehub.iot.bridge.token=<bridge-auth-token>
```

`StateChangeEvent` is already a clean serializable type — natural wire protocol between bridge and cloud.

### Mode 3 — Hybrid

Bridge runs Drools locally for latency-sensitive reactions. `StateChangeEvent`s also forwarded to cloud for orchestration, optimization, HITL, ledger, and memory.

```properties
casehub.iot.bridge.local-automations=security-alert,presence-lights
casehub.iot.bridge.cloud-automations=energy-optimization,morning-routine
```

---

## Testing (`iot-testing`)

```java
MockDeviceProvider implements DeviceProvider
    — programmatic device registration, manual StateChangeEvent firing

TestDeviceRegistry implements DeviceRegistry
    — pre-populated fixture device sets (home-standard-fixtures.yaml)

StateChangeEventPublisher
    — test helper: publish(DeviceEntity before, DeviceEntity after)
      derives changedCapabilities automatically, fires via CDI Event.fireAsync()
```

Add test scope to activate:

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-iot-testing</artifactId>
    <scope>test</scope>
</dependency>
```

---

## Key Constraints

- **`casehub-iot-api` is a public API.** Community automations in casehub-life depend on it. Semver from first release — no breaking changes without a major version bump.
- **OpenHAB semantic model is a hard prerequisite.** Document prominently. Thing-scoped discovery fallback is deferred.
- **`iot-testing` is test-scope only** — never compile or runtime dependency.
- **Authorization is the application's responsibility.** `iot-api` itself is authorization-agnostic; casehub-life enforces household permission checks before dispatch.

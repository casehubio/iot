# casehub-iot -- Consumer Guide

> Typed IoT device abstraction layer for the CaseHub ecosystem -- Matter-aligned device classes, reactive discovery, command dispatch, and CDI state change events.

**GitHub:** [casehubio/iot](https://github.com/casehubio/iot)
**Tier:** Foundation (consumed by application-tier repos)
**Version:** 0.2-SNAPSHOT
**GroupId:** `io.casehub`

---

## Purpose

Provides a unified device model regardless of the underlying home automation platform. Application repos consume the `api` module and receive typed device entities, state change events, and command dispatch -- without coupling to Home Assistant, OpenHAB, or any specific provider.

`api` is a **public API surface -- semver discipline applies from first release.** Community automations in casehub-life and downstream depend on it.

---

## Module Structure

Consumer-relevant modules -- what to depend on and why:

| Module | Artifact | When to use |
|--------|----------|-------------|
| `api` | `casehub-iot-api` | Always. Core SPIs, device class hierarchy, `StateChangeEvent`, `DeviceCommand`, `CommandResult`, `IoTCloudEventAdapter`, `IoTCommandAuditEvent`, enums. |
| `bridge-server` | `casehub-iot-bridge-server` | Cloud apps consuming remote (bridged) devices. `BridgeDeviceProvider implements DeviceProvider` -- remote devices look local. |
| `mcp` | `casehub-iot-mcp` | LLM agent device access. Add with `quarkus-mcp-server-http` for `iot_get_devices`, `iot_get_state`, `iot_send_command`, `iot_get_history` tools. |
| `testing` | `casehub-iot-testing` | Test scope only. `MockDeviceProvider`, `MockDeviceRegistry`, fixture devices (Java + YAML), `StateChangeEventPublisher`. |

---

## Device Class Hierarchy

`DeviceEntity` is the abstract root. All device types extend it with domain-specific fields and a typed `Builder`. Vocabulary aligned with the Matter Device Type Library.

Every `DeviceEntity` carries these base fields:

| Field | Type | Notes |
|-------|------|-------|
| `deviceId` | `String` | Stable cross-platform identifier |
| `deviceClass` | `DeviceClass` | Enum value for type discrimination |
| `label` | `String` | Human-readable name |
| `available` | `boolean` | ONLINE vs OFFLINE/UNAVAILABLE |
| `lastUpdated` | `Instant` | Last state change timestamp |
| `tenancyId` | `String` | Multi-tenant isolation key |
| `providerId` | `String` | Source provider (e.g. "homeassistant", "openhab") |
| `location` | `String` | Nullable -- populated by OpenHAB (from thing.location), null for HA |

The `capabilities()` method returns a `Map<String, Object>` used by `StateChangeEvent.deriveChangedCapabilities()` to compute change sets.

### Typed Device Subclasses

| Type | Class | DeviceClass Enum | Key Fields |
|------|-------|------------------|------------|
| Switch | `SwitchDevice` | `SWITCH` | `on` |
| Light | `LightDevice` | `LIGHT` | `on`, `brightness`, `colorTemp` |
| Thermostat | `ThermostatDevice` | `THERMOSTAT` | `currentTemperature`, `targetTemperature`, `mode` |
| Generic sensor | `SensorDevice` | `SENSOR` | `sensorType`, `numericValue`, `unit`, `binaryValue` |
| Presence sensor | `PresenceSensor` | `PRESENCE_SENSOR` | `present`, `lastSeen` |
| Power sensor | `PowerSensor` | `POWER_SENSOR` | `power`, `energy`, `voltage`, `current` |
| Lock | `LockDevice` | `LOCK` | `locked` |
| Cover | `CoverDevice` | `COVER` | `position`, `moving` |
| Media player | `MediaPlayerDevice` | `MEDIA_PLAYER` | `playing`, `volume` |
| Fan | `FanDevice` | `FAN` | `on`, `speed` |
| Camera | `CameraDevice` | `CAMERA` | `streaming` |

All 11 concrete types have `toBuilder()` methods and `CAP_*` capability constants for use with `changedCapabilities`.

### DeviceClass Enum

`io.casehub.iot.api.DeviceClass` -- 11 values: `SWITCH`, `LIGHT`, `THERMOSTAT`, `SENSOR`, `PRESENCE_SENSOR`, `POWER_SENSOR`, `LOCK`, `COVER`, `MEDIA_PLAYER`, `FAN`, `CAMERA`.

### Supporting Enums and Types

- `SensorType` -- sensor subtype classification
- `ThermostatMode` -- `HEAT`, `COOL`, `AUTO`, `OFF`, `FAN_ONLY`
- `ProviderStatus` -- `CONNECTED`, `CONNECTING`, `DISCONNECTED`
- `CommandResult` -- `SENT`, `FAILED`, `TIMEOUT`
- `Temperature` -- value type with `equals()` scale-insensitive comparison

---

## DeviceRegistry SPI (Consumer Contract)

`DeviceRegistry` (`io.casehub.iot.api.spi.DeviceRegistry`) is the primary consumer interface:

```java
public interface DeviceRegistry {
    Optional<DeviceEntity> findById(String deviceId);
    <T extends DeviceEntity> List<T> findByClass(Class<T> deviceClass);
    List<DeviceEntity> findByTenancyId(String tenancyId);
    List<DeviceEntity> findAll();
    void refresh();
    void refresh(String providerId);
}
```

`CdiDeviceRegistry` (`io.casehub.iot.spi.CdiDeviceRegistry`) is the `@ApplicationScoped @DefaultBean` implementation -- aggregates all `DeviceProvider` beans, calls `discover()` at startup on `@Observes StartupEvent`, and maintains an in-memory device map with synchronized updates.

The `refresh(String providerId)` method allows refreshing a single provider's device inventory without disrupting other providers.

All SPIs are **blocking** -- designed for virtual threads per ADR-0005. No `Uni<>` return types in the SPI layer.

---

## StateChangeEvent

CDI async event fired when a device's state changes. Java record in `io.casehub.iot.api`:

```java
public record StateChangeEvent(
    DeviceEntity before,
    DeviceEntity after,
    Set<String> changedCapabilities,
    Instant occurredAt,
    String providerId
) {}
```

Consumers observe with `@ObservesAsync StateChangeEvent`.

The `deriveChangedCapabilities(DeviceEntity before, DeviceEntity after)` static method compares `capabilities()` maps to produce the diff set. Used internally by providers and by `StateChangeEventPublisher` in the testing module.

**Important:** after receiving a `StateChangeEvent`, use `event.after()` for the current device state -- do not re-read from `DeviceRegistry`, as the registry update and event fire are not atomic.

---

## DeviceCommand

Immutable command record in `io.casehub.iot.api`:

```java
public record DeviceCommand(
    String targetDeviceId,
    String action,
    Map<String, Object> parameters,
    String dispatchedBy,
    String correlationId
) {}
```

### Action Constants

`ACTION_TURN_ON`, `ACTION_TURN_OFF`, `ACTION_SET_TEMPERATURE`, `ACTION_LOCK`, `ACTION_UNLOCK`, `ACTION_SET_POSITION`, `ACTION_SET_VOLUME`.

### Static Factory Methods

- `turnOn(targetDeviceId, parameters, dispatchedBy, correlationId)`
- `turnOff(targetDeviceId, dispatchedBy, correlationId)`
- `setTemperature(targetDeviceId, Temperature target, dispatchedBy, correlationId)`
- `lock(targetDeviceId, dispatchedBy, correlationId)`
- `unlock(targetDeviceId, dispatchedBy, correlationId)`
- `setPosition(targetDeviceId, int position, dispatchedBy, correlationId)`
- `setVolume(targetDeviceId, int volume, dispatchedBy, correlationId)`

---

## IoTCommandAuditEvent

CDI event record fired when a command is dispatched. Carries `deviceId`, `action`, `parameters`, `result`, `dispatchedBy`, `correlationId`, `providerId`, and `timestamp`. Consumers observe with `@ObservesAsync IoTCommandAuditEvent` for command audit trails.

---

## IoTCloudEventAdapter

`io.casehub.iot.api.IoTCloudEventAdapter` (`@ApplicationScoped`) observes every `StateChangeEvent` and re-publishes it as a CloudEvent via `Event.fireAsync()`.

- **Type:** `io.casehub.iot.state_change.{deviceClass}` (lowercase device class)
- **Source:** `/casehub-iot`
- **Subject:** `device/{deviceId}`
- **Data:** JSON-serialised `StateChangeEvent` (full before/after)
- **Extensions:** `providerid`, `tenancyid` (when non-null)

Consumers that prefer decoupled types can observe `@ObservesAsync CloudEvent` instead of `StateChangeEvent`.

---

## DeviceStateHistoryProvider SPI

`io.casehub.iot.api.spi.DeviceStateHistoryProvider` -- optional SPI for querying historical device state:

```java
public interface DeviceStateHistoryProvider {
    record HistoryEntry(String deviceId, String deviceClass,
                        DeviceEntity stateSnapshot,
                        List<String> changedCapabilities,
                        Instant occurredAt) {}

    List<HistoryEntry> findHistory(String deviceId, Instant from, Instant to, int limit);
}
```

Implemented by `JpaDeviceStateHistoryProvider` in the webapp module. Not available in all deployments -- consumers should check availability via `Instance<DeviceStateHistoryProvider>.isResolvable()`.

---

## SSE Device Status Streaming

`DeviceSseResource` in the webapp module (`GET /api/devices/stream`) produces `SERVER_SENT_EVENTS`. Sends an initial "snapshot" operation with all devices, then streams "replace" operations on state changes. Filters by tenancy ID via `CurrentPrincipal`.

---

## MCP Tools

The `mcp` module (`casehub-iot-mcp`) provides four tools for LLM agent integration via `IoTDeviceMcpTool` (`@ApplicationScoped`):

### iot_get_devices

Lists devices with optional filters. Parameters:
- `deviceClass` (optional) -- filter by `DeviceClass` enum value (case-insensitive)
- `providerId` (optional) -- filter by provider (e.g. "homeassistant", "openhab")
- `available` (optional) -- filter by online/offline status

Returns JSON array of device summaries (deviceId, class, label, location, provider, availability).

### iot_get_state

Gets current state for a specific device. Parameters:
- `deviceId` (required) -- device identifier

Returns full JSON device state including typed fields.

### iot_send_command

Sends a command to a device. Parameters:
- `deviceId` (required) -- target device
- `action` (required) -- command action (turn_on, turn_off, set_temperature, lock, unlock, set_position, set_volume)
- `parameters` (optional) -- command parameters map

Fires `IoTCommandAuditEvent` via CDI for audit trail. Returns confirmation with correlationId on success.

### iot_get_history

Gets state change history for a device. Parameters:
- `deviceId` (required) -- target device
- `from` (optional) -- ISO-8601 start time
- `to` (optional) -- ISO-8601 end time
- `limit` (optional) -- max entries (default 50, max 200)

Requires a `DeviceStateHistoryProvider` implementation (available in webapp deployments). Returns "not available" when no provider is present.

**Host-agnostic:** injects `DeviceRegistry` and `Instance<DeviceProvider>` -- sees whatever providers the host app configures.

---

## Testing Infrastructure

The `testing` module (test scope only) provides:

- **`MockDeviceProvider`** (`io.casehub.iot.testing.MockDeviceProvider`) -- CDI mock implementing `DeviceProvider`. Programmatic device registration and manual event firing.
- **`MockDeviceRegistry`** (`io.casehub.iot.testing.MockDeviceRegistry`) -- standalone mock implementing `DeviceRegistry`. For unit tests without CDI container.
- **`Fixtures`** -- static factory methods for every device type (`Fixtures.light()`, `Fixtures.thermostat()`, etc.) producing pre-configured test devices.
- **`DeviceFixtureLoader`** -- YAML fixture loading via `DeviceTypeHandler` SPI (16 handlers for all device types including vendor supplements).
- **`DeviceTypeRegistry`** -- registry mapping `DeviceClass` enum values to their concrete classes and handlers.
- **`StateChangeEventPublisher`** -- fires `StateChangeEvent` via CDI `fireAsync()` for `@QuarkusTest` integration tests. Auto-derives `changedCapabilities` via `StateChangeEvent.deriveChangedCapabilities()`.

---

## Configuration

### Tenancy

Single root property: `casehub.iot.tenancy-id` (env var `CASEHUB_IOT_TENANCY_ID`). Injected via `@ConfigProperty(name = "casehub.iot.tenancy-id")` across all modules. One property, zero divergence risk.

### Home Assistant (`casehub.iot.homeassistant.*`)

`@ConfigMapping(prefix = "casehub.iot.homeassistant")` on `HomeAssistantConfig`:

| Property | Type | Default | Purpose |
|----------|------|---------|---------|
| `casehub.iot.homeassistant.enabled` | `boolean` | `false` | Activates provider via `@LookupIfProperty` |
| `casehub.iot.homeassistant.url` | `Optional<String>` | -- | HA instance URL. Auto-discovered via mDNS if absent. |
| `casehub.iot.homeassistant.token` | `Optional<String>` | -- | Long-lived access token |
| `casehub.iot.homeassistant.reconnect-base-seconds` | `int` | `5` | WebSocket backoff base |
| `casehub.iot.homeassistant.reconnect-max-seconds` | `int` | `300` | Backoff cap |
| `casehub.iot.homeassistant.ping-interval-seconds` | `int` | `30` | WebSocket keep-alive |
| `casehub.iot.homeassistant.pong-timeout-seconds` | `int` | `10` | Pong deadline |
| `casehub.iot.homeassistant.discovery-timeout-seconds` | `int` | `5` | mDNS discovery timeout |

### OpenHAB (`casehub.iot.openhab.*`)

`@ConfigMapping(prefix = "casehub.iot.openhab")` on `OpenHabConfig`:

| Property | Type | Default | Purpose |
|----------|------|---------|---------|
| `casehub.iot.openhab.enabled` | `boolean` | `false` | Activates provider via `@LookupIfProperty` |
| `casehub.iot.openhab.url` | `Optional<String>` | -- | OpenHAB instance URL. Auto-discovered via mDNS/SSDP if absent. |
| `casehub.iot.openhab.auth.bearer.token` | `String` | -- | API token (bearer auth) |
| `casehub.iot.openhab.auth.basic.username` | `String` | -- | Basic auth username |
| `casehub.iot.openhab.auth.basic.password` | `String` | -- | Basic auth password |
| `casehub.iot.openhab.reconnect-base-seconds` | `int` | `5` | SSE backoff base |
| `casehub.iot.openhab.reconnect-max-seconds` | `int` | `300` | Backoff cap |
| `casehub.iot.openhab.coalesce-window-ms` | `int` | `50` | SSE event coalescing window |
| `casehub.iot.openhab.thing-discovery-enabled` | `boolean` | `true` | Enable Thing-scoped discovery layer |
| `casehub.iot.openhab.discovery-timeout-seconds` | `int` | `10` | mDNS/SSDP discovery timeout |

Auth is structured: `auth.bearer.token` for bearer auth, `auth.basic.username` + `auth.basic.password` for HTTP basic auth. Exactly one auth method must be configured.

### Provider Activation

Both providers use `@LookupIfProperty(name = "casehub.iot.<provider>.enabled", stringValue = "true")`. When `enabled` is `false` or absent, the provider bean is invisible to CDI `Instance<DeviceProvider>`. All config properties are `Optional<String>` to prevent SmallRye startup validation failure when a provider is disabled.

---

## Dependencies

`casehub-iot-api` depends on `casehub-platform-api` (shared vocabulary + CloudEvents SDK). Jackson annotations for `DeviceTypeIdResolver` polymorphic serialization (iot#5) -- `api` includes `quarkus-jackson` as a compile dependency. Provider modules depend on Quarkus REST Client, Jackson, and WebSocket/SSE extensions.

---

## Depended On By

| Repo | What it uses |
|------|-------------|
| `casehub-life` | Device discovery, state events, command dispatch for household automation |
| `casehub-ops` | IoT desired-state domain implementation |

---

## What It Does NOT Do

- **No domain logic.** IoT provides device abstraction, not automation rules. Business logic belongs in consuming repos (casehub-life, casehub-ops).
- **No direct provider coupling.** Consumers never import `homeassistant` or `openhab` modules -- they depend on `api` and receive providers via CDI.
- **No persistence.** Device state is live from providers. Historical state tracking is provided optionally by the webapp module's `JpaDeviceStateHistoryProvider`.
- **No UI framework.** The webapp module is a standalone operational console, not a reusable UI component.
- **No authorization.** `iot-api` does not enforce authorization. Application tier handles permission checks before dispatch.

---

## Design Documents

- **ARC42STORIES:** `ARC42STORIES.MD` (root) -- full architectural narrative, chapters C1-C7 complete
- **Foundation design spec:** `docs/superpowers/specs/2026-06-05-iot-foundation-design.md`
- **Bridge deployment:** `bridge/DEPLOYMENT.md`

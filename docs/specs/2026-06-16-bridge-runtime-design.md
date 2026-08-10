# casehub-iot Bridge Runtime — Design Spec

**Date:** 2026-06-16
**Issue:** casehubio/iot#5
**Foundation spec:** `docs/superpowers/specs/2026-06-05-iot-foundation-design.md`
**ARC42 chapter:** C5 — Bridge (L6)
**Deferred:** Durable store-and-forward — casehubio/iot#20

---

## Overview

The bridge runtime enables cloud and hybrid deployment of casehub-iot. It is a **two-sided tunnel via the `DeviceProvider` SPI** — remote devices look local to cloud consumers. The local bridge agent runs alongside HA/OpenHAB, observes `StateChangeEvent`s, runs an extensible event filter chain, and relays to the cloud. The cloud-side `BridgeDeviceProvider` receives events, fires them into CDI, and routes commands back.

**Constraint:** The bridge has **no domain logic** — pure event forwarding and command relay. Local processing (Drools triggers, YAML automations, safety rules) is achieved by adding application-tier libraries as classpath dependencies of the bridge deployment. This is standard Quarkus CDI extension — not a bridge feature. "Hybrid" is a deployment topology choice, not a bridge mode.

**ARC42 §7 superseded:** The hybrid mode configuration properties (`casehub.iot.bridge.local-automations`, `casehub.iot.bridge.cloud-automations`) described in ARC42STORIES.MD §7 are superseded by the deployment topology model. The bridge always forwards all events; local processing is achieved via CDI classpath extension, not bridge configuration.

---

## Module Structure

Two modules replace the current empty `bridge/`:

| Module | Artifact | Type | Purpose |
|---|---|---|---|
| `bridge` | `casehub-iot-bridge` | Standalone Quarkus app | Local agent — observes events, runs filter chain, relays to cloud, dispatches commands |
| `bridge-server` | `casehub-iot-bridge-server` | Library | Cloud-side `BridgeDeviceProvider implements DeviceProvider` — added as dependency by cloud consumers |

Shared wire protocol types, `BridgeEventFilter` SPI, and Jackson serialization configuration live in `casehub-iot-api` under the `io.casehub.iot.api.bridge` subpackage. Both bridge modules depend on iot-api.

```
casehub-iot/
├── api/                  L1 + L2 (+ Jackson annotations on DeviceEntity, + bridge subpackage)
├── homeassistant/        L3
├── openhab/              L4
├── testing/              L5
├── bridge/               L6a: Local agent (standalone Quarkus app)
└── bridge-server/        L6b: Cloud-side BridgeDeviceProvider (library)
```

### L1 Boundary Change

Adding `@JsonTypeInfo` to `DeviceEntity` introduces `jackson-databind` as a compile dependency of `iot-api`. This is a deliberate architectural boundary change: L1 was previously "Pure Java — types, enums, value objects. Zero framework dependency." It now includes Jackson annotations for serialization support.

This is the right trade-off. The `DeviceEntity` hierarchy crosses process boundaries — serialization is a first-class concern, not an implementation detail. Jackson is already on the classpath of every Quarkus consumer. The ARC42 L1 description should be updated to reflect this change.

---

## Wire Protocol

Communication uses a sealed `BridgeMessage` interface. Each message type is a record carrying only its required fields — no casting, no null correlationId on message types that don't use it.

### BridgeMessage

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = BridgeMessage.StateChange.class, name = "STATE_CHANGE"),
    @JsonSubTypes.Type(value = BridgeMessage.StateSnapshot.class, name = "STATE_SNAPSHOT"),
    @JsonSubTypes.Type(value = BridgeMessage.ProviderStatusChange.class, name = "PROVIDER_STATUS"),
    @JsonSubTypes.Type(value = BridgeMessage.Command.class, name = "COMMAND"),
    @JsonSubTypes.Type(value = BridgeMessage.CommandResponse.class, name = "COMMAND_RESULT"),
    @JsonSubTypes.Type(value = BridgeMessage.Heartbeat.class, name = "HEARTBEAT")
})
public sealed interface BridgeMessage {
    String tenancyId();
    Instant timestamp();

    record StateChange(String tenancyId, Instant timestamp,
                       StateChangeEvent event) implements BridgeMessage {}
    record StateSnapshot(String tenancyId, Instant timestamp,
                         List<DeviceEntity> devices) implements BridgeMessage {}
    record ProviderStatusChange(String tenancyId, Instant timestamp,
                                ProviderStatusEvent status) implements BridgeMessage {}
    record Command(String tenancyId, Instant timestamp,
                   String correlationId, DeviceCommand command) implements BridgeMessage {}
    record CommandResponse(String tenancyId, Instant timestamp,
                           String correlationId,
                           io.casehub.iot.api.CommandResult result) implements BridgeMessage {}
    record Heartbeat(String tenancyId, Instant timestamp) implements BridgeMessage {}
}
```

Pattern matching with `switch` is exhaustive — compiler catches missing cases. Each record validates its own invariants in its compact constructor. `correlationId` exists only on `Command` and `CommandResponse`. Record names `ProviderStatusChange` and `CommandResponse` avoid shadowing the existing `ProviderStatus` enum and `CommandResult` enum. Wire format names (`PROVIDER_STATUS`, `COMMAND_RESULT`) are unchanged.

### Message Directions

| Type | Direction | Purpose |
|---|---|---|
| `StateChange` | agent → server | Device state changed |
| `StateSnapshot` | agent → server | Full state sync on connect/reconnect |
| `ProviderStatus` | agent → server | Provider connected/disconnected |
| `Command` | server → agent | Cloud dispatches a command |
| `CommandResult` | agent → server | Result of dispatched command |
| `Heartbeat` | both | Connection liveness |

### Transport

WebSocket via `quarkus-websockets-next`. Agent connects outward to server endpoint (no inbound ports needed on the local network). Authentication via `Authorization: Bearer <token>` header on the WebSocket upgrade request + `X-Tenancy-ID` header. TLS required in production.

---

## DeviceEntity Serialization

### Type Discrimination

`DeviceEntity` uses a compound `@deviceType` property that encodes both the `DeviceClass` and the concrete type name, separated by `:`. This is necessary because vendor supplement types share the same `deviceClass` as their parent (e.g., both `ThermostatDevice` and `HomeAssistantThermostat` have `deviceClass = THERMOSTAT`), and the `DeserializationProblemHandler.handleUnknownTypeId()` method has no access to other JSON fields — only the type ID string.

Compound type ID format:

```
@deviceType: "THERMOSTAT:ThermostatDevice"          // common type
@deviceType: "THERMOSTAT:HomeAssistantThermostat"    // HA supplement
@deviceType: "COVER:OpenHabRollershutter"            // OH supplement
```

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.CUSTOM, property = "@deviceType")
@JsonTypeIdResolver(DeviceTypeIdResolver.class)
public abstract class DeviceEntity { ... }
```

No `@JsonSubTypes` annotation — the custom `DeviceTypeIdResolver` maintains the full type registry. Common types register at resolver construction from `DeviceClass` → class mappings. Supplement types register via vendor Jackson Modules.

### DeviceTypeIdResolver

The resolver handles both serialization (class → compound ID) and deserialization (compound ID → class):

**Serialization:** `idFromValue(Object value)` produces `"{deviceClass}:{simpleClassName}"` — e.g., a `HomeAssistantThermostat` instance produces `"THERMOSTAT:HomeAssistantThermostat"`.

**Deserialization:** `typeFromId(DatabindContext ctx, String id)`:
1. Try full ID (e.g., `"THERMOSTAT:HomeAssistantThermostat"`) against registered types — if found, return the specific class
2. If not found, split on `:`, use the prefix (e.g., `"THERMOSTAT"`) to map `DeviceClass` → common type (e.g., `ThermostatDevice`)
3. Log warning: `"Unknown device type 'HomeAssistantThermostat' — falling back to ThermostatDevice. Add casehub-iot-homeassistant to classpath for full type fidelity."`

The fallback is self-contained within the type ID string — no access to other JSON fields needed. Handles all cases including `OpenHabRollershutter` → `CoverDevice` (prefix `COVER`).

### Vendor Supplement Type Registration

Each provider module ships a Jackson `Module` as a `@Singleton` CDI bean (Quarkus auto-discovers and registers it with the `ObjectMapper`). Non-Quarkus consumers fall back to `ServiceLoader` (`META-INF/services/com.fasterxml.jackson.databind.Module`).

**Important:** `context.registerSubtypes()` feeds Jackson's standard `TypeNameIdResolver`, not a custom resolver. Since `DeviceEntity` uses `@JsonTypeIdResolver(DeviceTypeIdResolver.class)`, vendor Modules must register directly with the custom resolver via `DeviceTypeIdResolver.registerType()`:

```java
// In casehub-iot-homeassistant
@Singleton
public class HomeAssistantJacksonModule extends SimpleModule {
    @Override
    public void setupModule(SetupContext context) {
        DeviceTypeIdResolver.registerType("THERMOSTAT:HomeAssistantThermostat", HomeAssistantThermostat.class);
        DeviceTypeIdResolver.registerType("LIGHT:HomeAssistantLight", HomeAssistantLight.class);
        DeviceTypeIdResolver.registerType("LOCK:HomeAssistantLock", HomeAssistantLock.class);
    }
}
```

`DeviceTypeIdResolver` maintains a static `ConcurrentHashMap<String, Class<? extends DeviceEntity>>` registry. Common types register at resolver initialization from `DeviceClass` → class mappings. Vendor Modules add supplement types during `setupModule()`, which runs during `ObjectMapper` initialization — before any deserialization. The registry is set-once-at-startup, read-only during operation.

### Graceful Degradation

When the cloud-side encounters an unknown compound type ID (e.g., `"THERMOSTAT:HomeAssistantThermostat"` without `casehub-iot-homeassistant` on classpath), `DeviceTypeIdResolver.typeFromId()` falls back to the common parent type using the `DeviceClass` prefix. No `DeserializationProblemHandler` needed — the resolver handles fallback directly.

Supplement-specific fields are silently dropped by Jackson (unknown properties ignored). The device is represented as its common parent type with all cross-vendor fields intact.

---

## Event Filter Chain

The bridge agent runs a CDI-discovered filter chain before relaying `StateChangeEvent`s to cloud. `ProviderStatusEvent`s always relay — they are operational signals, not subject to filtering.

### BridgeEventFilter SPI

```java
public interface BridgeEventFilter {
    int priority();
    Uni<FilterAction> filter(StateChangeEvent event, FilterContext ctx);
}
```

```java
public sealed interface FilterAction {
    record Forward() implements FilterAction {}
    record Suppress(String reason) implements FilterAction {}
}
```

### FilterContext

Read-only context: `tenancyId`, `connectionState` (cloud connected/disconnected), `providerId` (from the event). Deliberately minimal — a filter operates on a single `StateChangeEvent` and doesn't need the full provider status map.

### Execution

1. Provider fires `StateChangeEvent` via CDI `fireAsync()`
2. Bridge agent's `@ObservesAsync` handler feeds the event to the filter chain
3. Filters execute in priority order (lowest first, consistent with CDI `@Priority`)
4. Each filter returns `Forward()` or `Suppress(reason)`
5. First `Suppress` short-circuits — event not relayed, reason logged
6. If all filters return `Forward`, relay sends to cloud

**No filters registered?** Forward all. The filter chain is a pass-through. Zero-filter deployment is a pure relay.

### Example Filters (not shipped in C5)

```java
// Property management: throttle temperature noise
@ApplicationScoped
public class TemperatureThrottle implements BridgeEventFilter {
    public int priority() { return 100; }
    public Uni<FilterAction> filter(StateChangeEvent event, FilterContext ctx) {
        // suppress if temperature changed < 0.5°C since last forwarded
    }
}

// Privacy: suppress bedroom presence events from cloud relay
@ApplicationScoped
public class PrivacyFilter implements BridgeEventFilter {
    public int priority() { return 50; }
    public Uni<FilterAction> filter(StateChangeEvent event, FilterContext ctx) {
        // suppress events from devices tagged as private
    }
}
```

---

## Bridge Agent (Local Side)

Standalone Quarkus app deployed on the user's local network alongside HA/OpenHAB.

### Dependencies

`casehub-iot-api` + `quarkus-websockets-next` + chosen provider module(s). Filter libraries as optional classpath dependencies.

### Components

| Component | Role |
|---|---|
| `BridgeEventObserver` | `@ObservesAsync StateChangeEvent` — feeds to filter chain. `@ObservesAsync ProviderStatusEvent` — relays directly (not filtered). |
| `BridgeFilterChain` | Discovers `BridgeEventFilter` beans via `@Any Instance<>`, sorts by priority, chains execution |
| `BridgeCloudClient` | WebSocket client — connects outward to cloud endpoint, sends `BridgeMessage`, receives `Command` |
| `BridgeCommandDispatcher` | Receives `Command` from cloud, strips tenancy prefix from device ID, resolves provider via `DeviceRegistry`, calls `dispatch()`, sends `CommandResult` back |
| `BridgeConnectionManager` | Connection lifecycle — auth handshake, exponential backoff reconnection, heartbeat, state snapshot on reconnect |

### Connection Lifecycle

1. **Startup:** connect to `casehub.iot.bridge.cloud-endpoint` with auth token + tenancy ID
2. **On connect:** call `discover()` on all providers, send `StateSnapshot` with raw local device IDs
3. **On state change:** filter chain → relay (if not suppressed), events carry raw local device IDs
4. **On provider status change:** relay `ProviderStatus` directly (not filtered)
5. **On disconnect:** events are not relayed (local CDI observers still fire). Reconnect with exponential backoff (base 5s, max 5min).
6. **On reconnect:** send fresh `StateSnapshot` from `discover()`. No event replay.

### No Event Buffer

Events during disconnection are not buffered. On reconnect, the bridge sends a `StateSnapshot` from `discover()` — cloud gets current truth.

Replaying buffered events risks ghost automations: a presence event from 30 seconds ago triggers a security case for a past arrival, while the current state shows no presence. The snapshot provides the correct current truth. This is the same behavior as crash recovery (which the spec already accepts as sufficient for home IoT). Durable store-and-forward for consumers requiring complete history is tracked as casehubio/iot#20.

### Configuration

```properties
casehub.iot.bridge.cloud-endpoint=wss://casehub.example.com/iot/bridge
casehub.iot.bridge.token=<bridge-auth-token>
casehub.iot.bridge.tenant-id=<tenancy-id>
casehub.iot.bridge.reconnect-base-seconds=5
casehub.iot.bridge.reconnect-max-seconds=300
casehub.iot.bridge.heartbeat-interval-seconds=30
```

---

## Bridge Server (Cloud Side)

Library module added as a dependency by cloud consumers.

### Components

| Component | Role |
|---|---|
| `BridgeDeviceProvider` | `@ApplicationScoped DeviceProvider` — maintains device map from snapshots/events, dispatches commands via WebSocket |
| `BridgeWebSocketEndpoint` | WebSocket server endpoint — accepts inbound connections from bridge agents |
| `BridgeConnectionRegistry` | Tracks connected bridge agents by tenancy ID — supports multi-site |

### Multi-Site Device ID Namespacing (Server-Side)

Device IDs from local providers are installation-local (e.g., `light.kitchen`). Multiple bridge agents connecting to one cloud endpoint will collide. The bridge **server** namespaces device IDs by prepending the tenancy ID after receiving events — the agent sends raw local IDs.

Namespacing uses the Jackson type system as a polymorphic copy mechanism, since `DeviceEntity` is an abstract class hierarchy and not all types support `toBuilder()`:

```java
DeviceEntity namespace(DeviceEntity device, String tenancyId, ObjectMapper mapper) {
    ObjectNode tree = mapper.valueToTree(device);
    tree.put("deviceId", tenancyId + "/" + device.deviceId());
    return mapper.treeToValue(tree, DeviceEntity.class);
}
```

This works for all types including vendor supplements. The `@JsonTypeInfo` infrastructure the spec adds makes it possible. `toBuilder()` is deliberately absent on the 4 common types that have supplement subclasses (ThermostatDevice, LightDevice, LockDevice, CoverDevice) — adding it would create a type-slicing trap where calling `toBuilder()` on a `HomeAssistantThermostat` accessed as a `ThermostatDevice` reference returns a `ThermostatDevice.Builder`, silently dropping supplement fields.

**Namespacing on the server rather than the agent** keeps the agent simpler (pure relay, sends events as-is), puts rewriting logic in one place, and keeps the wire format carrying original device IDs (more debuggable).

- **Inbound events:** `light.kitchen` → `{tenancyId}/light.kitchen` (applied on deserialization)
- **Outbound commands:** `{tenancyId}/light.kitchen` → `light.kitchen` (stripped before sending `Command` to agent)

### BridgeDeviceProvider Behaviour

- `providerId()` — returns `"bridge"`
- `discover()` — returns current device map (populated from most recent `StateSnapshot`, with namespaced IDs)
- `dispatch(DeviceCommand)` — strips tenancy prefix from `targetDeviceId`, serializes into `Command` message, sends via WebSocket to correct agent (resolved by tenancy prefix), waits for `CommandResult` with matching `correlationId`. Returns `Uni<CommandResult>` with configurable timeout.
- `status()` — `CONNECTED` when all known tenancies have active agents, `DISCONNECTED` when no agents are connected, `CONNECTING` when at least one tenancy is reconnecting but others are active
- On `StateChange` received — namespaces device IDs, updates device map, fires `StateChangeEvent` into CDI via `Event.fireAsync()`, preserving the original `providerId` from the agent
- On `StateSnapshot` received — namespaces device IDs, replaces device map for that tenancy. For devices whose state differs from previous snapshot: fires `StateChangeEvent` with `providerId = "bridge"`. For first-appearance devices (no `before`): fires with `changedCapabilities = after.capabilities().keySet()` (cannot use `deriveChangedCapabilities()` which requires non-null `before`). For devices present in previous snapshot but absent from new snapshot: marks `available = false` using Jackson tree copy, fires `StateChangeEvent` with `changedCapabilities = {"available"}`.
- On `ProviderStatus` received — fires `ProviderStatusEvent` into CDI

### Hexagonal Integration

Cloud consumers are completely unaware of the bridge:

```java
// casehub-life — works identically whether devices are local or remote
@ApplicationScoped
public class HomeAutomationEventObserver {
    void onStateChange(@ObservesAsync StateChangeEvent event) {
        // Triggered by HomeAssistantProvider, OpenHabProvider,
        // OR BridgeDeviceProvider — consumer doesn't know which
    }
}
```

### Multi-Site Support

`BridgeConnectionRegistry` maps `tenancyId → WebSocket session`. Multiple bridge agents connect to one cloud instance (e.g., property management company with 50 rentals). `dispatch()` routes commands to the correct agent by extracting the tenancy prefix from `DeviceCommand.targetDeviceId`. If an agent disconnects, its devices remain in the map marked `available=false`.

### Configuration

```properties
casehub.iot.bridge-server.command-timeout-seconds=30
```

---

## Deployment Topologies

The bridge design supports six deployment topologies without bridge-specific configuration:

### 1. SaaS

```
LOCAL:  bridge + iot-ha          →  cloud: bridge-server + casehub-life
```

All events forwarded. All processing in cloud. Simplest deployment.

### 2. Hybrid

```
LOCAL:  bridge + iot-ha + life-triggers.jar  →  cloud: bridge-server + casehub-life
```

Latency-sensitive observers (Drools, YAML triggers) fire locally via CDI — they are on the bridge's classpath. Bridge also relays all events to cloud for orchestration, HITL, ledger, memory. Consumer handles case deduplication.

### 3. Multi-Site

```
SITE A: bridge + iot-ha  ──┐
SITE B: bridge + iot-oh  ──┼→  cloud: bridge-server + management-app
SITE C: bridge + iot-ha  ──┘
```

`BridgeConnectionRegistry` maps tenancy ID → session. Device IDs namespaced server-side. Commands route to correct site.

### 4. Constrained Edge

```
LOCAL (RPi): bridge + iot-ha     →  cloud: bridge-server + casehub-life
```

Bridge is lightweight — no domain logic. RPi runs the bridge; cloud runs the full stack.

### 5. Development

```
LOCAL: bridge + iot-ha  →  localhost: bridge-server + casehub-life (dev mode)
```

Developer tests cloud integration without deploying. Both sides on localhost.

### 6. Multiple Consumers

```
LOCAL: bridge + iot-ha  →  cloud: bridge-server + casehub-life
                        →  cloud: bridge-server + casehub-ops (separate deployment)
```

Multiple cloud deployments can run bridge-server independently. Each maintains its own device map.

---

## Scope — What C5 Delivers

**In scope:**
- `casehub-iot-bridge` standalone Quarkus app (local agent)
- `casehub-iot-bridge-server` library (cloud-side `BridgeDeviceProvider`)
- Sealed `BridgeMessage` interface with 6 message types
- `BridgeEventFilter` SPI (filter chain runner — no concrete filters)
- Jackson `@JsonTypeInfo` on `DeviceEntity` with custom `DeviceTypeIdResolver`, compound `@deviceType` discriminator, and `@JsonTypeIdResolver` annotation
- Vendor Jackson `Module` as `@Singleton` CDI beans (Quarkus) with `ServiceLoader` fallback (non-Quarkus)
- Graceful degradation for unknown supplement types (compound ID prefix → common parent, warning logged)
- WebSocket transport with auth, reconnection, heartbeat
- State snapshot on connect/reconnect (no event buffer)
- Multi-site support via `BridgeConnectionRegistry` with server-side device ID namespacing (Jackson tree copy)
- Device removal handling (absent from snapshot → mark `available=false`, fire event)
- Configuration via `@ConfigMapping`

**Out of scope:**
- Concrete filter implementations (filtering, throttling, enrichment) — documented as examples
- Durable store-and-forward (casehubio/iot#20)
- Event enrichment SPI — separate concern if needed, not part of the filter chain
- Hybrid mode automation config — hybrid is a deployment topology, not a bridge feature
- MQTT/NATS transport alternatives — deferred to platform-level stream infrastructure
- Cloud-side consumer logic (casehub-life, casehub-ops)
- Adding `toBuilder()` to common types with supplement subclasses — deliberately absent to prevent type-slicing

---

## Architectural Decisions

### ADR — Two-sided tunnel via DeviceProvider SPI (not one-sided relay)

**Context:** Bridge needs to forward events from local providers to cloud consumers. Options: (a) one-sided relay — bridge pushes to wire protocol, consumer writes its own receiver; (b) two-sided tunnel — bridge ships both local agent and cloud-side `DeviceProvider`.

**Decision:** Two-sided tunnel. `BridgeDeviceProvider` implements `DeviceProvider` — cloud consumers treat remote devices identically to local ones.

**Consequences:** Cloud consumers need zero bridge-specific code. `HomeAutomationEventObserver` works unchanged. The `DeviceProvider` SPI was designed for exactly this kind of abstraction.

### ADR — CDI-discovered event filters (not SmallRye Reactive Messaging)

**Context:** The bridge needs an extensible event processing mechanism. Options: (a) SmallRye Reactive Messaging `@Incoming`/`@Outgoing` channels; (b) CDI-discovered SPI beans.

**Decision:** CDI-discovered `BridgeEventFilter` beans via `@Any Instance<>`, same pattern as `DeviceProvider`, `LedgerEntryEnricher`, `AgentRoutingStrategy`. Filters process `StateChangeEvent` directly — not protocol-level wrappers.

**Consequences:** Consistent with CaseHub platform patterns. No new pipeline concepts. SmallRye Reactive Messaging is for inter-service transport (Kafka, AMQP, MQTT) — using it for in-app event processing would be architecturally awkward. Filter scope is deliberately narrow (filter/suppress only, not enrich/transform) — enrichment is a separate concern added as a separate SPI if needed.

### ADR — Compound type ID with custom DeviceTypeIdResolver (not deviceClass discriminator)

**Context:** `DeviceEntity` is an abstract class hierarchy with vendor supplements. `deviceClass` cannot discriminate between a common type and its supplement (both `ThermostatDevice` and `HomeAssistantThermostat` have `deviceClass = THERMOSTAT`). `DeserializationProblemHandler.handleUnknownTypeId()` receives only the type ID string — no access to other JSON fields for fallback resolution.

**Decision:** Compound type ID format `"{DeviceClass}:{ClassName}"` with custom `DeviceTypeIdResolver`. The resolver splits on `:` — tries full ID first, falls back to the `DeviceClass` prefix to find the common parent type. No `@JsonSubTypes` needed; resolver maintains the full registry. No `DeserializationProblemHandler` needed; fallback is self-contained in the type ID.

**Consequences:** Full type fidelity when vendor module is on classpath. Graceful degradation when it isn't — DeviceClass prefix provides the fallback mapping. Handles all cases including `OpenHabRollershutter` → `CoverDevice` (prefix `COVER`). Wire format is slightly noisier but self-documenting.

**Alternatives rejected:** Simple class names with `DeserializationProblemHandler` — handler has no JSON access, can't determine fallback type. `@JsonSubTypes` with `deviceClass` discriminator — cannot distinguish supplements from common types.

### ADR — Sealed BridgeMessage interface (not Object payload envelope)

**Context:** Wire protocol message format. Options: (a) single record with `Object payload` and type discriminator; (b) sealed interface with typed record variants.

**Decision:** Sealed interface. Each message type is a record carrying only its required fields.

**Consequences:** Exhaustive pattern matching via `switch`. No casting. `correlationId` only on `Command` and `CommandResult`. Each record validates its own invariants. Wire format is self-documenting — each type names its content field explicitly.

### ADR — Snapshot-only reconnection (no event buffer)

**Context:** What happens during cloud disconnection? Options: (a) buffer events in-memory, replay on reconnect; (b) drop events, send state snapshot on reconnect.

**Decision:** No buffer. On reconnect, send `StateSnapshot` from `discover()`.

**Consequences:** Transition history during disconnection is lost. Cloud gets current truth. This eliminates ghost automation risk (replayed events triggering cases for past state transitions) and is the same behavior as crash recovery — which the spec already accepts as sufficient for home IoT. Durable store-and-forward tracked as casehubio/iot#20 for consumers requiring complete history.

### ADR — Server-side device ID namespacing via Jackson tree copy (not agent-side rewriting)

**Context:** Multi-site deployments have device ID collisions across tenancies (e.g., `light.kitchen` in two HA installations). `CdiDeviceRegistry` keys by `deviceId()` alone. `DeviceEntity` has no polymorphic copy mechanism — `toBuilder()` is deliberately absent on the 4 common types with supplement subclasses to prevent type-slicing.

**Decision:** Bridge server namespaces device IDs as `{tenancyId}/{deviceId}` after deserialization, using Jackson `ObjectMapper.valueToTree()` → modify → `treeToValue()` as the polymorphic copy mechanism. Works for all types including vendor supplements. Agent sends raw local IDs; wire format carries original device IDs.

**Consequences:** Agent stays simple (pure relay). Rewriting logic in one place (server). Wire format debuggable with original IDs. `DeviceCommand.targetDeviceId` uses composite form on cloud side; server strips prefix before sending `Command` to agent.

**Alternatives rejected:** Agent-side rewriting — duplicates logic (outbound + inbound). Adding `toBuilder()` to ThermostatDevice/LightDevice/LockDevice/CoverDevice — creates type-slicing trap (supplement fields silently dropped when called via common type reference). Changing `DeviceRegistry.findById()` to require tenancy — breaking change affecting all consumers for a bridge-specific concern.

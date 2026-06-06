# casehub-iot — ARC42STORIES.MD

**Spec:** Arc42Stories v0.1
**Profile:** CaseHub — Foundation tier
**Profile ref:** `../parent/docs/arc42stories-casehub-profile.md` · fallback: `https://raw.githubusercontent.com/casehubio/parent/main/docs/arc42stories-casehub-profile.md`
**Build position:** Foundation — no casehubio dependencies
**Consumed by:** casehub-life (Layer 9), future property management, elder care apps
**Depends on:** none
**Prefix:** IOT
**Status:** Not started. Chapters C1–C5 scoped. No implementation yet.

---

## §1 Introduction and Goals

### Description

`casehub-iot` is a **foundation IoT device abstraction layer** for the CaseHub ecosystem — peer to `casehub-connectors`. It provides a typed device class hierarchy aligned with the Matter Device Type Library, provider SPIs for platform integration, and platform implementations for Home Assistant and OpenHAB. Consumed by application-tier repos (initially `casehub-life` Layer 9) — never modified by them.

IoT platforms speak different protocols, expose different entity models, and use different state representations. Application code should not know or care which platform is underneath. `casehub-iot` closes that gap: a single `DeviceEntity` hierarchy, a single `StateChangeEvent`, a single `DeviceCommand` — regardless of whether the underlying platform is Home Assistant, OpenHAB, or a future addition.

### Stakeholders

| Stakeholder | Interest |
|---|---|
| casehub-life developers | Primary consumer — Layer 9 home automation; depend on stable iot-api for automation rules |
| Future app-tier consumers | Property management, elder care — need cross-platform device access without vendor lock-in |
| Platform team | Validates foundation correctness under downstream integration requirements |
| Smart home users | Cross-platform device control; automations portable between HA and OpenHAB |

### Quality Goals

| Priority | Goal | Scenario |
|---|---|---|
| 1 | Cross-vendor portability | Automation rule written against iot-api types works identically with HA and OpenHAB — no platform-specific code in consumers |
| 2 | API stability | casehub-life upgrades iot-api minor version; all existing code compiles without changes |
| 3 | Test without hardware | casehub-life runs device-dependent integration tests with MockDeviceProvider and fixture devices; no live HA/OpenHAB needed |
| 4 | Low-latency state propagation | Device state change in HA/OpenHAB surfaces as StateChangeEvent within 100ms on local network |
| 5 | Extensible provider model | New platform (e.g. SmartThings) added by implementing DeviceProvider — no changes to iot-api or existing providers |

### Artifact Schema

PREFIX: `IOT` (issues only — foundation-tier modules use issue refs directly)

| Artifact type | Format | Example | Where it lives |
|---|---|---|---|
| Issue | `#NNN` or `casehubio/casehub-iot#NNN` | `#1`, `casehubio/casehub-iot#1` | GitHub Issues |
| Garden entry | `GE-YYYYMMDD-XXXXXX` | `GE-20260606-a1b2c3` | `~/.hortora/garden/` |
| Protocol | `PP-YYYYMMDD-XXXXXX` | `PP-20260606-d4e5f6` | `casehub-parent/docs/protocols/` |
| ADR | `ADR-NNNN` | `ADR-0001` | `docs/adr/` |
| Blog entry | `YYYY-MM-DD-[initials]NN-title` | `2026-06-06-mdp01-core-api` | workspace `blog/` |
| Design spec | `YYYY-MM-DD-topic-design` | `2026-06-05-iot-foundation-design` | `docs/superpowers/specs/` |

---

## §2 Constraints

### Platform

| Constraint | Value | Rationale |
|---|---|---|
| Java version | Java 21 | Language features; ecosystem-wide alignment |
| Framework | Quarkus 3.x | CDI, REST client, WebSocket client, SSE |
| Build tool | Maven (system `mvn`, no wrapper) | Ecosystem-wide convention |
| Async model | Mutiny `Uni<>` | Quarkus native reactive; provider operations are I/O-bound |
| GroupId | `io.casehub` | Ecosystem-wide; publishes before casehub-life in build order |

### Architectural

- **Public API surface:** `casehub-iot-api` is semver from first release. Community automations in casehub-life depend on it. No breaking changes without a major version bump.
- **Common interface first:** The typed device class hierarchy carries everything with a cross-vendor mapping. Vendor supplement subclasses exist only for genuinely unmappable fields.
- **Matter alignment:** Device class vocabulary aligned with the Matter Device Type Library — industry-standard device categorization.
- **Test-scope only:** `iot-testing` is never a compile or runtime dependency.
- **Bridge is stateless:** `iot-bridge` has no domain logic — pure event forwarding and command relay.
- **Authorization-agnostic:** `iot-api` does not enforce authorization. Application tier (casehub-life) handles permission checks before dispatch.
- **Production-first:** Before writing any class, apply: "Would this class exist in a production system built to this chapter and no further?" If no — do not build it.

### Dependencies

| Dependency | Purpose |
|---|---|
| Quarkus REST client | HA REST API, OpenHAB REST API |
| Quarkus WebSocket Next client | HA WebSocket API (`/api/websocket`) |
| Quarkus REST client reactive | OpenHAB SSE stream |
| Quarkus CDI | Provider discovery via `@Any Instance<DeviceProvider>` |

No casehubio dependencies. Foundation-tier peer to `casehub-connectors`.

---

## §3 Context and Scope

```mermaid
C4Context
  title casehub-iot — System Context

  System(iot, "casehub-iot", "Typed device abstraction layer: DeviceProvider SPI, DeviceRegistry, StateChangeEvent, DeviceCommand")

  System_Ext(ha, "Home Assistant", "Local HA instance — REST API + WebSocket for state subscription")
  System_Ext(oh, "OpenHAB", "Local OpenHAB instance — REST API + SSE; semantic model required")
  System_Ext(life, "casehub-life", "Application-tier consumer — household automation, Layer 9")
  System_Ext(future, "Future consumers", "Property management, elder care apps")
  System_Ext(cloud, "Cloud CaseHub", "SaaS endpoint — receives bridged events, sends commands")

  Rel(iot, ha, "REST discovery, WebSocket subscription, service call dispatch")
  Rel(iot, oh, "REST discovery (semantic model), SSE subscription, item command dispatch")
  Rel(life, iot, "Depends on iot-api: DeviceRegistry, @ObservesAsync StateChangeEvent, DeviceCommand dispatch")
  Rel(future, iot, "Same dependency surface as casehub-life")
  Rel(iot, cloud, "Bridge mode: forwards StateChangeEvent, relays DeviceCommand")
```

### Boundary Rules

**casehub-iot owns:** device type vocabulary (DeviceEntity hierarchy, DeviceClass enum), provider SPI (DeviceProvider, DeviceRegistry), event model (StateChangeEvent, DeviceCommand), platform-specific providers (HA, OpenHAB), vendor supplement types, test infrastructure (MockDeviceProvider, fixtures), bridge runtime (event forwarding, command relay).

**Application tier owns:** authorization and permission checks, household/domain context, automation rules and triggers, device grouping by room/zone/purpose, SLA enforcement on device operations, human-in-the-loop approvals for device commands.

**casehub-iot does NOT own:** user identity, tenancy management (beyond `tenancyId` field on DeviceEntity), platform authentication credential storage, device firmware updates, platform configuration or setup.

---

## §4 Solution Strategy

### Core Architectural Patterns

| Tier | Pattern | IoT expression |
|---|---|---|
| Domain | **Type Hierarchy** | Abstract `DeviceEntity` base with 10 typed subclasses; Matter-aligned vocabulary |
| Integration | **Hexagonal (SPI)** | `DeviceProvider` and `DeviceRegistry` as ports; HA and OpenHAB as adapters |
| Eventing | **CDI async events** | `StateChangeEvent` via `Event.fireAsync()`; consumers use `@ObservesAsync` |
| Discovery | **CDI Instance<>** | Providers are `@ApplicationScoped` beans discovered via `@Any Instance<DeviceProvider>` |
| Mapping | **Strategy** | Platform-specific entity-to-DeviceEntity mappers — one per provider |
| Supplement | **Inheritance** | Vendor-specific fields via subclass extension; consumers downcast only when vendor-aware |

### Layer Taxonomy

| Layer | Concern | Module | Character |
|---|---|---|---|
| L1 | Device Type System | iot-api | Pure Java — types, enums, value objects. Zero framework dependency. |
| L2 | SPI & Registry | iot-api | CDI contracts, Uni<> async, default implementations |
| L3 | Home Assistant | iot-homeassistant | REST + WebSocket provider + supplement types |
| L4 | OpenHAB | iot-openhab | REST + SSE + semantic model + state cache + supplement types |
| L5 | Test Harness | iot-testing | Mock provider, fixture devices, event publisher |
| L6 | Bridge | iot-bridge | Cloud relay, deployment modes |

L1 and L2 both reside in `iot-api` but represent distinct architectural concerns: L1 is pure Java with zero framework imports (the vocabulary); L2 introduces CDI, Mutiny, and runtime behavior (the contracts). This mirrors the api/app split in application-tier repos at a finer grain.

### Chapter Sequencing Rationale

- C1 before C2: Core API provides the types and SPIs that test infrastructure implements (hard dependency)
- C2 before C3: MockDeviceProvider validates SPI contract; test infrastructure enables TDD of real providers (hard dependency)
- C3 before C4: HA is simpler (no state cache, no semantic model) — validates SPI against real platform data before tackling OpenHAB's higher complexity (minimal delta)
- C4 after C3: OpenHAB state cache coalescing and semantic model mapping are informed by HA experience (soft ordering)
- C5 after C3/C4: Bridge depends on iot-api + at least one working provider for integration testing (hard dependency)

---

## §5 Building Block View

### Layer Architecture View

Target delivery state — all layers complete.

```mermaid
C4Container
  title casehub-iot — Layer Architecture (target delivery state)

  Person(consumer, "Application Consumer", "casehub-life, property management, elder care")

  Container_Boundary(b1, "Layer: Device Type System (L1)") {
    Container(entity, "DeviceEntity hierarchy", "Pure Java", "10 typed device classes: Switch, Light, Thermostat, Sensor, PresenceSensor, PowerSensor, Lock, Cover, MediaPlayer, Fan")
    Container(enums, "DeviceClass + ThermostatMode + Temperature", "Pure Java", "Device class enum, mode enum, value type")
  }

  Container_Boundary(b2, "Layer: SPI & Registry (L2)") {
    Container(provider, "DeviceProvider SPI", "CDI", "discover() + dispatch() + status()")
    Container(registry, "DeviceRegistry + CdiDeviceRegistry", "CDI", "findById + findByClass + findAll + refresh")
    Container(events, "StateChangeEvent + DeviceCommand", "Java records", "Platform-agnostic event and command model")
  }

  Container_Boundary(b3, "Layer: Home Assistant (L3)") {
    Container(haprov, "HomeAssistantProvider", "Quarkus CDI", "REST discovery + WebSocket subscription + service call dispatch")
    Container(hasupps, "HA Supplement Types", "Java", "HomeAssistantThermostat + HomeAssistantLight + HomeAssistantLock")
  }

  Container_Boundary(b4, "Layer: OpenHAB (L4)") {
    Container(ohprov, "OpenHABProvider", "Quarkus CDI", "REST semantic model discovery + SSE subscription + item command dispatch")
    Container(ohcache, "State Cache", "Quarkus CDI", "Equipment-keyed device state with 50ms coalescing window")
    Container(ohsupps, "OH Supplement Types", "Java", "OpenHABThermostatItem + OpenHABRollershutter + OpenHABColorItem")
  }

  Container_Boundary(b5, "Layer: Test Harness (L5)") {
    Container(mock, "MockDeviceProvider + TestDeviceRegistry", "Java", "Programmatic device registration and manual event firing")
    Container(fixtures, "Fixture Devices", "YAML", "home-standard-fixtures.yaml pre-built device sets")
    Container(publisher, "StateChangeEventPublisher", "CDI", "Test helper: publish(before, after) with auto-derived changedCapabilities")
  }

  Container_Boundary(b6, "Layer: Bridge (L6)") {
    Container(bridge, "BridgeRuntime", "Quarkus", "WebSocket cloud relay for StateChangeEvent + DeviceCommand")
  }

  Rel(consumer, registry, "findByClass, findById, findAll")
  Rel(consumer, events, "@ObservesAsync StateChangeEvent")
  Rel(consumer, events, "dispatches DeviceCommand")
  Rel(registry, provider, "discover() at startup")
  Rel(haprov, provider, "implements DeviceProvider")
  Rel(ohprov, provider, "implements DeviceProvider")
  Rel(mock, provider, "implements DeviceProvider")
  Rel(bridge, events, "forwards StateChangeEvent, relays DeviceCommand")
```

### Module Structure

```
casehub-iot/
├── iot-api/              L1 + L2: Core types + SPI (public API, semver)
│   └── src/main/java/io/casehub/iot/
│       ├── api/          L1: DeviceEntity, DeviceClass, typed subclasses, value types
│       └── spi/          L2: CdiDeviceRegistry, NoOpDeviceRegistry
├── iot-homeassistant/    L3: Home Assistant provider
│   └── src/main/java/io/casehub/iot/homeassistant/
├── iot-openhab/          L4: OpenHAB provider
│   └── src/main/java/io/casehub/iot/openhab/
├── iot-testing/          L5: Test infrastructure (test-scope only)
│   └── src/main/java/io/casehub/iot/testing/
├── iot-bridge/           L6: Bridge runtime
│   └── src/main/java/io/casehub/iot/bridge/
└── pom.xml               Parent POM
```

---

## §6 Runtime View

### Scenario 1 — State Change Propagation (Home Assistant)

A light toggled in Home Assistant surfaces as a typed StateChangeEvent to application consumers.

```mermaid
sequenceDiagram
  participant HA as Home Assistant
  participant WS as HA WebSocket Client
  participant Prov as HomeAssistantProvider
  participant Map as EntityMapper
  participant CDI as CDI Event Bus
  participant App as @ObservesAsync Consumer

  HA->>WS: state_changed event (old_state, new_state)
  WS->>Prov: onStateChanged(event)
  Prov->>Map: map(new_state) → LightDevice
  Prov->>Map: map(old_state) → LightDevice
  Prov->>Prov: diff(old_state, new_state) → changedCapabilities {"isOn"}
  Prov->>CDI: Event<StateChangeEvent>.fireAsync(before, after, changedCapabilities)
  CDI->>App: @ObservesAsync StateChangeEvent
```

### Scenario 2 — Device Command Dispatch

Application sends a command; provider maps it to the platform-specific API call.

```mermaid
sequenceDiagram
  participant App as Application
  participant Reg as CdiDeviceRegistry
  participant Prov as HomeAssistantProvider
  participant HA as Home Assistant

  App->>Reg: findById(deviceId) → LightDevice
  App->>Prov: dispatch(DeviceCommand{targetDeviceId, "turn_on", {brightness: 200}})
  Prov->>Prov: map "turn_on" + params → domain=light, service=turn_on
  Prov->>HA: POST /api/services/light/turn_on {entity_id, brightness: 200}
  HA-->>Prov: 200 OK
  Prov-->>App: CommandResult.SENT
```

### Scenario 3 — Device Discovery Lifecycle

At startup, CdiDeviceRegistry discovers all providers and populates the device map.

```mermaid
sequenceDiagram
  participant CDI as CDI Container
  participant Reg as CdiDeviceRegistry
  participant HA as HomeAssistantProvider
  participant OH as OpenHABProvider

  CDI->>Reg: @PostConstruct
  Reg->>HA: discover()
  HA->>HA: GET /api/states → map entities
  HA-->>Reg: Uni<List<DeviceEntity>>
  Reg->>OH: discover()
  OH->>OH: GET /rest/items?tags=Equipment → map Equipment Groups
  OH-->>Reg: Uni<List<DeviceEntity>>
  Reg->>Reg: merge into device map (keyed by deviceId)
  Note over Reg: findById, findByClass, findAll ready
```

### Scenario 4 — OpenHAB State Cache Coalescing

Multiple item-level events for the same Equipment Group coalesce into one StateChangeEvent.

```mermaid
sequenceDiagram
  participant OH as OpenHAB
  participant SSE as SSE Client
  participant Prov as OpenHABProvider
  participant Cache as DeviceStateCache
  participant CDI as CDI Event Bus

  OH->>SSE: ItemStateChangedEvent (temperature item)
  SSE->>Prov: onItemChanged("livingroom_temp", "21.5", "22.0")
  Prov->>Cache: update("LivingRoom_Thermostat", currentTemperature=22.0)
  Note over Cache: start 50ms coalescing window

  OH->>SSE: ItemStateChangedEvent (target temp item, 10ms later)
  SSE->>Prov: onItemChanged("livingroom_target", "20.0", "23.0")
  Prov->>Cache: update("LivingRoom_Thermostat", targetTemperature=23.0)

  Note over Cache: 50ms window expires
  Cache->>CDI: Event<StateChangeEvent>.fireAsync(before, after, {"currentTemperature", "targetTemperature"})
```

### Scenario 5 — Bridge Event Forwarding

Bridge observes local events and relays to cloud; cloud commands relayed back.

```mermaid
sequenceDiagram
  participant Prov as Local Provider
  participant CDI as CDI Event Bus
  participant Bridge as BridgeRuntime
  participant Cloud as Cloud CaseHub

  Prov->>CDI: Event<StateChangeEvent>.fireAsync()
  CDI->>Bridge: @ObservesAsync StateChangeEvent
  Bridge->>Bridge: serialize to JSON
  Bridge->>Cloud: WebSocket send(stateChangeEvent)

  Cloud->>Bridge: WebSocket receive(DeviceCommand)
  Bridge->>Bridge: deserialize DeviceCommand
  Bridge->>Prov: dispatch(DeviceCommand)
  Prov-->>Bridge: CommandResult
  Bridge->>Cloud: WebSocket send(CommandResult)
```

---

## §7 Deployment View

### Mode 1 — Local

Provider connects directly to HA/OpenHAB on the same machine or local network. No internet dependency. All processing on-premises.

```
┌──────────────────────────────────┐
│         Local Machine            │
│  ┌──────────┐  ┌──────────────┐  │
│  │ casehub  │──│ iot-api +    │  │
│  │  -life   │  │ iot-ha / oh  │  │
│  └──────────┘  └──────┬───────┘  │
│                       │          │
│              ┌────────▼────────┐ │
│              │ HA / OpenHAB    │ │
│              └─────────────────┘ │
└──────────────────────────────────┘
```

### Mode 2 — Bridge (SaaS)

`iot-bridge` runs locally. Connects to HA/OpenHAB locally. Forwards StateChangeEvents to cloud CaseHub via WebSocket. Relays DeviceCommands back.

```
┌───────────────────┐          ┌──────────────────┐
│   Local Machine   │          │  Cloud CaseHub   │
│ ┌───────────────┐ │   WSS    │ ┌──────────────┐ │
│ │  iot-bridge   │─┼──────────┼─│ casehub-life │ │
│ └───────┬───────┘ │          │ └──────────────┘ │
│         │         │          └──────────────────┘
│ ┌───────▼───────┐ │
│ │ HA / OpenHAB  │ │
│ └───────────────┘ │
└───────────────────┘
```

### Mode 3 — Hybrid

Bridge runs Drools locally for latency-sensitive reactions. StateChangeEvents also forwarded to cloud for orchestration, optimization, HITL, ledger, and memory.

```properties
casehub.iot.bridge.cloud-endpoint=wss://casehub.io/bridge
casehub.iot.bridge.tenant-id=<tenancy-id>
casehub.iot.bridge.token=<bridge-auth-token>
casehub.iot.bridge.local-automations=security-alert,presence-lights
casehub.iot.bridge.cloud-automations=energy-optimization,morning-routine
```

### Build Commands

```bash
mvn --batch-mode install                     # Build all modules
mvn --batch-mode deploy -DskipTests          # Publish to GitHub Packages (CI only)
```

---

## §8 Crosscutting Concepts

### Governing Protocols and References

| Concern | Protocol / Reference |
|---|---|
| Module structure | `docs/protocols/universal/module-tier-structure.md` |
| CDI provider discovery | `@Any Instance<DeviceProvider>` — Quarkus CDI standard |
| Async events | CDI `Event.fireAsync()` / `@ObservesAsync` |
| Device class vocabulary | [Matter Device Type Library](https://csa-iot.org/developer-resource/specifications-download-request/) |
| Vendor supplement pattern | Extends common type only for fields with no cross-vendor equivalent |
| Connection lifecycle | Exponential backoff: base 5s, max 5min, ProviderStatusEvent on transitions |

### Vendor Supplement Pattern

Common device types carry fields mappable from both HA and OpenHAB. Fields with no cross-vendor equivalent belong in vendor supplement subclasses only.

**Test:** "Does this field have a meaningful mapping in both HA and OpenHAB?" If yes → common type. If no → supplement type in the vendor module.

Examples:
- `brightness` → both HA and OpenHAB expose brightness as 0–255. Common type.
- `rgbColor` → HA uses RGB; OpenHAB uses HSB. No common representation. Supplement types: `HomeAssistantLight.rgbColor()`, `OpenHABColorItem.hsb()`.
- `hvacAction` → HA-specific concept ("heating", "cooling", "idle"). No OpenHAB equivalent. Supplement: `HomeAssistantThermostat.hvacAction()`.

### changedCapabilities Population

`StateChangeEvent.changedCapabilities` identifies which device fields changed — consumers pattern-match without full diffing.

- **OpenHAB:** populated directly from the item event — the event names the specific item that changed, and the provider maps item to capability.
- **Home Assistant:** populated by diffing `old_state` and `new_state` attribute maps (HA events include previous state).

This preserves OpenHAB's field-level precision and makes HA produce the same granularity.

### Anti-patterns

**Leaky abstraction — vendor concept in common type**

- **Symptom:** Common `LightDevice` has `rgbColor()`. OpenHAB implementation returns null because OpenHAB uses HSB.
- **Cause:** Field placed in common type without verifying cross-vendor mapping.
- **Fix:** Only fields mappable from both HA and OpenHAB belong in common types. HA RGB → `HomeAssistantLight.rgbColor()`. OpenHAB HSB → `OpenHABColorItem.hsb()`.

**Blocking I/O on CDI event thread**

- **Symptom:** `@ObservesAsync StateChangeEvent` handler performs REST call; other event handlers starve.
- **Cause:** CDI async events share a thread pool. Blocking calls consume pool threads.
- **Fix:** Event handlers delegate I/O to `Uni<>` or a separate executor. Keep event handling non-blocking.

**Stale device from registry after state change**

- **Symptom:** Consumer reads DeviceEntity from registry immediately after receiving StateChangeEvent; gets pre-change state.
- **Cause:** Registry update and event fire are not atomic. Consumer reads between provider receiving platform event and registry update completing.
- **Fix:** CdiDeviceRegistry updates device map before provider fires StateChangeEvent. Consumers should use `event.after()`, not re-read from registry.

**Command dispatch to disconnected provider**

- **Symptom:** DeviceCommand dispatched to DISCONNECTED provider; `Uni` completes with TIMEOUT after delay.
- **Cause:** Application dispatches without checking `ProviderStatus`.
- **Fix:** Provider `dispatch()` returns `CommandResult.FAILED` immediately when status is DISCONNECTED. Application may also pre-check `provider.status()`.

---

## §9.1 Journey Overview

| Journey | Description | Chapters | Status |
|---|---|---|---|
| IoT Device Abstraction | From bare scaffold to deployable, tested, multi-platform device integration layer | 5 | Not started |

```mermaid
flowchart LR
  C1["C1: Core API\nL1, L2"] --> C2["C2: Test Infrastructure\n+ L5"]
  C2 --> C3["C3: Home Assistant\n+ L3"]
  C3 --> C4["C4: OpenHAB\n+ L4"]
  C4 --> C5["C5: Bridge\n+ L6"]

  style C1 fill:#D3D3D3,color:#000
  style C2 fill:#D3D3D3,color:#000
  style C3 fill:#D3D3D3,color:#000
  style C4 fill:#D3D3D3,color:#000
  style C5 fill:#D3D3D3,color:#000
```

---

## §9.2 Chapter Index

| # | Chapter | Journey | Layers touched | Delta summary | Status |
|---|---|---|---|---|---|
| 1 | Core API | IoT Device Abstraction | L1, L2 | High, High | 🔲 |
| 2 | Test Infrastructure | IoT Device Abstraction | + L5 | High | 🔲 |
| 3 | Home Assistant | IoT Device Abstraction | + L3 | High | 🔲 |
| 4 | OpenHAB | IoT Device Abstraction | + L4 | High | 🔲 |
| 5 | Bridge | IoT Device Abstraction | + L6 | High | 🔲 |

### Layer x Chapter Matrix

| Layer | C1 | C2 | C3 | C4 | C5 |
|---|---|---|---|---|---|
| L1 Device Type System | High | Low | Low | Low | — |
| L2 SPI & Registry | High | Low | Low | Low | Low |
| L3 Home Assistant | — | — | High | — | Low |
| L4 OpenHAB | — | — | — | High | Low |
| L5 Test Harness | — | High | Low | Low | — |
| L6 Bridge | — | — | — | — | High |

L1 and L2 appear in C1–C4: foundational — real platform data in C3/C4 may surface type adjustments or SPI refinements. L5 appears in C2–C4: test harness grows as providers add fixtures. L3, L4, L6 are additive single-column entries — stable after introduction.

### Sequencing Rationale

- C1 before C2: Core API provides the types and SPIs that test infrastructure implements (hard — MockDeviceProvider implements DeviceProvider)
- C2 before C3: MockDeviceProvider validates SPI contract; test infrastructure enables TDD of real providers (hard — contract must be validated before real provider work)
- C3 before C4: HA is simpler (no state cache, no semantic model) — validates SPI against real platform data before OpenHAB's higher complexity (minimal delta — one platform concern vs two)
- C4 after C3: OpenHAB state cache coalescing and semantic model mapping informed by HA implementation experience (soft — C4 could technically proceed independently)
- C5 after C3/C4: Bridge requires at least one working provider for integration testing (hard — bridge forwards events from real providers)

---

## §9.3 Chapter Entries

### Chapter 1 — Core API

**Journey:** IoT Device Abstraction | **Sequence:** 1 of 5 | **Status:** 🔲
**Issues:** #1

**What this delivers**

`iot-api` module — the full public API surface. 10 typed device classes with cross-vendor fields, DeviceProvider SPI for platform integration, DeviceRegistry for device lookup, StateChangeEvent for state propagation, DeviceCommand for control dispatch. After this chapter, consumers can write automation code against the type hierarchy and SPI contracts.

**Accountability gaps closed**
- No common device vocabulary → DeviceEntity hierarchy + DeviceClass enum (L1)
- No provider contract → DeviceProvider SPI + CdiDeviceRegistry (L2)
- No platform-agnostic events → StateChangeEvent + DeviceCommand (L2)

**Layer Impact**

| Layer | Delta |
|---|---|
| L1 Device Type System | High — DeviceEntity base, DeviceClass enum, 10 typed subclasses, Temperature, ThermostatMode |
| L2 SPI & Registry | High — DeviceProvider, DeviceRegistry, CdiDeviceRegistry, NoOpDeviceRegistry, StateChangeEvent, DeviceCommand, CommandResult, ProviderStatus, ProviderStatusEvent |

---

### Chapter 2 — Test Infrastructure

**Journey:** IoT Device Abstraction | **Sequence:** 2 of 5 | **Status:** 🔲
**Issues:** #2

**What this delivers**

`iot-testing` module — MockDeviceProvider for programmatic device registration and manual StateChangeEvent firing, TestDeviceRegistry with pre-populated fixture device sets, StateChangeEventPublisher test helper that auto-derives changedCapabilities. After this chapter, downstream consumers (casehub-life) can write device-dependent tests without a live platform.

**Accountability gaps closed**
- No way to test device-dependent code → MockDeviceProvider + TestDeviceRegistry (L5)
- No repeatable device sets → fixture YAML (L5)
- No test event publishing → StateChangeEventPublisher (L5)

**Layer Impact**

| Layer | Delta |
|---|---|
| L5 Test Harness | High — MockDeviceProvider, TestDeviceRegistry, StateChangeEventPublisher, home-standard-fixtures.yaml |
| L1 Device Type System | Low — validate type hierarchy under fixture construction |
| L2 SPI & Registry | Low — validate SPI contract against first real implementation |

---

### Chapter 3 — Home Assistant

**Journey:** IoT Device Abstraction | **Sequence:** 3 of 5 | **Status:** 🔲
**Issues:** #3

**What this delivers**

`iot-homeassistant` module — REST-based device discovery via `/api/states`, WebSocket state subscription with changedCapabilities diffing, command dispatch via service calls, connection lifecycle with exponential backoff reconnection, HA supplement types for unmappable fields. After this chapter, devices on a Home Assistant instance are discoverable, observable, and controllable through the iot-api abstraction.

**Accountability gaps closed**
- No real platform integration → HomeAssistantProvider discovers and controls HA devices (L3)
- No live state subscription → WebSocket `state_changed` events fire StateChangeEvent (L3)
- No HA-specific fields → HomeAssistantThermostat, HomeAssistantLight, HomeAssistantLock supplement types (L3)

**Layer Impact**

| Layer | Delta |
|---|---|
| L3 Home Assistant | High — HomeAssistantProvider, REST client, WebSocket client, entity mapper, 3 supplement types, connection lifecycle |
| L1 Device Type System | Low — type adjustments from real HA entity mapping |
| L2 SPI & Registry | Low — SPI refinements from first real provider implementation |
| L5 Test Harness | Low — HA-specific test fixtures |

---

### Chapter 4 — OpenHAB

**Journey:** IoT Device Abstraction | **Sequence:** 4 of 5 | **Status:** 🔲
**Issues:** #4

**What this delivers**

`iot-openhab` module — semantic model discovery from Equipment Groups with Point items, SSE state subscription with 50ms coalescing window, Equipment-keyed state cache, command dispatch via item commands, OH supplement types. After this chapter, devices on an OpenHAB instance (with semantic model configured) are discoverable, observable, and controllable through the same iot-api abstraction.

**Accountability gaps closed**
- OpenHAB users excluded → OpenHABProvider discovers and controls OH devices (L4)
- No semantic model integration → Equipment Group mapping with Point resolution (L4)
- Item-level events need assembly → State cache coalesces item events into device-level StateChangeEvent (L4)
- No OH-specific fields → OpenHABThermostatItem, OpenHABRollershutter, OpenHABColorItem supplement types (L4)

**Layer Impact**

| Layer | Delta |
|---|---|
| L4 OpenHAB | High — OpenHABProvider, REST client, SSE client, semantic model mapper, state cache with 50ms coalescing, 3 supplement types |
| L1 Device Type System | Low — type adjustments from real OH entity mapping |
| L2 SPI & Registry | Low — SPI refinements from second provider implementation |
| L5 Test Harness | Low — OH-specific test fixtures |

---

### Chapter 5 — Bridge

**Journey:** IoT Device Abstraction | **Sequence:** 5 of 5 | **Status:** 🔲
**Issues:** #5

**What this delivers**

`iot-bridge` module — lightweight Quarkus app that runs locally, connects to HA/OpenHAB via local providers, forwards StateChangeEvents to cloud CaseHub via WebSocket, relays DeviceCommands back. Supports hybrid mode with selectable local vs cloud automations. After this chapter, CaseHub deployments can operate in SaaS or hybrid mode without requiring co-located compute.

**Accountability gaps closed**
- Local deployment only → Bridge enables SaaS mode with cloud CaseHub (L6)
- No hybrid option → Hybrid mode runs local automations for latency-sensitive reactions (L6)

**Layer Impact**

| Layer | Delta |
|---|---|
| L6 Bridge | High — BridgeRuntime, WebSocket cloud client, event serialization, command relay, hybrid mode configuration |
| L2 SPI & Registry | Low — registry access for command routing |
| L3 Home Assistant | Low — integration testing with HA provider |
| L4 OpenHAB | Low — integration testing with OH provider |

---

## §9.4 Layer Entries

### Layer — Device Type System

**Participates in chapters:** C1, C2, C3, C4
**Architectural patterns:** Type Hierarchy (abstract base + typed subclasses), Value Objects (Temperature)
**Key protocols:** Matter Device Type Library alignment
**Design refs:** `docs/superpowers/specs/2026-06-05-iot-foundation-design.md` §Core Abstractions — DeviceEntity hierarchy
**Issues:** casehubio/casehub-iot#1
**Navigation:** `git log --grep="#1" --oneline`
**Blog:** 🔲
**Improvement refs:** 🔲
**Completed:** 🔲 pending

#### What it adds

**Before:** No device vocabulary exists — each platform uses its own entity model.
**After:** `DeviceEntity` abstract base with 10 typed subclasses. `DeviceClass` enum aligned with Matter Device Type Library.

- **Typed device hierarchy** — `SwitchDevice.isOn()`, `LightDevice.brightness()`, `ThermostatDevice.currentTemperature()` — strongly typed access replaces string-keyed attribute maps
- **Cross-vendor common fields** — only fields mappable from both HA and OpenHAB appear in common types
- **Value types** — `Temperature` with unit-safe representation; `ThermostatMode` enum (HEAT/COOL/AUTO/OFF/FAN_ONLY)

Not closed here: provider contract (L2), platform integration (L3/L4), test infrastructure (L5).

#### Accountability gaps closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| No common device vocabulary | Each consumer writes platform-specific parsing | DeviceEntity hierarchy with 10 typed subclasses |
| No device categorization | Consumer code uses string matching on entity IDs | DeviceClass enum aligned with Matter |
| Temperature unit ambiguity | Celsius/Fahrenheit confusion across platforms | Temperature value type with explicit unit |

#### Key files (planned)

- `iot-api/src/main/java/io/casehub/iot/api/DeviceEntity.java` — abstract base: deviceId, deviceClass, label, available, lastUpdated, tenancyId
- `iot-api/src/main/java/io/casehub/iot/api/DeviceClass.java` — enum: SWITCH, LIGHT, THERMOSTAT, SENSOR, PRESENCE_SENSOR, POWER_SENSOR, LOCK, COVER, MEDIA_PLAYER, FAN
- `iot-api/src/main/java/io/casehub/iot/api/SwitchDevice.java` — isOn()
- `iot-api/src/main/java/io/casehub/iot/api/LightDevice.java` — isOn(), brightness(), colorTemp()
- `iot-api/src/main/java/io/casehub/iot/api/ThermostatDevice.java` — currentTemperature(), targetTemperature(), mode()
- `iot-api/src/main/java/io/casehub/iot/api/SensorDevice.java` — numericValue(), unit(), binaryValue()
- `iot-api/src/main/java/io/casehub/iot/api/PresenceSensor.java` — isPresent(), lastSeen()
- `iot-api/src/main/java/io/casehub/iot/api/PowerSensor.java` — power(), energy()
- `iot-api/src/main/java/io/casehub/iot/api/LockDevice.java` — isLocked()
- `iot-api/src/main/java/io/casehub/iot/api/CoverDevice.java` — position(), isMoving()
- `iot-api/src/main/java/io/casehub/iot/api/MediaPlayerDevice.java` — isPlaying(), volume()
- `iot-api/src/main/java/io/casehub/iot/api/FanDevice.java` — isOn(), speed()
- `iot-api/src/main/java/io/casehub/iot/api/Temperature.java` — value type with unit
- `iot-api/src/main/java/io/casehub/iot/api/ThermostatMode.java` — enum: HEAT, COOL, AUTO, OFF, FAN_ONLY

#### Key wiring

🔲 at C1 close.

#### Architectural decisions

- **Why abstract class hierarchy, not interfaces:** Device types need shared state (deviceId, label, available, lastUpdated, tenancyId). Interfaces require default methods or delegation for state — neither composes well across 10 subtypes with a third level of vendor supplements. The hierarchy IS a taxonomy — "a light IS a device" is genuinely IS-A.
- **Why Matter Device Type Library alignment:** Industry-standard categorization. Prevents inventing a custom vocabulary that diverges from the ecosystem. HA and OpenHAB both have their own vocabularies; Matter is the neutral ground.

#### Pattern introduced

**Typed device class hierarchy with vendor supplement extension:** Abstract base → typed subtype → optional vendor supplement subclass. Each level adds fields; no level duplicates the level above.

#### Pattern anchor

- `DeviceEntity` — abstract base with common fields
- `LightDevice` — typed subclass example (isOn, brightness, colorTemp)
- `HomeAssistantLight extends LightDevice` — supplement subclass example (rgbColor, effect)

#### Gotchas

🔲 at C1 close.

#### Pattern to replicate

1. Define an abstract base entity class with fields common to all instances: stable ID, classification enum, display label, availability, last-update timestamp, tenant scope.
2. Create a classification enum aligned with a relevant industry standard (Matter for IoT, ICD for clinical, ISIC for business).
3. Define typed subclasses — one per classification — each adding strongly typed accessor methods for the fields that classification carries.
4. Use `Optional<>` for fields not all instances of a classification carry (e.g. `brightness` on a light that only supports on/off).
5. Place all types in a standalone module with zero framework dependency — pure Java.
6. Value types (Temperature, Currency) carry explicit units; no raw numeric fields with implicit units.

---

### Layer — SPI & Registry

**Participates in chapters:** C1, C2, C3, C4, C5
**Architectural patterns:** Hexagonal (SPI ports), CDI Instance<> discovery, Async Events, DefaultBean
**Key protocols:** CDI `Event.fireAsync()` / `@ObservesAsync`, `@Any Instance<>` discovery
**Design refs:** `docs/superpowers/specs/2026-06-05-iot-foundation-design.md` §DeviceProvider SPI, §DeviceRegistry SPI, §StateChangeEvent
**Issues:** casehubio/casehub-iot#1
**Navigation:** `git log --grep="#1" --oneline`
**Blog:** 🔲
**Improvement refs:** 🔲
**Completed:** 🔲 pending

#### What it adds

**Before:** Device types exist (L1) but no way to discover, control, or observe them.
**After:** `DeviceProvider` SPI for platform integration; `DeviceRegistry` SPI for device lookup; `StateChangeEvent` for state propagation; `DeviceCommand` for control. `CdiDeviceRegistry` discovers all providers at startup.

- **DeviceProvider SPI** — `discover()`, `dispatch()`, `status()` with Uni<> async contract
- **DeviceRegistry SPI** — `findById()`, `findByClass()`, `findAll()`, `refresh()` with CdiDeviceRegistry default
- **StateChangeEvent** — before/after DeviceEntity, changedCapabilities, occurredAt, providerId
- **DeviceCommand** — targetDeviceId, action, parameters, dispatchedBy, correlationId
- **ProviderStatusEvent** — fired via CDI on CONNECTED/CONNECTING/DISCONNECTED transitions

Not closed here: actual platform providers (L3/L4), test mocks (L5), bridge relay (L6).

#### Accountability gaps closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| No standard provider contract | Each platform integration is bespoke | DeviceProvider SPI — single interface all platforms implement |
| No device lookup | Consumers maintain their own device maps | DeviceRegistry + CdiDeviceRegistry auto-discovers providers |
| No state change propagation | Consumers poll or use platform-specific subscription | StateChangeEvent via CDI Event.fireAsync() |
| No command abstraction | Consumers call platform REST APIs directly | DeviceCommand dispatched through DeviceProvider |
| No provider lifecycle visibility | Disconnection undetected | ProviderStatusEvent on status transitions |

#### Key files (planned)

- `iot-api/src/main/java/io/casehub/iot/api/DeviceProvider.java` — SPI: providerId(), discover(), dispatch(), status()
- `iot-api/src/main/java/io/casehub/iot/api/DeviceRegistry.java` — SPI: findById(), findByClass(), findAll(), refresh()
- `iot-api/src/main/java/io/casehub/iot/api/StateChangeEvent.java` — record: before, after, changedCapabilities, occurredAt, providerId
- `iot-api/src/main/java/io/casehub/iot/api/DeviceCommand.java` — record: targetDeviceId, action, parameters, dispatchedBy, correlationId
- `iot-api/src/main/java/io/casehub/iot/api/CommandResult.java` — enum: SENT, FAILED, TIMEOUT
- `iot-api/src/main/java/io/casehub/iot/api/ProviderStatus.java` — enum: CONNECTED, CONNECTING, DISCONNECTED
- `iot-api/src/main/java/io/casehub/iot/api/ProviderStatusEvent.java` — record: providerId, previousStatus, currentStatus
- `iot-api/src/main/java/io/casehub/iot/spi/CdiDeviceRegistry.java` — @ApplicationScoped @DefaultBean; discovers providers via @Any Instance<DeviceProvider>
- `iot-api/src/main/java/io/casehub/iot/spi/NoOpDeviceRegistry.java` — @ApplicationScoped @DefaultBean; empty registry for test/tutorial

#### Key wiring

- **`CdiDeviceRegistry` discovers providers via `@Any Instance<DeviceProvider>`** — all `@ApplicationScoped` DeviceProvider beans are collected at startup. `discover()` called on each; results merged by deviceId.
- **`NoOpDeviceRegistry` for zero-provider deployments** — `@DefaultBean` active when no provider module is on the classpath. Returns empty collections from all methods.
- **`Uni<>` on provider methods** — provider operations are I/O-bound (REST calls, WebSocket sends). Blocking signatures would stall the CDI container during discovery.
- **`Event.fireAsync()` for StateChangeEvent** — observers discovered at startup. No subscription management. Standard Quarkus pattern. No backpressure — acceptable for IoT event frequency.

#### Architectural decisions

- **Why CDI Event.fireAsync, not reactive streams:** IoT state changes are infrequent (seconds between changes). CDI events have no subscription management overhead and integrate with Quarkus observer lifecycle. Reactive streams add backpressure machinery unnecessary at this frequency. If high-frequency sensors emerge, batching at the provider level is the mitigation.
- **Why Uni<> on provider methods, not CompletableFuture:** Quarkus native. Mutiny integrates with Quarkus REST client and WebSocket client without bridging. CompletableFuture requires `.subscribeAsCompletionStage()` conversion at every call site.
- **Why CdiDeviceRegistry as @DefaultBean:** Application-tier consumers may provide a custom DeviceRegistry (e.g. with caching, filtering, or room grouping). `@DefaultBean` is displaced when a custom implementation exists — standard CaseHub CDI pattern.

#### Pattern introduced

**SPI + CDI discovery with async events:** SPI interface defines the contract; CDI `Instance<>` discovers implementations; events propagate state changes without coupling consumer to provider.

#### Pattern anchor

- `DeviceProvider` — SPI interface
- `CdiDeviceRegistry` — CDI Instance<> discovery + @DefaultBean

#### Gotchas

🔲 at C1 close.

#### Pattern to replicate

1. Define a provider SPI interface with `discover()` returning `Uni<List<T>>`, `dispatch()` returning `Uni<Result>`, and `status()` returning synchronous status enum.
2. Define a registry SPI interface with `findById`, `findByClass`, `findAll`, `refresh`.
3. Implement a default CDI registry that injects `@Any Instance<Provider>`, calls `discover()` on each at startup, and maintains an in-memory map keyed by stable ID.
4. Provide a `NoOpRegistry` `@DefaultBean` for zero-provider deployments.
5. Define a state change record carrying before/after entity, changed field names, timestamp, and provider ID.
6. Fire state changes via `Event.fireAsync()`; document that consumers use `@ObservesAsync`.
7. Define a command record carrying target ID, action string, parameters map, caller ID, and correlation ID.

---

### Layer — Home Assistant

**Participates in chapters:** C3, C5
**Architectural patterns:** REST Client, WebSocket Client, Strategy (entity mapping), Reconnection (exponential backoff)
**Key protocols:** HA REST API v2, HA WebSocket API
**Design refs:** `docs/superpowers/specs/2026-06-05-iot-foundation-design.md` §Home Assistant provider
**Issues:** casehubio/casehub-iot#3
**Navigation:** `git log --grep="#3" --oneline`
**Blog:** 🔲
**Improvement refs:** 🔲
**Completed:** 🔲 pending

#### What it adds

**Before:** SPI contract and test infrastructure exist; no real platform connects.
**After:** `HomeAssistantProvider` discovers devices via REST, subscribes to state changes via WebSocket, dispatches commands via service calls.

- **REST discovery** — `GET /api/states` maps HA domain + `device_class` to common type hierarchy
- **WebSocket subscription** — `/api/websocket` subscribes to `state_changed`; diff old_state/new_state produces changedCapabilities
- **Command dispatch** — `POST /api/services/{domain}/{service}` with entity_id and parameters mapped from DeviceCommand
- **Connection lifecycle** — `@PostConstruct` connect; exponential backoff reconnection (base 5s, max 5min); ProviderStatusEvent on transitions
- **Supplement types** — HomeAssistantThermostat (presetMode, swingMode, hvacAction), HomeAssistantLight (rgbColor, effect, supportedColorModes), HomeAssistantLock (changedBy, codeSlot)

Not closed here: OpenHAB support (L4), bridge relay (L6).

#### Accountability gaps closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| No real platform integration | SPI contract is untested against real data | HomeAssistantProvider implements DeviceProvider against live HA |
| No live state subscription | Consumers poll or miss state changes | WebSocket state_changed → StateChangeEvent via CDI |
| No HA-specific field access | RGB color, HVAC action inaccessible from common types | 3 HA supplement types with typed accessors |
| Silent disconnection | HA WebSocket drops; no notification | ProviderStatusEvent on DISCONNECTED; reconnection with backoff |

#### Key files (planned)

- `iot-homeassistant/src/main/java/io/casehub/iot/homeassistant/HomeAssistantProvider.java` — @ApplicationScoped DeviceProvider
- `iot-homeassistant/src/main/java/io/casehub/iot/homeassistant/HomeAssistantRestClient.java` — Quarkus REST client for /api/states and /api/services
- `iot-homeassistant/src/main/java/io/casehub/iot/homeassistant/HomeAssistantWebSocketClient.java` — WebSocket Next client for /api/websocket
- `iot-homeassistant/src/main/java/io/casehub/iot/homeassistant/HomeAssistantEntityMapper.java` — maps HA entity JSON → DeviceEntity subtype
- `iot-homeassistant/src/main/java/io/casehub/iot/homeassistant/HomeAssistantThermostat.java` — extends ThermostatDevice: presetMode, swingMode, hvacAction
- `iot-homeassistant/src/main/java/io/casehub/iot/homeassistant/HomeAssistantLight.java` — extends LightDevice: rgbColor, effect, supportedColorModes
- `iot-homeassistant/src/main/java/io/casehub/iot/homeassistant/HomeAssistantLock.java` — extends LockDevice: changedBy, codeSlot

#### Key wiring

- **Long-lived access token authentication** — `Authorization: Bearer <token>` header on REST calls; `auth` message on WebSocket connect.
- **WebSocket authentication handshake** — HA WebSocket requires an `auth` message immediately after connect; provider must complete auth before subscribing to `state_changed`.
- **HA domain-to-DeviceClass mapping** — `light` → LIGHT, `switch` → SWITCH, `climate` → THERMOSTAT, `sensor` → SENSOR (refined by `device_class` attribute), `binary_sensor` with `device_class=motion/occupancy` → PRESENCE_SENSOR, `lock` → LOCK, `cover` → COVER, `media_player` → MEDIA_PLAYER, `fan` → FAN.
- **changedCapabilities diffing** — compare old_state and new_state attribute maps key by key; changed keys mapped to capability names (e.g. `brightness` attribute → `"brightness"` capability).

#### Architectural decisions

- **Why REST for discovery, WebSocket for subscription:** HA REST API returns all entities in one call — efficient for initial population. WebSocket API pushes individual state changes in real time — no polling. Different transport for different access pattern.
- **Why diff-based changedCapabilities rather than HA event metadata:** HA `state_changed` events include old_state but do not identify which specific attributes changed. Diffing is the only way to populate changedCapabilities with the same granularity OpenHAB provides natively.

#### Pattern introduced

**REST discovery + WebSocket subscription + diff-based changedCapabilities:** Initial device map built from REST snapshot. Live state changes received via WebSocket push. Field-level change tracking derived from attribute map diffing.

#### Pattern anchor

- `HomeAssistantProvider.discover()` — REST-based discovery
- `HomeAssistantWebSocketClient.onStateChanged()` — WebSocket subscription + diffing

#### Gotchas

🔲 at C3 close.

#### Pattern to replicate

1. Create a `@ApplicationScoped` provider implementing the DeviceProvider SPI.
2. Inject platform configuration (URL, token) via `@ConfigProperty`.
3. Implement `discover()` as a REST GET that returns all entities; map each to the typed device hierarchy.
4. Implement state subscription via the platform's push mechanism (WebSocket, SSE, MQTT). On each event, map to DeviceEntity, compute changedCapabilities, fire StateChangeEvent.
5. Implement `dispatch()` by mapping DeviceCommand action + parameters to the platform's command API.
6. Add `@PostConstruct` connection with exponential backoff reconnection (base 5s, max 5min).
7. Fire ProviderStatusEvent on CONNECTED/CONNECTING/DISCONNECTED transitions.
8. Create supplement subclasses for fields with no cross-vendor mapping.

---

### Layer — OpenHAB

**Participates in chapters:** C4, C5
**Architectural patterns:** REST Client, SSE Stream, State Cache with Coalescing, Semantic Model Mapping
**Key protocols:** OpenHAB REST API, OpenHAB SSE API, OpenHAB Semantic Model
**Design refs:** `docs/superpowers/specs/2026-06-05-iot-foundation-design.md` §OpenHAB provider
**Issues:** casehubio/casehub-iot#4
**Navigation:** `git log --grep="#4" --oneline`
**Blog:** 🔲
**Improvement refs:** 🔲
**Completed:** 🔲 pending

#### What it adds

**Before:** HA provider works; OpenHAB users excluded.
**After:** `OpenHABProvider` discovers devices via semantic model, subscribes via SSE, dispatches via item commands. State cache coalesces item-level events into device-level StateChangeEvent.

- **Semantic model discovery** — `GET /rest/items?tags=Equipment&recursive=true` maps Equipment Groups with Point items to common device hierarchy
- **SSE subscription** — `GET /rest/events?topics=openhab/items/*/statechanged` receives item-level events
- **State cache** — `Map<String, DeviceEntity>` keyed by Equipment Group name; multiple item events within 50ms coalesced into one StateChangeEvent
- **Command dispatch** — `POST /rest/items/{itemName}` with value mapped from DeviceCommand; target item resolved via semantic Point/Property tags
- **Supplement types** — OpenHABThermostatItem (heatingDemand, coolingDemand), OpenHABRollershutter (upDown), OpenHABColorItem (hsb)

Not closed here: bridge relay (L6).

#### Accountability gaps closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| OpenHAB users excluded from iot abstraction | Half the target user base unsupported | OpenHABProvider implements DeviceProvider for OH |
| No semantic model integration | OH items are flat; no device grouping | Equipment Group → DeviceEntity mapping with Point resolution |
| Item events are too granular | Individual item changes flood consumers | State cache coalesces within 50ms window into single StateChangeEvent |
| OH-specific concepts inaccessible | Heating demand, up/down, HSB color not in common types | 3 OH supplement types |

#### Key files (planned)

- `iot-openhab/src/main/java/io/casehub/iot/openhab/OpenHABProvider.java` — @ApplicationScoped DeviceProvider
- `iot-openhab/src/main/java/io/casehub/iot/openhab/OpenHABRestClient.java` — Quarkus REST client for /rest/items
- `iot-openhab/src/main/java/io/casehub/iot/openhab/OpenHABSseClient.java` — SSE client for /rest/events
- `iot-openhab/src/main/java/io/casehub/iot/openhab/SemanticModelMapper.java` — Equipment Group + Point items → DeviceEntity subtype
- `iot-openhab/src/main/java/io/casehub/iot/openhab/DeviceStateCache.java` — Equipment-keyed state cache with 50ms coalescing
- `iot-openhab/src/main/java/io/casehub/iot/openhab/OpenHABThermostatItem.java` — extends ThermostatDevice: heatingDemand, coolingDemand
- `iot-openhab/src/main/java/io/casehub/iot/openhab/OpenHABRollershutter.java` — extends CoverDevice: upDown
- `iot-openhab/src/main/java/io/casehub/iot/openhab/OpenHABColorItem.java` — extends LightDevice: hsb

#### Key wiring

- **Semantic model is a hard prerequisite** — without Equipment Groups, the provider cannot map items to devices. Document prominently. Thing-scoped discovery fallback is deferred.
- **Equipment Group → DeviceEntity mapping** — Equipment semantic tag determines DeviceClass. Member Point items provide state values. Each Equipment Group becomes one DeviceEntity.
- **50ms coalescing window** — when an item event arrives, schedule a 50ms timer for the owning Equipment Group. If another item event for the same Equipment arrives within the window, reset the timer and accumulate the change. Fire StateChangeEvent when the timer expires.
- **Command target resolution** — `DeviceCommand.action` + semantic Point/Property tags on member items determine which item receives the command value. E.g. `"set_temperature"` → find the member item with `Point_Setpoint` and `Property_Temperature`.

#### Architectural decisions

- **Why semantic model only (no Thing-scoped fallback):** Things map 1:1 to physical devices but lack the capability structure Equipment Groups provide. A Thing's channels are opaque strings; an Equipment Group's Point items carry semantic tags that map to the DeviceEntity capability model. Thing fallback adds significant complexity for a weaker mapping. Deferred.
- **Why 50ms coalescing, not immediate fire:** OpenHAB fires one SSE event per item state change. A thermostat setpoint adjustment changes both `currentTemperature` and `targetTemperature` — two events milliseconds apart. Without coalescing, consumers receive two StateChangeEvents for what is semantically one device state transition. 50ms is long enough to catch multi-item updates, short enough for responsive automation.

#### Pattern introduced

**Semantic model mapping + state cache coalescing:** Equipment Groups provide device structure; Point items provide capability values. Item-level events assembled and coalesced into device-level state changes via a timed coalescing cache.

#### Pattern anchor

- `SemanticModelMapper` — Equipment Group → DeviceEntity mapping
- `DeviceStateCache` — 50ms coalescing window

#### Gotchas

🔲 at C4 close.

#### Pattern to replicate

1. Identify the platform's device grouping concept (Equipment, Room, Thing Group). Map to the base entity.
2. Identify the platform's capability representation (Point items, Channels, Attributes). Map to typed fields.
3. Implement discovery by fetching grouped entities with their member capabilities in one call.
4. Implement state subscription via the platform's push mechanism (SSE, MQTT, WebSocket).
5. Build a state cache keyed by device group ID. On each member-level event, update the cache entry and start/reset a coalescing timer.
6. When the timer fires, assemble the complete DeviceEntity from cache, compute changedCapabilities from all accumulated member changes, fire StateChangeEvent.
7. For command dispatch, resolve the target member by matching the command action to semantic capability tags.
8. Make the coalescing window configurable — defaults will need adjustment for real deployments.

---

### Layer — Test Harness

**Participates in chapters:** C2, C3, C4
**Architectural patterns:** Mock/Stub, Fixture Factory, Test Helper
**Key protocols:** test-scope dependency only
**Design refs:** `docs/superpowers/specs/2026-06-05-iot-foundation-design.md` §Testing
**Issues:** casehubio/casehub-iot#2
**Navigation:** `git log --grep="#2" --oneline`
**Blog:** 🔲
**Improvement refs:** 🔲
**Completed:** 🔲 pending

#### What it adds

**Before:** No way to test device-dependent code without a live HA/OpenHAB instance.
**After:** `MockDeviceProvider` for programmatic device registration and manual event firing. `TestDeviceRegistry` with pre-populated fixture device sets. `StateChangeEventPublisher` auto-derives changedCapabilities from before/after comparison.

- **MockDeviceProvider** — `addDevice(DeviceEntity)`, `fireStateChange(before, after)`, `setStatus(ProviderStatus)` — programmatic control for tests
- **TestDeviceRegistry** — pre-populated from `home-standard-fixtures.yaml`; devices available immediately without calling `discover()`
- **StateChangeEventPublisher** — `publish(before, after)` derives changedCapabilities automatically by comparing field values; fires via CDI `Event.fireAsync()`

Not closed here: real platform integration (L3/L4), bridge (L6).

#### Accountability gaps closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| Device-dependent tests require live platform | CI pipeline needs HA/OH; flaky, slow, infrastructure-heavy | MockDeviceProvider — tests run without network or platform |
| No repeatable device sets | Each test constructs devices ad-hoc; inconsistent | TestDeviceRegistry + fixture YAML |
| Manual changedCapabilities assembly | Tests must compute changedCapabilities by hand; error-prone | StateChangeEventPublisher auto-derives from before/after comparison |

#### Key files (planned)

- `iot-testing/src/main/java/io/casehub/iot/testing/MockDeviceProvider.java` — implements DeviceProvider; programmatic device registration, manual event firing
- `iot-testing/src/main/java/io/casehub/iot/testing/TestDeviceRegistry.java` — implements DeviceRegistry; pre-populated from fixture YAML
- `iot-testing/src/main/java/io/casehub/iot/testing/StateChangeEventPublisher.java` — test helper: publish(before, after) with auto-derived changedCapabilities
- `iot-testing/src/main/resources/fixtures/home-standard-fixtures.yaml` — standard home device set

#### Key wiring

- **Test-scope only** — `<scope>test</scope>` in consumer pom.xml. Never a compile or runtime dependency.
- **MockDeviceProvider is not a CDI bean by default** — tests instantiate directly or register as a CDI bean via `@QuarkusTestResource`. This prevents conflict with real providers in integration tests.
- **StateChangeEventPublisher requires CDI Event injection** — operates within a `@QuarkusTest` CDI container to fire real async events.

#### Architectural decisions

- **Why fixture YAML rather than fixture Java builders:** YAML is readable, editable, and platform-agnostic. A non-developer can inspect and modify fixture device sets. Java builders are code — they require compilation and Java knowledge. YAML fixtures serve both test setup and documentation.
- **Why auto-derived changedCapabilities:** Manual changedCapabilities assembly is error-prone and diverges from production behavior where providers compute it automatically. The test helper mirrors production by comparing field values.

#### Pattern introduced

**Mock provider + fixture registry + auto-deriving event publisher:** Three test utilities covering the three SPI integration points: device discovery (mock provider), device lookup (fixture registry), state change observation (event publisher).

#### Pattern anchor

- `MockDeviceProvider.fireStateChange()` — programmatic state change triggering
- `StateChangeEventPublisher.publish()` — auto-deriving changedCapabilities

#### Gotchas

🔲 at C2 close.

#### Pattern to replicate

1. Create a mock provider implementing the provider SPI with methods for programmatic device registration (`addDevice`), manual state change firing (`fireStateChange`), and status control (`setStatus`).
2. Create a fixture registry pre-populated from a YAML file containing a standard device set for the domain.
3. Create a test event publisher that accepts before/after entity pairs, auto-derives the changed field set by reflection or explicit comparison, and fires via CDI Event.fireAsync().
4. Package as a separate module with no framework dependency beyond CDI (consumers add the dependency at test scope).
5. Document that the mock provider is not a CDI bean by default — tests register it explicitly to avoid conflicts with real providers.

---

### Layer — Bridge

**Participates in chapters:** C5
**Architectural patterns:** Event Relay, WebSocket Client, Deployment Mode Selection
**Key protocols:** WebSocket for cloud relay; TLS + auth token
**Design refs:** `docs/superpowers/specs/2026-06-05-iot-foundation-design.md` §Deployment Modes
**Issues:** casehubio/casehub-iot#5
**Navigation:** `git log --grep="#5" --oneline`
**Blog:** 🔲
**Improvement refs:** 🔲
**Completed:** 🔲 pending

#### What it adds

**Before:** Local deployment only — provider connects to HA/OpenHAB on the local network. No remote or SaaS option.
**After:** `BridgeRuntime` observes local StateChangeEvents, serializes, and forwards to cloud CaseHub via WebSocket. DeviceCommands relayed from cloud back to local providers. Hybrid mode runs selectable automations locally.

- **SaaS bridge** — StateChangeEvent forwarded to cloud; DeviceCommand relayed back; bridge is stateless
- **Hybrid mode** — local automations for latency-sensitive reactions (security alerts, presence lights); cloud automations for orchestration, optimization, HITL
- **Event serialization** — StateChangeEvent is already a clean serializable record; natural wire protocol

Not closed here: Drools integration for hybrid local automations (application tier responsibility).

#### Accountability gaps closed

| Gap | What breaks without it | Closed by |
|---|---|---|
| Local deployment only | CaseHub SaaS users cannot control home devices | Bridge forwards events and relays commands over WebSocket |
| No hybrid option | Latency-sensitive automations delayed by cloud round-trip | Hybrid mode selects which automations run locally vs cloud |

#### Key files (planned)

- `iot-bridge/src/main/java/io/casehub/iot/bridge/BridgeRuntime.java` — @ApplicationScoped; @ObservesAsync StateChangeEvent → forward; WebSocket receive → dispatch
- `iot-bridge/src/main/java/io/casehub/iot/bridge/CloudWebSocketClient.java` — WebSocket client for cloud endpoint
- `iot-bridge/src/main/java/io/casehub/iot/bridge/EventSerializer.java` — StateChangeEvent ↔ JSON serialization

#### Key wiring

- **Bridge has no domain logic** — pure event forwarding and command relay. Automation rules belong in the application tier (casehub-life or Drools).
- **Authentication** — `casehub.iot.bridge.token` + `casehub.iot.bridge.tenant-id` sent on WebSocket connect. TLS for transport.
- **Hybrid mode configuration** — `casehub.iot.bridge.local-automations` and `casehub.iot.bridge.cloud-automations` are comma-separated automation IDs. Events matching local automations are processed locally AND forwarded to cloud.

#### Architectural decisions

- **Why WebSocket for cloud relay, not HTTP POST:** State changes are continuous and bidirectional (events up, commands down). WebSocket provides persistent connection with low overhead per message. HTTP POST would require separate polling for commands.
- **Why bridge is stateless:** State management introduces consistency problems between bridge and cloud. Bridge forwards events as they arrive; cloud maintains the authoritative device state. Recovery is simple: bridge reconnects; cloud re-syncs via `discover()`.

#### Pattern introduced

**Stateless event relay with deployment mode selection:** Bridge observes local events, forwards to cloud, relays commands back. No state management — cloud is authoritative. Deployment mode (local/bridge/hybrid) configured at startup.

#### Pattern anchor

- `BridgeRuntime` — event observation + forwarding

#### Gotchas

🔲 at C5 close.

#### Pattern to replicate

1. Create a `@ApplicationScoped` runtime that `@ObservesAsync` the system's state change event.
2. Serialize the event to JSON (the event should already be a clean serializable record).
3. Forward via WebSocket to the cloud endpoint with tenant-id and auth token.
4. Listen for incoming commands on the same WebSocket; deserialize and dispatch to the local provider.
5. Add deployment mode configuration: local (no bridge), bridge (all events forwarded), hybrid (selected events processed locally).
6. Keep the bridge stateless — cloud is the authoritative state store.

---

## §10 Architectural Decisions

Decisions that span multiple layers or are cross-cutting. Layer-specific decisions are in §9.4 layer entries.

### ADR-0001 — Abstract class hierarchy for DeviceEntity (not interfaces)

**Date:** 2026-06-06
**Context:** Device types need shared state fields (deviceId, label, available, lastUpdated, tenancyId). Options: (a) abstract class hierarchy, (b) interface hierarchy with default methods, (c) interface hierarchy with record implementations.
**Decision:** Abstract class hierarchy. DeviceEntity is abstract with common fields. Typed subtypes are abstract classes. Vendor supplements extend subtypes.
**Consequences:** Single inheritance limits composition. Acceptable because device classification IS a taxonomy — "a light IS a device" — and vendor supplements add at most one more level. Consumers never implement DeviceEntity; they consume it.
**Alternatives rejected:** Interfaces with default methods lose field encapsulation. Records cannot be extended for supplements.

### ADR-0002 — Vendor supplement types via inheritance (not composition)

**Date:** 2026-06-06
**Context:** HA and OpenHAB have fields with no cross-vendor equivalent (rgbColor, hvacAction, heatingDemand, hsb). Options: (a) supplement subclass extends common type, (b) composition via `VendorExtension` bag on DeviceEntity.
**Decision:** Inheritance. `HomeAssistantLight extends LightDevice`.
**Consequences:** Consumers who need vendor-specific fields downcast explicitly. Acceptable because: (1) importing `iot-homeassistant` is an explicit choice to be vendor-aware; (2) composition bags (`extension.get("rgbColor")`) lose type safety and IDE support.
**Alternatives rejected:** Composition bag — loses compile-time type checking; accessor is `Object`-typed; no IDE auto-complete.

### ADR-0003 — CDI Event.fireAsync for state changes (not reactive streams)

**Date:** 2026-06-06
**Context:** State change propagation mechanism. Options: (a) CDI Event.fireAsync, (b) Mutiny Multi<> stream, (c) callback registration.
**Decision:** CDI Event.fireAsync. Standard Quarkus pattern. No subscription management.
**Consequences:** No backpressure. IoT state changes are low-frequency (seconds, not milliseconds). CDI queues events internally. High-frequency sensors batch at provider level.
**Alternatives rejected:** Multi<> adds subscription lifecycle complexity unnecessary at IoT event frequency. Callbacks require manual registration and deregistration.

---

## §11 Quality Requirements

| Priority | Goal | Scenario | Layers |
|---|---|---|---|
| 1 | Cross-vendor portability | Automation rule `if (thermostat.mode() == HEAT && thermostat.currentTemperature().celsius() > 23)` works identically with HA and OpenHAB | L1, L2, L3, L4 |
| 2 | API stability | casehub-life upgrades iot-api from 1.0 to 1.1; all existing automation code compiles without changes | L1, L2 |
| 3 | Test without hardware | casehub-life integration test runs with `MockDeviceProvider` returning fixture `ThermostatDevice`; no HA instance needed | L5 |
| 4 | Low-latency state propagation | HA light toggled → `StateChangeEvent` received by `@ObservesAsync` consumer within 100ms (local network) | L3 |
| 5 | Extensible provider model | SmartThings provider added in a new module implementing `DeviceProvider`; no changes to iot-api or existing providers | L2 |
| 6 | Reconnection resilience | HA WebSocket disconnects → provider reconnects within 5s (first attempt); full state re-synced via REST discovery | L3 |

---

## §12 Risks and Technical Debt

| Risk / Debt | Impact | Status | Mitigation |
|---|---|---|---|
| API surface wrong on first cut | Breaking changes needed before casehub-life Layer 9 depends on it | Open | TDD with MockDeviceProvider validates contract; HA provider validates against real data; iterate before 1.0 release |
| OpenHAB semantic model assumption | Users without semantic model cannot use OpenHAB provider | Accepted | Document prominently; Thing-scoped discovery fallback deferred |
| WebSocket reliability (HA) | HA WebSocket drops silently; state changes lost during reconnection | Open | Exponential backoff reconnection; full state re-sync via REST discovery on reconnect |
| State cache timing (OpenHAB) | 50ms coalescing window may be too short (split events) or too long (delayed reaction) | Open | Make window configurable; validate with real OpenHAB instance |
| casehub-life Layer 9 timing | casehub-life needs iot-api before full provider work completes | Open | C1 (Core API) + C2 (Test Infrastructure) sufficient for casehub-life to begin Layer 9 |
| Bridge security | WebSocket to cloud carries device state — interception risk | Open | TLS mandatory; bridge-auth-token per tenant; bridge does not store HA/OH credentials |
| Device ID stability | HA entity_id can change; OpenHAB item names can change | Open | Provider maps platform ID to stable deviceId via a deterministic transformation; document ID stability contract |

---

## §13 Glossary

| Term | Definition |
|---|---|
| **DeviceEntity** | Abstract base class for all IoT devices — carries deviceId, deviceClass, label, available, lastUpdated, tenancyId |
| **DeviceClass** | Enum categorizing device types: SWITCH, LIGHT, THERMOSTAT, SENSOR, PRESENCE_SENSOR, POWER_SENSOR, LOCK, COVER, MEDIA_PLAYER, FAN |
| **DeviceProvider** | SPI interface that platform adapters implement: discover(), dispatch(), status() |
| **DeviceRegistry** | SPI interface for device lookup: findById, findByClass, findAll, refresh |
| **CdiDeviceRegistry** | Default DeviceRegistry implementation — discovers all DeviceProvider beans via CDI Instance<>, calls discover(), maintains in-memory device map |
| **StateChangeEvent** | Record carrying before/after DeviceEntity, changedCapabilities set, occurredAt, and providerId |
| **DeviceCommand** | Record carrying targetDeviceId, action, parameters map, dispatchedBy, and correlationId |
| **CommandResult** | Enum for command dispatch outcome: SENT, FAILED, TIMEOUT |
| **ProviderStatus** | Enum for provider connection state: CONNECTED, CONNECTING, DISCONNECTED |
| **ProviderStatusEvent** | Record fired via CDI when a provider's connection status transitions |
| **changedCapabilities** | Set<String> on StateChangeEvent identifying which device fields changed — consumers pattern-match without full diffing |
| **Vendor supplement type** | Subclass extending a common device type with fields that have no cross-vendor equivalent |
| **Equipment Group** | OpenHAB semantic model concept — a Group item tagged as Equipment containing member Point items representing device capabilities |
| **State cache** | OpenHAB provider's Map<String, DeviceEntity> keyed by Equipment Group name; coalesces multiple item events within 50ms into single StateChangeEvent |
| **Bridge** | Lightweight Quarkus runtime running locally; forwards StateChangeEvents to cloud CaseHub via WebSocket; relays DeviceCommands back |
| **Matter Device Type Library** | Industry-standard device categorization from the Matter smart home standard — DeviceClass vocabulary alignment source |
| **Temperature** | Value type with explicit unit (Celsius/Fahrenheit) — prevents unit confusion across platforms |
| **ThermostatMode** | Enum for thermostat operating mode: HEAT, COOL, AUTO, OFF, FAN_ONLY |

---

## Arc42Stories Self-Assessment

### Completeness

- **All 6 layers:** Scoped with planned key files, architectural decisions, accountability gaps, and pattern-to-replicate sections. Full entries will be completed at chapter close.
- **All 5 chapters:** Scoped with layer impact matrices and accountability gaps. Delivery entries will be written at chapter close.
- **§1–§8:** Complete for current knowledge. §6 Runtime View covers 5 scenarios across all deployment modes.
- **§10:** Three cross-cutting ADRs. Layer-specific decisions are inline in §9.4.

### Pre-implementation State

This ARC42STORIES was written before any implementation. Gotchas, key wiring details, and pattern anchors marked 🔲 will be populated as each chapter delivers. The architecture may evolve — this document evolves with it.

### LLM Replication

Each layer entry includes a Pattern to replicate section written in domain-agnostic language. An LLM building a similar device abstraction layer for a different IoT ecosystem (e.g. Zigbee, Z-Wave, KNX) should succeed at following these steps, adapting platform-specific details.

### C4 Diagrams

- **§3 C4Context:** Shows casehub-iot in relation to HA, OpenHAB, consumers, cloud.
- **§5 C4Container:** Target delivery state with all 6 layers.
- **§6 Sequence diagrams:** 5 runtime scenarios covering state change, command dispatch, discovery, coalescing, and bridge relay.

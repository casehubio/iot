# CloudEvent Adapter for StateChangeEvent

**Issue:** casehubio/iot#19
**Date:** 2026-06-20
**Status:** Approved

## Context

CaseHub platform uses CloudEvents (CNCF spec) as the typed event envelope for
inter-system communication. Each domain module provides an adapter that observes
its domain events and fires `Event<CloudEvent>.fireAsync()`, enabling downstream
consumers to react without coupling to domain-specific types.

This spec defines the IoT adapter: `StateChangeEvent` to `CloudEvent`.

**Parent spec:** `casehubio/parent — docs/superpowers/specs/2026-06-13-p0-layering-decisions-design.md`

**P0 spec discrepancy:** The P0 spec (Decision 1, impact table) uses shorthand
`io.casehub.iot.<deviceClass>`. This spec is authoritative for the full type
format — the event kind segment (`state_change`) was added for consistency with
the Qhorus (`message`) and Connectors (`inbound`) patterns and to future-proof
for additional IoT event kinds. Follow-up: update the P0 summary table.

## Where It Lives

`IoTCloudEventAdapter` in `casehub-iot-api`.

The `api` module gains a compile dependency on `casehub-platform-api`, which
transitively provides `cloudevents-core` (zero runtime dependencies). This is
a policy decision: `platform-api` is a shared vocabulary module — any repo may
depend on it.

**ARC42STORIES update required:** §2 Constraints currently states "No casehubio
dependencies. Foundation-tier peer to casehub-connectors." and the header states
"Depends on: none". Both must be updated to reflect the `casehub-platform-api`
dependency. Updated text: "Depends on casehub-platform-api (shared vocabulary —
types only, no runtime behaviour)." This update is part of the implementation,
not a separate task.

The adapter is `@ApplicationScoped`. `Event<CloudEvent>.fireAsync()` is a no-op
when no observer exists, so there is no cost in apps that don't consume CloudEvents.

## CloudEvent Mapping

| Field | Value | Derivation |
|-------|-------|------------|
| `type` | `io.casehub.iot.state_change.thermostat` | `"io.casehub.iot.state_change." + event.after().deviceClass().name().toLowerCase()` |
| `source` | `/casehub-iot` | Constant URI — the IoT subsystem |
| `subject` | `device/{deviceId}` | `"device/" + event.after().deviceId()` |
| `id` | UUID | `UUID.randomUUID().toString()` |
| `time` | Event timestamp | `event.occurredAt().atOffset(ZoneOffset.UTC)` — `CloudEventBuilder.withTime()` requires `OffsetDateTime`; `occurredAt` is `Instant` |
| `datacontenttype` | `application/json` | Constant |
| `data` | StateChangeEvent as JSON bytes | Jackson `ObjectMapper` serialization |
| `tenancyid` ext | Tenant ID | `event.after().tenancyId()` |
| `providerid` ext | Provider | `event.providerId()` |

### Type Convention

The `type` field encodes both **event kind** and **device class**:
`io.casehub.iot.state_change.<deviceClass>`

**Separator convention (platform-wide):** Underscores for compound words within
segments, following the CNCF CloudEvents spec examples (`com.github.pull_request.opened`).
Dots separate segments (reverse-DNS prefix). This convention applies to all
CaseHub CloudEvent adapters — IoT, Qhorus, and Connectors.

Examples with compound `DeviceClass` enum values:

| DeviceClass enum | `.name().toLowerCase()` | Full CloudEvent type |
|---|---|---|
| `THERMOSTAT` | `thermostat` | `io.casehub.iot.state_change.thermostat` |
| `PRESENCE_SENSOR` | `presence_sensor` | `io.casehub.iot.state_change.presence_sensor` |
| `MEDIA_PLAYER` | `media_player` | `io.casehub.iot.state_change.media_player` |

This aligns with the P0 spec patterns for other modules:
- Qhorus: `io.casehub.qhorus.message.<messageType>` — event kind = `message`
- Connectors: `io.casehub.connectors.inbound.<connectorType>` — event kind = `inbound`
- IoT: `io.casehub.iot.state_change.<deviceClass>` — event kind = `state_change`

Prefix matching supports:
- `io.casehub.iot.state_change` — all IoT state changes
- `io.casehub.iot` — all IoT events (future-proof for `discovered`, `unavailable`, etc.)

Device class is derived from `DeviceClass` enum (not Java class name) to avoid:
1. **Polymorphism bugs** — `HomeAssistantThermostat.getSimpleName()` would leak provider-specific types into the CloudEvent type field. `deviceClass()` returns `THERMOSTAT` regardless of the concrete subclass.
2. **Refactoring fragility** — class renames change the type string silently.
3. **Naming leakage** — Java suffixes like "Device" are not domain concepts.

### Source and Subject

`source` identifies the IoT subsystem (constant), not individual devices.
`subject` identifies the specific entity within that source. Provider identity
goes in the `providerid` extension — directly filterable without URI parsing.

### Tenancy

Derived from `event.after().tenancyId()`, not `CurrentPrincipal`. The adapter
runs in an `@ObservesAsync` context with no request scope. `DeviceEntity` already
carries the tenancy ID, resolved by the provider before firing.

## Event Flow

```
Provider fires StateChangeEvent
  → CDI delivers to IoTCloudEventAdapter.onStateChange(@ObservesAsync)
    → Adapter builds CloudEvent (type, source, subject, extensions, serialized data)
      → Fires Event<CloudEvent>.fireAsync()
        → Downstream consumers (casehub-ras, etc.) observe @ObservesAsync CloudEvent
```

## Serialization

The adapter uses an internal static `ObjectMapper` (with `JavaTimeModule`,
timestamps as ISO-8601 strings) to serialize the full `StateChangeEvent` record
to `byte[]`. The `ObjectMapper` is not injected — `iot-api` has no
`quarkus-jackson` dependency, so no CDI producer exists for `ObjectMapper`.
Injecting it would cause `UnsatisfiedResolutionException` in downstream
`@QuarkusTest` modules.

Consumers who want typed access add `iot-api` as a dependency and deserialize
with the same polymorphic ObjectMapper config. Consumers who just want to
forward, log, or store treat it as opaque JSON.

## Error Handling

If `ObjectMapper.writeValueAsBytes()` throws `JsonProcessingException`, wrap in
`UncheckedIOException` and let it propagate. CDI's async event infrastructure
logs unchecked exceptions and continues processing other observers. This is a
bug signal (the ObjectMapper is pre-configured and the types are known at compile
time), not a runtime condition — failing loudly is correct.

## Testing

`IoTCloudEventAdapterTest` — unit test, no CDI container.

The adapter takes `Event<CloudEvent>` as an injected dependency. In tests, a
capturing mock intercepts the fired CloudEvent.

**Test 1 — correct mapping:** Fire a `StateChangeEvent` with a `ThermostatDevice`
fixture. Assert:
- `type` = `io.casehub.iot.state_change.thermostat`
- `source` = `/casehub-iot`
- `subject` = `device/{expectedId}`
- `time` = the `occurredAt` value (as `OffsetDateTime` at UTC)
- `tenancyid` extension = the device's tenancy ID
- `providerid` extension = the provider ID
- `data` deserializes back to the original `StateChangeEvent`

**Test 2 — polymorphism guard:** Fire with a `HomeAssistantThermostat` (vendor
supplement type extending `ThermostatDevice`). Assert `type` is still
`io.casehub.iot.state_change.thermostat`, not `homeassistantthermostat`.

**Test 3 — null before (initial discovery):** Fire a `StateChangeEvent` with
`before == null` (initial device discovery). Assert the CloudEvent data
deserializes correctly with `before` as null, and all envelope fields are
populated.

**Test 4 — compound DeviceClass type string:** Fire with a `PresenceSensor`
fixture. Assert `type` is `io.casehub.iot.state_change.presence_sensor`.
Guards the underscore convention — an implementer who normalises to hyphens
(`.replace('_', '-')`) passes Tests 1–3 (all single-word enum values) but
fails here.

## Dependencies Added

```xml
<!-- api/pom.xml -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-platform-api</artifactId>
</dependency>
```

Version managed by `casehub-parent` BOM. Transitive: `cloudevents-core` (compile).

## Garden Reference

GE-20260618-11251a: CloudEvents deserialization in Quarkus REST requires
`byte[] + EventFormatProvider.deserialize()`, not auto-binding. Relevant for
consumers deserializing the CloudEvent, not for the adapter itself (which
produces, not consumes).

# Thing-Scoped Discovery — Design Spec

**Date:** 2026-06-14
**Issue:** casehubio/iot#11
**Prerequisite spec:** `docs/superpowers/specs/2026-06-10-chapter4-openhab-provider-design.md`

---

## Overview

Layered discovery for the OpenHAB provider. Both Equipment-based (semantic model) and Thing-based discovery always run and merge. Equipment-mapped devices get rich semantic-tag-based field mapping. Thing-only devices (no semantic model) get basic mapping from channel metadata. Thing status provides accurate real-time availability for all devices.

**Breaking change to internal OpenHAB module only.** No API changes — `DeviceProvider.discover()` return type and `DeviceEntity` hierarchy are unchanged. The `openhab` module's internal discovery and SSE pipeline gains a second layer.

---

## Architecture

### Dual-Layer Discovery

Two discovery paths run in parallel on every `connect()`:

**Layer 1 — Equipment (existing):**
`GET /rest/items?tags=Equipment&recursive=true` → Equipment resolution strategy → `ResolvedDeviceFields` → shared construction → DeviceEntities with full semantic tag mapping.

**Layer 2 — Thing (new):**
`GET /rest/things` → Thing resolution strategy → `ResolvedDeviceFields` → shared construction → DeviceEntities with channel-based mapping.

**Thing fetch is always performed** regardless of `thingDiscoveryEnabled`. Things are needed for:
- The `thingToEquipment` index (Equipment-backed Thing availability via SSE)
- Availability override on Equipment-mapped devices
- `thingDiscoveryEnabled` controls only whether unmapped Things produce DeviceEntities

### Discovery Pipeline — Uni Chain

```
Uni.combine().all().unis(
    restClient.getItems("Equipment", true),
    restClient.getThings()
        .onFailure().recoverWithItem(List.of())       // Failure isolation
).asTuple()
.chain(tuple -> {
    List<OpenHabItemDto> equipments = tuple.getItem1();
    List<OpenHabThingDto> things = tuple.getItem2();

    // Phase 1: Equipment mapping (always)
    populateEquipmentCaches(equipments);
    Set<String> coveredItems = buildCoveredItemsSet(equipments);

    // Phase 2: Build thingToEquipment index + enhance availability (always)
    buildThingIndexes(things, coveredItems);
    enhanceAvailabilityFromThings(things, coveredItems);

    // Phase 3: Thing mapping (only when thingDiscoveryEnabled)
    if (!thingDiscoveryEnabled) return Uni.createFrom().voidItem();

    List<OpenHabThingDto> unmappedThings = filterUnmapped(things, coveredItems);
    if (unmappedThings.isEmpty()) return Uni.createFrom().voidItem();

    // Phase 4: Fetch item states for unmapped Thing channels
    return restClient.getAllItems()
        .onFailure().recoverWithItem(List.of())
        .invoke(allItems -> {
            Map<String, OpenHabItemDto> itemLookup = buildFilteredItemLookup(allItems, unmappedThings);
            populateThingCaches(unmappedThings, itemLookup);
        });
})
.invoke(v -> subscribeSse())
.replaceWithVoid()
```

**Failure isolation:** If `getThings()` fails, the Equipment layer stands alone via `.onFailure().recoverWithItem(List.of())`. The provider operates in semantic-model-only mode (C4 behaviour). Same for `getAllItems()`. Equipment results are never compromised by Thing discovery failures.

### Merge Rules

1. **Equipment-backed Thing:** A Thing where ANY linked item appears in an Equipment Group. Use Equipment mapping. Override `available` from Thing's `statusInfo.status` — OFFLINE forces unavailable regardless of item states. Partial overlap (some channels in Equipment, some not) is treated as Equipment-backed — uncovered channels are typically infrastructure (firmware, signal strength) and not mappable to DeviceEntity fields.

2. **Unmapped Thing:** A Thing where NO linked item appears in any Equipment Group. Map via Thing resolution strategy + shared construction. `deviceId` = Thing UID. `label` = Thing label. `available` = statusInfo.status == "ONLINE". Only when `thingDiscoveryEnabled = true`.

3. **Equipment without Thing:** Equipment Group whose members have no Thing backing. Use Equipment mapping as-is (existing behaviour preserved).

4. **Invariant:** Equipment-backed Things never produce entries in `thingDeviceCache`. This prevents deduplication issues in `cachedDevices()` — the caches are disjoint by construction.

### Item State Resolution for Unmapped Things

`getAllItems()` returns all items without tag filter. From the response, build a filtered lookup map containing only items linked to unmapped Thing channels — not the full list. This minimises memory for large installations.

---

## Eliminating Mapper Duplication

### The Problem

Both Equipment-based and Thing-based mapping produce the same DeviceEntity types with the same builder calls. The variation point is **resolution** — how you find the field value — not **construction** — how you build the DeviceEntity. Duplicating 7+ `mapXxx` methods creates a synchronisation hazard: bug fixes to parsing (temperature scale, HSB format, position inversion) must be applied in two places.

### Design: Resolved Fields + Shared Construction

Extract a `ResolvedDeviceFields` record that both resolution strategies produce. A single set of construction methods builds DeviceEntities from resolved fields.

```java
record ResolvedDeviceFields(
    String deviceId, String label, boolean available, Instant now, String tenancyId,
    DeviceClass deviceClass,
    // Per-type fields — all nullable, populated by the resolution strategy
    Boolean on,
    Temperature currentTemperature, Temperature targetTemperature, ThermostatMode mode,
    BigDecimal heatingDemand, BigDecimal coolingDemand,
    OpenHabHsbType hsb, Integer brightness,
    Boolean locked,
    Integer position, boolean isRollershutter,
    Integer volume,
    SensorType sensorType, BigDecimal numericValue, String unit,
    BigDecimal power, BigDecimal energy,
    Boolean present
) {}
```

**Resolution strategies:**

```java
// Equipment path — resolves from semantic tags
ResolvedDeviceFields resolveFromEquipment(OpenHabItemDto equipment, Instant now);

// Thing path — resolves from channel itemType
ResolvedDeviceFields resolveFromThing(OpenHabThingDto thing, Map<String, OpenHabItemDto> itemStates, Instant now);
```

**Shared construction:**

```java
DeviceEntity buildDevice(ResolvedDeviceFields fields) {
    return switch (fields.deviceClass()) {
        case THERMOSTAT -> buildThermostat(fields);
        case LIGHT -> buildLight(fields);
        case SWITCH -> buildSwitch(fields);
        case LOCK -> buildLock(fields);
        case COVER -> buildCover(fields);
        case MEDIA_PLAYER -> buildMediaPlayer(fields);
        case FAN -> buildFan(fields);
        case SENSOR -> buildSensor(fields);
        case POWER_SENSOR -> buildPowerSensor(fields);
        case PRESENCE_SENSOR -> buildPresenceSensor(fields);
        default -> null;
    };
}
```

Parsing utilities (`parseTemperature`, `parseHsb`, `parseCoverPosition`, `parseOrNull`, `isNullOrUndef`, `mapHvacModeString`) are shared unchanged — they operate on state strings which are identical regardless of resolution path.

This refactoring touches `OpenHabEntityMapper` (extract construction from resolution) but the Equipment mapping behaviour is unchanged — existing tests verify this.

### DeviceClass Refinement in Resolution Strategies

OpenHAB's semantic model has no Equipment tags for POWER_SENSOR or PRESENCE_SENSOR — only `Sensor`. The Equipment path's `resolveDeviceClass()` returns SENSOR for all sensor Equipment, then member inspection in the resolution strategy refines to the true DeviceClass:

- `Measurement+Power` or `Measurement+Energy` members → `deviceClass = POWER_SENSOR`
- `Measurement+Presence` members → `deviceClass = PRESENCE_SENSOR`
- `MotionDetector` equipment tag WITHOUT `Measurement+Presence` member → `deviceClass = SENSOR`, `sensorType = MOTION` (tag contributes SensorType, not a DeviceClass refinement — PresenceSensor requires a Presence-tagged member to populate its `present` field)
- Otherwise → `deviceClass = SENSOR`

The `ResolvedDeviceFields.deviceClass` carries the **final refined class**, not the initial tag hint. Construction dispatches on this refined class. This eliminates the current inconsistency where `resolveDeviceClass()` returns SENSOR but `mapSensor()` builds entities with POWER_SENSOR or PRESENCE_SENSOR DeviceClass values.

The Thing resolution strategy applies the same principle: channel `itemType` gives the initial signal, then the strategy refines based on channel-specific analysis.

---

## New DTOs

Package: `io.casehub.iot.openhab.internal`

### OpenHabThingDto

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenHabThingDto(
    @JsonProperty("UID") String uid,
    String label,
    String thingTypeUID,
    OpenHabStatusInfoDto statusInfo,
    List<OpenHabChannelDto> channels,
    String location   // reserved — future room/zone assignment
) {}
```

`location` is present in the OpenHAB Thing response and captured in the DTO for future use (room/zone assignment in casehub-life). Not referenced in mapping or merge logic in this spec.

### OpenHabChannelDto

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenHabChannelDto(
    String uid,
    String id,
    String channelTypeUID,
    String itemType,
    String kind,          // "STATE" or "TRIGGER"
    List<String> linkedItems,
    List<String> defaultTags
) {}
```

**TRIGGER channel filter:** Only channels with `kind = "STATE"` (or null/missing kind) participate in mapping. TRIGGER channels do not carry persistent state and are excluded from DeviceClass inference, field resolution, and command dispatch target resolution.

### OpenHabStatusInfoDto

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenHabStatusInfoDto(
    String status,         // "ONLINE", "OFFLINE", "UNKNOWN", etc.
    String statusDetail
) {}
```

---

## Thing → DeviceClass Inference

Channel `itemType` is universal across all OpenHAB bindings. Only STATE channels participate.

**Algorithm:** The mapper scans ALL STATE channels on a Thing, determines the candidate DeviceClass for each channel from the priority table below, and returns the **highest-priority candidate** (lowest row number wins). This prevents channel iteration order (which depends on the OpenHAB binding) from affecting the result. A Thing with channels `[Number, Switch]` and `[Switch, Number]` must produce the same DeviceClass.

| Priority | Channels present on Thing | DeviceClass | Notes |
|---|---|---|---|
| 1 | Any channel with `itemType: Color` | LIGHT | Color-capable light |
| 2 | `Dimmer` (no Color channel) | LIGHT | Dimmable light |
| 3 | `Rollershutter` | COVER | Position inverted to CaseHub convention |
| 4 | `Player` | MEDIA_PLAYER | |
| 5 | `Number:Power` or `Number:Energy` | POWER_SENSOR | |
| 6 | Multiple `Number:Temperature` (measurement + setpoint) | THERMOSTAT | Disambiguated by channelTypeUID — see below |
| 7 | Single `Number:Temperature` | SENSOR | SensorType.TEMPERATURE |
| 8 | `Number:Humidity` | SENSOR | SensorType.HUMIDITY |
| 9 | `Switch` (no Color/Dimmer/Rollershutter) | SWITCH | Actuator — stronger signal than generic measurement |
| 10 | `Contact` | SENSOR | SensorType.GENERIC — too ambiguous for LOCK without semantic context |
| 11 | `Number` (bare, no dimension qualifier) | SENSOR | SensorType.GENERIC — fallback for unclassified measurements |

### Thermostat Disambiguation

A Thing with two or more `Number:Temperature` channels could be a thermostat (measurement + setpoint) or a multi-zone sensor. Disambiguation:

1. Check `channelTypeUID` for setpoint hints: contains `setpoint`, `target`, `desired` (case-insensitive)
2. Check channel `id` for setpoint hints: contains `setpoint`, `target`, `desired`
3. If both a measurement-like and a setpoint-like Temperature channel exist → THERMOSTAT
4. Otherwise → SENSOR

### Thermostat Mode for Thing-Mapped Devices

The Equipment resolution strategy resolves HVAC mode from a `Control+Switch` member (power on/off) and a String item with "mode" in the name. The Thing resolution strategy adapts the same heuristic to channel metadata:

1. Look for a `Switch`-type STATE channel — if linked item state is not "ON", mode = OFF (powered down)
2. Look for a `String`-type STATE channel whose `channelTypeUID` or `id` contains "mode" (case-insensitive)
3. If found, parse the linked item's state via `mapHvacModeString()` (shared utility)
4. If no mode channel found, default to `ThermostatMode.OFF`

This works for most real thermostat bindings — binding authors frequently name mode channels with "mode" in the id (e.g., `hvacMode`, `thermostat_mode`, `system:mode`).

### SensorType Resolution for Thing Path

Channel `itemType` determines SensorType:

| Channel itemType | SensorType |
|---|---|
| `Number:Temperature` | TEMPERATURE |
| `Number:Humidity` | HUMIDITY |
| `Contact` | GENERIC |
| `Number` (bare, no dimension) | GENERIC |

### DeviceClasses NOT Reachable via Thing-Only Discovery

The following DeviceClasses require semantic model tags and cannot be inferred from channel metadata alone:

| DeviceClass | Why unreachable | Semantic tag required |
|---|---|---|
| FAN | Channel itemType is `Switch` — indistinguishable from a plain switch | `Fan` equipment tag |
| LOCK | Channel itemType is `Switch` or `Contact` — indistinguishable from switch/sensor | `Lock` equipment tag |
| PRESENCE_SENSOR | No channel itemType maps to presence detection specifically | `MotionDetector` equipment tag or `Measurement+Presence` point tags |

These devices will be discovered as SWITCH or SENSOR via Thing-only discovery. Users needing correct classification should configure the OpenHAB semantic model.

### Unmappable Things

Things where no STATE channel matches any known `itemType` pattern are logged at DEBUG and skipped. Expected for infrastructure Things (bridges, network devices).

---

## REST Client Changes

Add to `OpenHabRestClient`:

```java
@GET @Path("/rest/things")
Uni<List<OpenHabThingDto>> getThings();

@GET @Path("/rest/items")
Uni<List<OpenHabItemDto>> getAllItems();
```

`getAllItems()` has no `tags` filter — returns all items. Called only when unmapped Things exist. Separate from the existing `getItems(tags, recursive)` which remains for Equipment discovery. This is a second REST call, not a replacement.

---

## SSE Integration

### Topic Subscription

The SSE subscription changes from:

```
openhab/items/*/statechanged
```

to:

```
openhab/items/*/statechanged,openhab/things/*/statuschanged
```

OpenHAB's SSE endpoint supports comma-separated topic filters in a single connection. This adds real-time Thing status events (`ThingStatusInfoChangedEvent`) alongside item state events, enabling immediate availability updates for all devices.

### Thing Status Event Handling

`ThingStatusInfoChangedEvent` payload format is a JSON-encoded array of two `OpenHabStatusInfoDto` objects: `[newStatusInfo, oldStatusInfo]`.

```
"[{\"status\":\"OFFLINE\",\"statusDetail\":\"COMMUNICATION_ERROR\"},{\"status\":\"ONLINE\",\"statusDetail\":\"NONE\"}]"
```

Parsing: deserialize the payload string as `List<OpenHabStatusInfoDto>`. Index 0 = new status, index 1 = old status.

When a `ThingStatusInfoChangedEvent` arrives:

```
1. Parse Thing UID from topic: "openhab/things/{thingUID}/statuschanged"
2. Deserialize payload as List<OpenHabStatusInfoDto>, take index 0 as new status
3. If Thing UID in thingToEquipment:
   a. Look up Equipment name
   b. Rebuild Equipment-mapped device with updated availability
   c. Fire StateChangeEvent if availability changed
4. Else if Thing UID in thingCache (Thing-only device):
   a. Rebuild Thing-mapped device with updated availability
   b. Fire StateChangeEvent if availability changed
5. Else: ignore (infrastructure Thing)
```

### Reverse Index Extension

The `OpenHabSseClient` gains two new indexes:

```java
// Existing — Equipment path
private final ConcurrentHashMap<String, String> itemToEquipment;

// New — Thing path (items not in any Equipment)
private final ConcurrentHashMap<String, String> itemToThing;

// New — Thing UID → Equipment name (for Thing status → Equipment availability)
private final ConcurrentHashMap<String, String> thingToEquipment;
```

`itemToThing` is populated for items linked to unmapped Things only (items NOT in `itemToEquipment`). `thingToEquipment` maps Thing UIDs to Equipment names — populated always (regardless of `thingDiscoveryEnabled`) to support Equipment-backed Thing availability via SSE.

### Item Event Handling

`handleSseEvent` gains a second lookup path:

```
1. Parse event type from JSON
2. If ItemStateChangedEvent:
   a. Parse item name from topic
   b. Check itemToEquipment → if found, process via existing Equipment coalescing
   c. Else check itemToThing → if found, process via Thing coalescing
   d. Else ignore (standalone item)
3. If ThingStatusInfoChangedEvent:
   a. Handle as Thing status event (see above)
```

Equipment takes priority when an item appears in both indexes (should not happen by construction, but defensive).

### Thing Device Cache

```java
// Existing
private final ConcurrentHashMap<String, DeviceEntity> deviceCache;        // Equipment name → DeviceEntity
private final ConcurrentHashMap<String, OpenHabItemDto> equipmentCache;   // Equipment name → DTO

// New
private final ConcurrentHashMap<String, DeviceEntity> thingDeviceCache;   // Thing UID → DeviceEntity
private final ConcurrentHashMap<String, OpenHabThingDto> thingCache;      // Thing UID → DTO
```

`cachedDevices()` returns the union of `deviceCache.values()` and `thingDeviceCache.values()`. The caches are disjoint by construction (merge rule invariant).

---

## Command Dispatch

### Target Item Resolution for Thing-Scoped Devices

`resolveTargetItem` gains a Thing-scoped path:

```java
// Check Equipment path first (existing)
if (equipmentCache.containsKey(command.targetDeviceId())) {
    return resolveBySemanticTags(command);
}

// Thing path (new)
OpenHabThingDto thing = thingCache.get(command.targetDeviceId());
if (thing != null) {
    return resolveByChannelType(command, thing);
}

return null;
```

### resolveByChannelType

Maps command actions to target channel `itemType`, filtering STATE channels only:

| Command action | Target channel itemType | Disambiguation |
|---|---|---|
| `turn_on`, `turn_off` | `Switch` (or `Dimmer`/`Color`) | Prefer Color > Dimmer > Switch |
| `set_temperature` | `Number:Temperature` | **Setpoint channel** — same heuristic as thermostat disambiguation: `channelTypeUID` or `id` contains `setpoint`, `target`, `desired` |
| `lock`, `unlock` | `Switch` | — |
| `set_position` | `Rollershutter` | — |
| `set_volume` | `Dimmer` | Prefer channel with `channelTypeUID` containing `volume` |

For `set_temperature`, the disambiguation is critical: sending a temperature command to the measurement channel instead of the setpoint channel would be incorrect. The same `channelTypeUID`/`id` heuristics used in thermostat DeviceClass inference are reused here.

Returns the first linked item name from the matching channel.

### Command Value Building

`buildCommandValue` is unchanged — it produces correct OpenHAB command strings regardless of resolution path.

---

## Configuration

```java
@ConfigMapping(prefix = "casehub.iot.openhab")
public interface OpenHabConfig {
    // ... existing fields ...
    @WithDefault("true") boolean thingDiscoveryEnabled();
}
```

**`thingDiscoveryEnabled` controls Thing mapping only, not Thing fetching.** When `false`:
- Things are still fetched (needed for `thingToEquipment` index and availability override)
- Unmapped Things do NOT produce DeviceEntities
- The SSE subscription still includes `openhab/things/*/statuschanged` for Equipment-backed Thing availability
- The `getAllItems()` call is skipped (no unmapped Thing states needed)

When `true` (default): full layered discovery as described.

---

## Testing Strategy

### Shared Construction Tests

Verify that `ResolvedDeviceFields` → DeviceEntity construction produces identical results regardless of resolution strategy:
- Same resolved fields from Equipment and Thing paths produce equal DeviceEntities
- Supplement type selection (OpenHabThermostat vs ThermostatDevice) works correctly
- POWER_SENSOR construction dispatched from refined DeviceClass
- PRESENCE_SENSOR construction dispatched from refined DeviceClass

### OpenHabThingMapperTest

Unit tests for Thing → DeviceEntity mapping:
- Color channel → OpenHabLight with HSB
- Dimmer channel → LightDevice with brightness
- Switch channel → SwitchDevice
- Dual Number:Temperature → ThermostatDevice (measurement + setpoint)
- Single Number:Temperature → SensorDevice with SensorType.TEMPERATURE
- Number:Humidity → SensorDevice with SensorType.HUMIDITY
- Number (bare) → SensorDevice with SensorType.GENERIC
- Contact → SensorDevice with SensorType.GENERIC
- Rollershutter → CoverDevice (position inverted)
- Player → MediaPlayerDevice
- Number:Power → PowerSensor (resolved at DeviceClass level)
- OFFLINE Thing → available=false
- No mappable channels → null
- Linked item with NULL/UNDEF state → field defaults
- TRIGGER channels excluded from mapping
- Thermostat mode resolved from String channel with "mode" in id
- Thermostat mode defaults to OFF when no mode channel found

### OpenHabSseClientTest (additions)

- Thing-scoped device in cache returned by `cachedDevices()`
- SSE event for Thing-linked item updates Thing device cache
- SSE event for Equipment-linked item uses Equipment path (priority)
- Thing-scoped coalescing works identically to Equipment coalescing
- ThingStatusInfoChangedEvent → OFFLINE updates availability on Equipment-backed device
- ThingStatusInfoChangedEvent → OFFLINE updates availability on Thing-only device
- ThingStatusInfoChangedEvent → ONLINE restores availability
- ThingStatusInfoChangedEvent payload parsed as [newStatus, oldStatus] array

### OpenHabProviderTest (additions)

- Layered discovery: Equipment + Thing results merged
- Thing status OFFLINE overrides Equipment-mapped device availability
- Unmapped Thing produces basic DeviceEntity
- Partial overlap (some channels in Equipment, some not) → Equipment-backed
- `thingDiscoveryEnabled=false` skips Thing mapping but still fetches Things
- `thingDiscoveryEnabled=false` still builds thingToEquipment index
- Thing discovery failure doesn't affect Equipment results
- getAllItems() failure doesn't affect Equipment results

### OpenHabCommandDispatchTest (additions)

- Command dispatch to Thing-scoped device resolves target via channel type
- `turn_on` to Thing-scoped switch finds Switch channel's linked item
- `set_temperature` to Thing-scoped thermostat finds setpoint channel (not measurement)
- TRIGGER channels excluded from target resolution

---

## Deferred Concerns

- **`thingTypeUID`-based mapping** (#16): Binding-specific Thing type UIDs could provide more accurate DeviceClass inference. Deferred — channel itemType covers common cases.
- **Channel `defaultTags` exploitation** (#18): Some bindings populate semantic tags on channels. Could improve field mapping accuracy. Deferred — channel itemType is sufficient.

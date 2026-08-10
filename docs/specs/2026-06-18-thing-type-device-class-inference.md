# thingTypeUID-Based DeviceClass Inference — Design Spec

**Date:** 2026-06-18
**Issue:** casehubio/iot#16
**Deferred from:** casehubio/iot#11

---

## Overview

Enhance `OpenHabThingResolver` to use the thing-type's `category` field from the OpenHAB REST API as an additional inference signal. Category is binding-maintained metadata — for revealing categories (Lock, MotionDetector, Lightbulb) it's more reliable than channel guessing. For coarse categories (Sensor) channel inference provides refinement. Category also enriches `SensorType` for cross-provider parity with the HA provider.

---

## Design

### REST Endpoint

Add to `OpenHabRestClient`:

```java
@GET @Path("/rest/thing-types")
Uni<List<OpenHabThingTypeDto>> getThingTypes();
```

### DTO

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenHabThingTypeDto(
    @JsonProperty("UID") String uid,
    String category
) {}
```

### Category → DeviceClass Mapping

Static method on `OpenHabThingResolver`. Case-insensitive matching.

| OpenHAB Category | DeviceClass | Rationale |
|-----------------|-------------|-----------|
| `Lightbulb` | LIGHT | Unambiguous |
| `RadiatorControl` | THERMOSTAT | Radiator controllers are always thermostats |
| `Lock` | LOCK | Unambiguous — closes Thing-path gap |
| `Blinds` | COVER | Unambiguous |
| `PowerOutlet`, `WallSwitch` | SWITCH | On/off actuators |
| `Inverter` | POWER_SENSOR | Solar inverters are power monitoring devices |
| `Sensor`, `SmokeDetector`, `Door`, `FrontDoor`, `Window` | SENSOR | Coarse — channels refine further |
| `MotionDetector` | PRESENCE_SENSOR | Closes the Thing-path PRESENCE_SENSOR gap — no channel itemType maps to presence |
| `Speaker`, `Receiver`, `Screen`, `Projector` | MEDIA_PLAYER | Audio/video output devices |
| All others (Battery, Camera, Car, HVAC, GarageDoor, etc.) | `null` — fall through to channel inference | |

**HVAC deliberately unmapped.** OpenHAB defines HVAC as "Air condition devices, Fans" — there is no separate Fan category. Mapping HVAC → THERMOSTAT would catch fans and produce ThermostatDevice entities with null temperatures. For actual HVAC thermostats, channel inference already gives THERMOSTAT via dual-temperature disambiguation.

**GarageDoor deliberately unmapped.** Garage doors are typically motorized actuators (Rollershutter or Switch channels), not sensors. A reed switch monitoring a garage door would get category Door or Sensor. The category is too ambiguous for a static mapping — channels determine the result.

### SensorType Enrichment from Category

When the resolved DeviceClass is SENSOR and the category provides domain-specific information, set `SensorType` for cross-provider parity with the HA provider (which maps door/window/garage_door → `SensorType.DOOR_WINDOW`):

| Category | SensorType |
|----------|------------|
| `Door`, `FrontDoor`, `Window` | DOOR_WINDOW |
| `SmokeDetector` | GENERIC (no SMOKE value in SensorType today) |
| `Sensor` | no override — channels determine SensorType |

This enrichment runs after DeviceClass resolution and field population, only when the final DeviceClass is SENSOR. Category-based SensorType overrides only when the current SensorType is GENERIC or unset. Channel-derived specific SensorTypes (TEMPERATURE, HUMIDITY, MOTION, etc.) take precedence — they carry more precise information than the broad category signal.

### Inference Priority — Two-Signal Model

The priority model distinguishes **revealing** categories (tell you something channels can't) from **coarse** categories (less specific than channels).

#### Restructured resolve() flow

```java
DeviceClass channelClass = inferDeviceClass(stateChannels);   // may be null; includes thermostat disambiguation
DeviceClass categoryClass = mapCategory(thing.thingTypeUID()); // may be null
DeviceClass deviceClass = mergeInference(channelClass, categoryClass);
if (deviceClass == null) return null;  // both null → unmappable
```

This replaces the current early-return (`if (deviceClass == null) return null` before category lookup). Category can rescue things that have no mappable STATE channels — e.g., a Lock thing with only String channels, or a thing with only TRIGGER channels but a valid category.

#### Merge rules

```
1. If categoryClass is non-null AND is not SENSOR → use categoryClass (revealing)
2. If categoryClass is SENSOR → use channelClass if non-null, else SENSOR (coarse, defer for refinement)
3. If categoryClass is null → use channelClass (no category signal)
```

Thermostat disambiguation is part of channel inference (`inferDeviceClass`), not a separate post-processing step. The `channelClass` output already incorporates it. Running it again post-merge would risk overriding revealing categories (e.g., a WallSwitch with temperature monitoring channels).

**Why SENSOR is special:** OpenHAB's Sensor category covers "Device used to measure something" — temperature probes, power meters, CO2 sensors all get this category. Channel inference distinguishes them (Number:Power → POWER_SENSOR, Number:Temperature → SENSOR+TEMPERATURE). Taking SENSOR from category and stopping would lose that refinement.

### Field Population — New DeviceClass Paths

When category introduces a DeviceClass that the Thing path didn't previously support, `populateFields()` needs corresponding population logic. Existing channel handling is governed by DeviceClass guards (e.g., `populateSwitch()` skips when `deviceClass != SWITCH`), so channels that "don't match" the category-derived class are correctly ignored for field purposes.

#### LOCK population

When deviceClass is LOCK, scan channels for:
- `Switch` channel → locked = `"ON".equals(state)`
- `Contact` channel → locked = `"CLOSED".equals(state)`

Without this, category-based Lock inference creates devices with correct type but `locked = false` regardless of state. The Equipment path handles this via `resolveLock()` — the Thing path needs an equivalent.

#### PRESENCE_SENSOR population

When deviceClass is PRESENCE_SENSOR, scan channels for:
- `Switch` channel → present = `"ON".equals(state)`
- `Contact` channel → present = `"OPEN".equals(state)`

Set `present` on `ResolvedDeviceFields.Builder`. `lastSeen` is handled by `OpenHabDeviceBuilder.buildPresenceSensor()` which sets `.lastSeen(f.now())` unconditionally — the resolver does not set it.

### Integration — connect(), Not init()

`@PostConstruct` runs synchronously before the container is wired — blocking on a reactive HTTP call there is wrong. The thing-types fetch goes in `connect()`, which already has a `Uni.combine()` chain:

```java
Uni.combine().all().unis(
    restClient.getItems("Equipment", true),
    restClient.getThings().onFailure().recoverWithItem(List.of()),
    categoryMapCached != null
        ? Uni.createFrom().item(List.<OpenHabThingTypeDto>of())
        : restClient.getThingTypes().onFailure().recoverWithItem(List.of())
).asTuple()
```

**Caching:** Thing-types are static metadata — they don't change between OpenHAB restarts. Fetch on first `connect()`, cache the category map. Subsequent reconnects skip the REST call entirely via the conditional Uni above. The resolver is created once with the category map; it's not recreated on reconnect.

**Failure:** If `getThingTypes()` fails on first connect, `recoverWithItem(List.of())` produces an empty list. `categoryMapCached` remains null (not cached), so the next reconnect re-attempts the fetch. Once a non-empty result is received, the map is cached and reconnects skip the call.

### Components

| File | Change |
|------|--------|
| `OpenHabThingTypeDto` (new) | Record: uid + category |
| `OpenHabRestClient` | Add `getThingTypes()` |
| `OpenHabThingResolver` | Constructor accepts category map, restructured `resolve()`, two-signal merge, LOCK + PRESENCE_SENSOR population, SensorType enrichment |
| `OpenHabSseClient` | `connect()` fetches thing-types (cached on first success), passes map to resolver |
| `OpenHabThingResolverTest` | Tests for category inference, refinement, fallback, LOCK, PRESENCE_SENSOR, SensorType enrichment |

### Test Plan

| Test | Assertion |
|------|-----------|
| Revealing category maps to DeviceClass | Thing with `Lightbulb` category → LIGHT, regardless of channels |
| Lock category + Switch ON → locked | `Lock` category + Switch ON → LOCK with locked = true |
| Lock category + Contact CLOSED → locked | `Lock` category + Contact CLOSED → LOCK with locked = true |
| Lock category with no channels → LOCK defaults | Lock category, no STATE channels → LOCK with locked = false |
| MotionDetector + Switch ON → present | `MotionDetector` category + Switch ON → PRESENCE_SENSOR with present = true |
| MotionDetector + Contact OPEN → present | `MotionDetector` category + Contact OPEN → PRESENCE_SENSOR with present = true |
| SENSOR category defers to channel refinement | `Sensor` category + Number:Power channels → POWER_SENSOR (not SENSOR) |
| SENSOR category + thermostat disambiguation | `Sensor` category + 2 temperature channels with setpoint → THERMOSTAT |
| SENSOR category with no refinable channels → SENSOR | `Sensor` category + unmappable channels → SENSOR |
| Door category enriches SensorType | `Door` category + Contact channel → SENSOR with SensorType.DOOR_WINDOW |
| Unknown category falls through to channel inference | `Camera` category + Switch channel → SWITCH |
| Null category falls through to channel inference | No category → channel-based inference unchanged |
| HVAC falls through to channel inference | `HVAC` category → null from category, channels determine result |
| GarageDoor falls through to channel inference | `GarageDoor` category → null from category, channels determine result |
| Category rescues null-channel thing | Lock category + no mappable STATE channels → LOCK (not null) |
| Case-insensitive matching | `lightbulb` and `Lightbulb` both → LIGHT |
| Empty category map degrades gracefully | No thing-type data → all inference via channels (existing behavior) |

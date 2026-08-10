# C4: OpenHAB Provider — Design Spec

**Date:** 2026-06-10
**Issue:** casehubio/iot#4
**Reference implementation:** `homeassistant/` (C3)
**Foundation spec:** `docs/superpowers/specs/2026-06-05-iot-foundation-design.md`
**C3 spec:** `docs/superpowers/specs/2026-06-09-chapter3-homeassistant-provider-design.md`

---

## Overview

OpenHAB provider for `casehub-iot` — implements `DeviceProvider` SPI using OpenHAB's REST API for discovery and command dispatch, and SSE event stream for real-time state subscription. Follows the same structural patterns as the Home Assistant provider (C3).

**Hard prerequisite:** OpenHAB's semantic model must be configured — Equipment items (Groups tagged as Equipment) with member Point items. Without the semantic model, discovery returns nothing. This is documented prominently in config and error messages. Thing-scoped discovery fallback is deferred (#11).

**Naming convention:** This spec uses `OpenHab*` (camelCase) for Java class names, following standard Java convention and paralleling `HomeAssistant*` in the HA module. The foundation spec uses `OpenHAB*` — the foundation spec naming is the deviation from Java convention and should be updated separately.

---

## API-Level Changes

### CoverDevice position convention

The HA mapper (`HomeAssistantEntityMapper.mapCover()`) passes `current_position` straight through. In HA, `current_position` uses 0=closed, 100=open. This establishes the CaseHub canonical convention, but it is only implicit.

**Fix:** Add Javadoc to `CoverDevice.position()` documenting the canonical convention:

> Position as a percentage: 0 = fully closed, 100 = fully open. Providers that use the opposite convention (e.g., OpenHAB Rollershutter: 0=open, 100=closed) must invert before populating this field.

This is an API documentation fix, not a behavioral change. The OpenHAB mapper inverts: `position = 100 - openhabPercentage`.

---

## Components

| Class | Responsibility |
|-------|---------------|
| `OpenHabProvider` | `@ApplicationScoped DeviceProvider` — SPI entry point, injects `OpenHabRestClient` for discovery/commands, delegates state subscription to SSE client |
| `OpenHabRestClient` | MicroProfile REST Client interface (`configKey = "openhab"`) — discovery + command dispatch. Short-lived request/response with read-timeout. |
| `OpenHabSseRestClient` | MicroProfile REST Client interface (`configKey = "openhab-sse"`) — SSE subscription only. Long-lived stream, no read-timeout. |
| `OpenHabSseClient` | `@ApplicationScoped` — SSE subscription lifecycle, state cache, coalescing, reconnection. Injects `OpenHabSseRestClient` for the SSE Multi and `OpenHabRestClient` for discovery on connect/reconnect. Owns the item-to-Equipment index and Equipment DTO cache. |
| `OpenHabEntityMapper` | `@ApplicationScoped` — **pure stateless mapper**: OpenHAB Item DTO → `DeviceEntity`. No indexes, no caches. |
| `OpenHabConfig` | `@ConfigMapping(prefix = "casehub.iot.openhab")` — connection and tuning params |

Package: `io.casehub.iot.openhab`
Internal DTOs: `io.casehub.iot.openhab.internal`

---

## Configuration

```java
@ConfigMapping(prefix = "casehub.iot.openhab")
public interface OpenHabConfig {
    String url();
    String token();
    String tenancyId();
    @WithDefault("5")    int reconnectBaseSeconds();
    @WithDefault("300")  int reconnectMaxSeconds();
    @WithDefault("50")   int coalesceWindowMs();
}
```

```properties
# Application config
casehub.iot.openhab.url=http://openhab.local:8080
casehub.iot.openhab.token=<api-token>
casehub.iot.openhab.tenancy-id=household-1

# REST client — short-lived requests (discovery, commands)
quarkus.rest-client."openhab".url=${casehub.iot.openhab.url}
quarkus.rest-client."openhab".connect-timeout=5000
quarkus.rest-client."openhab".read-timeout=10000

# SSE client — long-lived event stream, NO read-timeout
quarkus.rest-client."openhab-sse".url=${casehub.iot.openhab.url}
quarkus.rest-client."openhab-sse".connect-timeout=5000
# No read-timeout: SSE connections are idle for unbounded periods in quiet homes.
# A read-timeout here would kill the connection on every idle period > timeout,
# causing continuous reconnect-discover-subscribe cycles.
```

Authentication: Bearer token via `Authorization: Bearer <token>` header on all requests (both REST client interfaces share the same `lookupToken()` implementation).

---

## Data Model — Internal DTOs

### OpenHabItemDto

Represents an OpenHAB Item as returned by the REST API. Used for both Equipment Groups and their member Point items.

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenHabItemDto(
    String type,            // "Group", "Switch", "Number:Temperature", "String", etc.
    String name,            // "LivingRoom_Thermostat", "LivingRoom_Temperature"
    String label,           // "Living Room Thermostat"
    String state,           // "21.5", "ON", "heat", "NULL", "UNDEF"
    List<String> tags,      // ["Equipment", "HVAC"] or ["Measurement", "Temperature"]
    List<OpenHabItemDto> members,  // non-null for Groups, null for plain items
    @JsonProperty("stateDescription")
    OpenHabStateDescriptionDto stateDescription  // optional — carries unit pattern
) {}
```

**Note on lastUpdated:** OpenHAB's standard REST API item response does not include a per-item timestamp. `DeviceEntity.lastUpdated` is populated with `Instant.now()` at discovery time and on each SSE event. This is less accurate than HA's per-entity `last_updated` timestamp but functionally equivalent for consumers — the timestamp reflects "when CaseHub last saw this state," not "when the device last changed." Documented as accepted divergence.

### OpenHabStateDescriptionDto

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenHabStateDescriptionDto(
    String pattern  // "%d °C", "%d %%", etc.
) {}
```

### OpenHabSseEventDto

Wrapper for SSE event `data` field — the payload is a JSON string inside the data.

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenHabSseEventDto(
    String topic,    // "openhab/items/LivingRoom_Temperature/statechanged"
    String payload,  // JSON string: {"type":"Decimal","value":"21.5","oldType":"Decimal","oldValue":"21.0"}
    String type      // "ItemStateChangedEvent"
) {}
```

### OpenHabStatePayloadDto

Parsed from the `payload` JSON string inside the SSE event.

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenHabStatePayloadDto(
    String type,      // "Decimal", "OnOff", "Percent", "HSB", "UpDown"
    String value,     // "21.5", "ON", "75", "240,100,50", "UP"
    String oldType,
    String oldValue
) {}
```

---

## Discovery — Semantic Model Mapping

### REST endpoint

`GET /rest/items?tags=Equipment&recursive=true`

Returns all Equipment Groups with their member Point items inlined.

**deviceId:** Each Equipment maps to one `DeviceEntity` with `deviceId = equipment.name()`. This is the stable identifier used for command dispatch lookup and state cache keying.

**Tag filtering assumption:** This query assumes OpenHAB's REST API does hierarchy-aware tag filtering — i.e., items tagged `["HVAC"]` (which inherits from Equipment in OpenHAB's semantic hierarchy) are returned by `tags=Equipment`. If the API does literal string matching instead, installations that only tag items with the specific Equipment subtype (without the parent `"Equipment"` tag) would return empty results. **Verify against an actual OpenHAB instance during implementation.** If literal matching is confirmed, the alternative is to query all items and filter client-side, or query for each Equipment subtype tag individually.

### Equipment tag → DeviceClass mapping

| Equipment semantic tag | DeviceClass | Notes |
|----------------------|-------------|-------|
| `HVAC`, `RadiatorControl`, `AirConditioner`, `HeatPump`, `Boiler` | `THERMOSTAT` | Requires Temperature Measurement + Setpoint members |
| `Lightbulb`, `LightStrip` | `LIGHT` | |
| `PowerOutlet`, `WallSwitch` | `SWITCH` | |
| `Lock` | `LOCK` | |
| `Blinds`, `Rollershutter` | `COVER` | |
| `Receiver`, `Screen`, `Speaker`, `Television` | `MEDIA_PLAYER` | |
| `Fan` | `FAN` | |
| `Sensor` | `SENSOR` | Inferred from member Point types |
| `MotionDetector` | `SENSOR` | Maps to `SensorDevice` with `SensorType.MOTION` |
| `Battery` | `SENSOR` | Charge percentage — maps to `SensorDevice` with `SensorType.GENERIC` and `unit = "%"` |
| `SmokeDetector` | `SENSOR` | Maps to `SensorDevice` with `SensorType.GENERIC` — smoke ≠ CO |

Equipment Groups without a recognised semantic tag are logged at WARN and skipped.

### Member Point → device field mapping

Point semantic tags (from `tags` array) determine which device field a member item populates:

| Point tag combination | Target field | Value parsing |
|----------------------|-------------|---------------|
| `Measurement` + `Temperature` | `currentTemperature` | `BigDecimal`; unit: default Celsius, Fahrenheit if `stateDescription.pattern` contains `°F` or `℉` |
| `Setpoint` + `Temperature` | `targetTemperature` | Same unit resolution as Measurement |
| `Control` + `Switch` (on HVAC) | on/off toggle | `ON`→thermostat is on, `OFF`→thermostat is off (sets mode to `OFF`) |
| `Control` + `Switch` (on non-HVAC) | `isOn` / `isLocked` | `ON`→true, `OFF`→false |
| `Status` + `OpenState` (Contact item) | binary open/closed | `OPEN`→position=100, `CLOSED`→position=0 |
| `Status` + `OpenState` (Rollershutter) | `position` | Percent 0-100, **inverted**: `position = 100 - openhabPercentage` |
| `Measurement` + `Humidity` | `SensorDevice` humidity | `BigDecimal` |
| `Measurement` + `Power` | `PowerSensor.power` | `BigDecimal` watts |
| `Measurement` + `Energy` | `PowerSensor.energy` | `BigDecimal` kWh |
| `Measurement` + `Presence` | `PresenceSensor.present` | `ON`→true, `OFF`→false |
| `Control` + `SoundVolume` | `volume` | Percent 0-100 |

**HVAC mode resolution:** OpenHAB has no standard semantic tag for thermostat mode. The mapper searches for a String item in the Equipment Group whose `name` or `label` contains "mode" (case-insensitive). If found, the state is mapped to `ThermostatMode` via the same table as HA (`"heat"→HEAT`, `"cool"→COOL`, `"auto"→AUTO`, `"off"→OFF`). If no mode item is found, the mapper logs a WARN and defaults to `ThermostatMode.OFF`. This is acknowledged as fragile — a future improvement could support a custom CaseHub semantic tag convention.

**Contact vs Rollershutter disambiguation:** Items with `Status` + `OpenState` tags require type-aware parsing. The item's `type` field distinguishes them: `"Contact"` → binary (OPEN/CLOSED mapped to 100/0); `"Rollershutter"` or `"Dimmer"` → percentage (inverted). The mapper checks the `type` field, not the tag alone.

Items with `NULL` or `UNDEF` state: the device is marked `available = false`. The field is left null/default.

**Items not belonging to any Equipment Group:** SSE events for items not in the `itemToEquipment` index are silently ignored (DEBUG log). This is expected — OpenHAB installations have many standalone items.

### Mapper design

`OpenHabEntityMapper` is a **pure stateless mapper**. It receives a top-level Equipment Group `OpenHabItemDto` (with inlined members) and produces a `DeviceEntity`:

```java
public DeviceEntity mapEquipment(OpenHabItemDto equipment, Instant now) {
    DeviceClass deviceClass = resolveDeviceClass(equipment.tags());
    // iterate equipment.members(), resolve each point's role, populate builder
    // lastUpdated = now (no timestamp in OpenHAB item JSON)
}
```

The mapper does **not** build or maintain indexes. The SSE client populates its own reverse-lookup maps during discovery by iterating the Equipment's members.

---

## SSE Subscription — State Cache and Coalescing

### SSE endpoint

`GET /rest/events?topics=openhab/items/*/statechanged`

Returns an SSE stream of `ItemStateChangedEvent` frames:

```
event: message
data: {"topic":"openhab/items/LivingRoom_Temperature/statechanged",
       "payload":"{\"type\":\"Decimal\",\"value\":\"22.0\",\"oldType\":\"Decimal\",\"oldValue\":\"21.5\"}",
       "type":"ItemStateChangedEvent"}
```

### SSE client design

`OpenHabSseClient` manages the SSE connection using a **dedicated MicroProfile REST Client** (`OpenHabSseRestClient`, `configKey = "openhab-sse"`). This is separate from `OpenHabRestClient` (`configKey = "openhab"`) because they have fundamentally different timeout requirements: REST calls need a read-timeout (10s); SSE streams must never have one (a quiet home may go minutes without a state change — a read-timeout would kill the connection and trigger pointless reconnect-discover-subscribe cycles).

Both HA and OpenHAB modules use `quarkus-rest-client`, which is the RESTEasy Reactive client in Quarkus 3.x. No dependency change is needed for SSE support — `Multi<SseEvent<String>>` is available from this artifact. The `SseEvent` type is `org.jboss.resteasy.reactive.client.SseEvent<T>`, which provides `id()`, `name()`, `comment()`, `data()`. The RESTEasy Reactive client handles SSE frame parsing — line buffering, `\n\n` boundaries, `data:` field extraction — with no manual parsing needed.

Connection lifecycle is managed explicitly (not via Mutiny retry operators) to allow re-discovery on reconnect:

```
connect():
  1. fireStatus(CONNECTING)
  2. Call discover() → populate state cache + indexes
  3. Subscribe to sseRestClient.subscribeEvents("openhab/items/*/statechanged")
  4. On first event: fireStatus(CONNECTED)
  5. On Multi completion or error: fireStatus(DISCONNECTED), scheduleReconnect()

scheduleReconnect():
  - Exponential backoff (base 5s, max 5min, jittered)
  - On retry: call connect() again (re-discovers to refresh indexes)
```

### State cache structure

```java
// Equipment-level DTO cache — stores the Equipment Group item (name, tags, type, label,
// stateDescription) for reconstruction with updated members. Populated at discovery.
private final ConcurrentHashMap<String, OpenHabItemDto> equipmentCache;

// Raw item states per Equipment — updated on every SSE event.
// Equipment name → (item name → latest OpenHabItemDto with updated state).
// The full DTO is needed (not just the state String) because the mapper requires
// type, tags, and stateDescription for parsing.
private final ConcurrentHashMap<String, ConcurrentHashMap<String, OpenHabItemDto>> itemStateCache;

// Assembled DeviceEntity per Equipment — rebuilt from itemStateCache on change
private final ConcurrentHashMap<String, DeviceEntity> deviceCache;

// Reverse index: item name → Equipment name — populated at discovery
private final ConcurrentHashMap<String, String> itemToEquipment;

// Coalescing: Equipment name → snapshot of "before" DeviceEntity (captured on first event in window)
private final ConcurrentHashMap<String, DeviceEntity> coalescingBefore;

// Coalescing: Equipment name → pending timer
private final ConcurrentHashMap<String, ScheduledFuture<?>> coalescingTimers;
```

### Event processing flow

```
1. SSE frame arrives (on Vert.x event loop thread via REST Client Multi)
2. Parse data → OpenHabSseEventDto → extract item name from topic
3. Look up owning Equipment via itemToEquipment map
   → if null (item not in any Equipment): log DEBUG, return
4. Create updated OpenHabItemDto record from existing cached item + new state value
5. Store updated item DTO in itemStateCache[equipment][itemName]
6. Reconstruct Equipment DTO from equipmentCache + itemStateCache members:
     OpenHabItemDto updated = new OpenHabItemDto(
         equip.type(), equip.name(), equip.label(), equip.state(), equip.tags(),
         List.copyOf(itemStateCache.get(equipmentName).values()),
         equip.stateDescription());
7. Re-map via mapper.mapEquipment(updated, Instant.now()) → newEntity
8. oldEntity = deviceCache.put(equipment, newEntity)  // atomic: put() returns old value
9. Coalescing branch:
   a. If no pending timer for this Equipment:
      - coalescingBefore.put(equipment, oldEntity)  // snapshot before any change
      - Start 50ms timer → on expiry: fireStateChange(equipment)
   b. If timer already pending:
      - Do nothing (timer continues; "before" was captured at first event)
```

### Coalescing

Multiple item events for the same Equipment within the coalescing window (default 50ms) produce a single `StateChangeEvent`. This prevents flooding consumers when OpenHAB reports individual item changes for a multi-field device (e.g., thermostat setpoint + mode change simultaneously).

Implementation: `ScheduledExecutorService` (single-threaded, daemon) with one pending `ScheduledFuture` per Equipment.

On timer expiry:
```
  before = coalescingBefore.remove(equipment)
  after = deviceCache[equipment]
  changedCapabilities = StateChangeEvent.deriveChangedCapabilities(before, after)
  if changedCapabilities is non-empty:
      fire StateChangeEvent(before, after, changedCapabilities, Instant.now(), "openhab")
  coalescingTimers.remove(equipment)
```

### Thread safety model

- SSE events arrive on the Vert.x event loop thread (via REST Client Multi subscription)
- Coalescing timers fire on the `ScheduledExecutorService` thread
- All caches use `ConcurrentHashMap` — provides happens-before guarantees between put (event loop thread) and get (timer thread)
- The `before` reference is captured once (first event in window, event loop thread) via `coalescingBefore.put()` and read once (timer expiry, executor thread) via `coalescingBefore.remove()` — ConcurrentHashMap provides the visibility guarantee
- `deviceCache.put()` returns the old value atomically — the "before" snapshot is captured in the same operation that stores "after," eliminating the race between overwrite and read

### Connection lifecycle

Same pattern as HA WebSocket client:
- Connect at `@PostConstruct` via `OpenHabProvider.start()`
- Exponential backoff reconnection (base 5s, max 5min, jittered)
- `ProviderStatusEvent` on CONNECTED / CONNECTING / DISCONNECTED transitions
- `@PreDestroy` closes the SSE subscription and shuts down the coalescing executor
- On reconnect: re-discover to refresh the item-to-Equipment index, then re-subscribe to SSE

**Event loss window:** Between disconnect and successful reconnect, state changes are lost. The re-discovery on reconnect compensates by providing a full state snapshot. Consumers that track deltas will see a gap — this is accepted behavior. The alternative (event replay from OpenHAB) is not supported by the OpenHAB REST API.

---

## Command Dispatch

### REST endpoint

`POST /rest/items/{itemName}` with `Content-Type: text/plain` body containing the command value.

### Action → item resolution

The provider resolves the target item from the Equipment's member items using semantic Point/Property tags. Full tag combination is required for disambiguation — bare `Control` is insufficient when an HVAC Equipment has multiple Control items (power switch, fan speed, damper).

| DeviceCommand action | Target Point tags | Command body |
|---------------------|-------------------|-------------|
| `turn_on` | `Control` + `Switch` | `ON` |
| `turn_off` | `Control` + `Switch` | `OFF` |
| `set_temperature` | `Setpoint` + `Temperature` | numeric value as string (e.g., `"22.0"`) |
| `lock` | `Control` + `Switch` (on Lock Equipment) | `ON` |
| `unlock` | `Control` + `Switch` (on Lock Equipment) | `OFF` |
| `set_position` | `Control` + `OpenState` or `Status` + `OpenState` | numeric 0-100 as string, **inverted**: `command = 100 - position` |
| `set_volume` | `Control` + `SoundVolume` | numeric 0-100 as string |

If the target item cannot be resolved (no matching Point tag), dispatch returns `CommandResult.FAILED`.

### Error recovery

Same pattern as the HA provider (`HomeAssistantProvider.dispatch()`). The `sendCommand()` Uni is chained with failure recovery handlers:

```java
return restClient.sendCommand(targetItemName, commandValue)
    .map(resp -> resp.getStatus() < 300 ? CommandResult.SENT : CommandResult.FAILED)
    .onFailure(WebApplicationException.class).recoverWithItem(CommandResult.FAILED)
    .onFailure(ProcessingException.class).recoverWithItem(CommandResult.FAILED)
    .onFailure(TimeoutException.class).recoverWithItem(CommandResult.TIMEOUT);
```

- `WebApplicationException`: HTTP 4xx/5xx → `FAILED`
- `ProcessingException`: connection/serialization errors → `FAILED`
- `TimeoutException`: read-timeout exceeded → `TIMEOUT`

---

## Supplement Types

Three OpenHAB-specific types for fields with no cross-vendor equivalent. All follow the same builder pattern as the HA supplements (AbstractBuilder extending the parent's AbstractBuilder), including `static builder()`, `toBuilder()`, and **`capabilities()` override** methods.

The `capabilities()` override is required — without it, `StateChangeEvent.deriveChangedCapabilities()` would never see supplement fields in the capabilities map. A change to a supplement-only field (e.g., `heatingDemand`) would produce an empty `changedCapabilities` set, and the coalescing guard (`if changedCapabilities is non-empty`) would silently drop the event. Same bug pattern C3 §3.6 explicitly warned about.

### OpenHabThermostat extends ThermostatDevice

```java
public class OpenHabThermostat extends ThermostatDevice {
    Optional<BigDecimal> heatingDemand();  // 0-100% valve demand
    Optional<BigDecimal> coolingDemand();  // 0-100% valve demand

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> caps = super.capabilities();
        caps.put(CAP_HEATING_DEMAND, heatingDemand);
        caps.put(CAP_COOLING_DEMAND, coolingDemand);
        return caps;
    }

    public static Builder builder() { ... }
    public Builder toBuilder() { ... }
}
```

Populated from member items with `Measurement` + `Level` tags where the item name or label contains "heating" or "cooling" demand. These are OpenHAB-specific — HA thermostats report `hvacAction` (string) instead.

Capability constants: `CAP_HEATING_DEMAND`, `CAP_COOLING_DEMAND`.

### OpenHabRollershutter extends CoverDevice

```java
public class OpenHabRollershutter extends CoverDevice {
    Optional<OpenHabUpDownType> upDown();  // UP, DOWN, STOP — last commanded direction

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> caps = super.capabilities();
        caps.put(CAP_UP_DOWN, upDown);
        return caps;
    }

    public static Builder builder() { ... }
    public Builder toBuilder() { ... }
}
```

OpenHAB's Rollershutter items support UP/DOWN/STOP commands in addition to percentage position. The `upDown` field captures the last commanded direction. Before any command is issued, `upDown()` returns `Optional.empty()`. The common `CoverDevice.position` carries the percentage (inverted to CaseHub convention: 0=closed, 100=open).

```java
public enum OpenHabUpDownType { UP, DOWN, STOP }
```

Capability constant: `CAP_UP_DOWN`.

### OpenHabLight extends LightDevice

```java
public class OpenHabLight extends LightDevice {
    Optional<OpenHabHsbType> hsb();  // hue, saturation, brightness — OH native colour model

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> caps = super.capabilities();
        caps.put(CAP_HSB, hsb);
        return caps;
    }

    public static Builder builder() { ... }
    public Builder toBuilder() { ... }
}
```

OpenHAB represents colour as HSB (hue 0-360, saturation 0-100, brightness 0-100). The common `LightDevice.brightness` is populated from the B component. HSB itself is OpenHAB-specific.

```java
public record OpenHabHsbType(
    BigDecimal hue,         // 0-360 degrees
    BigDecimal saturation,  // 0-100%
    BigDecimal brightness   // 0-100%
) {}
```

Capability constant: `CAP_HSB`.

---

## REST Clients

### OpenHabRestClient — discovery and commands

```java
@RegisterRestClient(configKey = "openhab")
@ClientHeaderParam(name = "Authorization", value = "{lookupToken}")
public interface OpenHabRestClient {

    @GET @Path("/rest/items")
    Uni<List<OpenHabItemDto>> getItems(
        @QueryParam("tags") String tags,
        @QueryParam("recursive") boolean recursive);

    @POST @Path("/rest/items/{itemName}")
    @Consumes(MediaType.TEXT_PLAIN)
    Uni<Response> sendCommand(
        @PathParam("itemName") String itemName,
        String command);

    default String lookupToken() {
        return "Bearer " + ConfigProvider.getConfig()
                .getValue("casehub.iot.openhab.token", String.class);
    }
}
```

### OpenHabSseRestClient — SSE event stream

```java
@RegisterRestClient(configKey = "openhab-sse")
@ClientHeaderParam(name = "Authorization", value = "{lookupToken}")
public interface OpenHabSseRestClient {

    @GET @Path("/rest/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    Multi<SseEvent<String>> subscribeEvents(
        @QueryParam("topics") String topics);

    default String lookupToken() {
        return "Bearer " + ConfigProvider.getConfig()
                .getValue("casehub.iot.openhab.token", String.class);
    }
}
```

Separated from `OpenHabRestClient` because SSE streams must not have a read-timeout (see Configuration section). The `lookupToken()` duplication is minimal — extracting to a shared utility is optional.

---

## Dependencies

```xml
<dependencies>
    <dependency>
        <groupId>io.casehub</groupId>
        <artifactId>casehub-iot-api</artifactId>
    </dependency>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-rest-client</artifactId>
    </dependency>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-rest-client-jackson</artifactId>
    </dependency>

    <!-- test -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-junit</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>mockwebserver</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

Jandex index required (same as HA module).

**Note on quarkus-junit:** This is the correct artifact — `quarkus-junit5` is a relocation stub since Quarkus 3.31 per garden entry GE-20260512-47f92e and protocol `quarkus-junit-not-junit5.md`. The HA module already uses `quarkus-junit`.

---

## Testing Strategy

### Test infrastructure

`OpenHabMockServerResource implements QuarkusTestResourceLifecycleManager` — starts a MockWebServer, injects the URL into `quarkus.rest-client."openhab".url`, `quarkus.rest-client."openhab-sse".url`, and `casehub.iot.openhab.url`.

### Test classes

| Test class | Covers |
|-----------|--------|
| `OpenHabEntityMapperTest` | Equipment → DeviceEntity mapping for all device classes; unknown tags skipped; NULL/UNDEF → available=false; temperature unit resolution; Contact vs Rollershutter OpenState; position inversion; Battery → SensorDevice; SmokeDetector → SensorDevice (not CO); AirConditioner/HeatPump/Boiler → THERMOSTAT; MotionDetector → SENSOR (MOTION) |
| `OpenHabProviderTest` | `@QuarkusTest` — discover(), dispatch() for each action, status delegation, unknown action → FAILED |
| `OpenHabSseClientTest` | SSE event parsing; item-to-Equipment resolution; items not in Equipment → ignored; state cache update with full DTO reconstruction; coalescing (verify single StateChangeEvent for rapid item events); reconnection scheduling |
| `OpenHabSupplementTest` | Supplement type fields, capabilities() map, builder/toBuilder round-trip; OpenHabRollershutter.upDown() Optional.empty() before command |
| `OpenHabCommandDispatchTest` | Action → item resolution via full tag combination; disambiguation with multiple Control items; POST body format; position inversion on set_position; error recovery |

### Coalescing test approach

Use **Awaitility** with generous timeout (e.g., 2 seconds) to verify that:
1. Rapid SSE events for the same Equipment produce exactly one `StateChangeEvent`
2. The event fires after the coalescing window expires
3. The `changedCapabilities` set reflects all item changes in the window

```java
await().atMost(2, SECONDS).untilAsserted(() ->
    assertThat(capturedEvents).hasSize(1)
);
```

### SSE testing with MockWebServer

MockWebServer serves SSE by enqueuing chunked responses with `Content-Type: text/event-stream`. Unlike HA's WebSocket tests (clean open/message/close lifecycle via MockWebServer's WebSocket support), SSE requires a long-lived chunked response.

**For simple tests** (event parsing, mapping): pre-loaded `MockResponse` with all SSE frames buffered in the body. The client reads all events, then the response completes (triggering reconnection — acceptable in test scope).

**For reconnection tests**: use a MockWebServer `Dispatcher` that holds the response open and writes events incrementally, then closes the connection to simulate disconnect. The next request gets a new Dispatcher response.

---

## Deferred Concerns

These are out of scope for C4 and tracked as separate issues:

- **Thing-scoped discovery fallback** (#11) — for OpenHAB installations without semantic model configuration
- **Basic auth support** (#12) — token auth only in C4; basic auth via config switch
- **SSE event filtering by Equipment** — currently subscribes to all item state changes and filters in-process; OpenHAB supports topic filtering but the wildcard `openhab/items/*/statechanged` is the pragmatic starting point

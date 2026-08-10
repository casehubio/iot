# casehub-iot C3 — Home Assistant Provider Design Spec (rev 5)
**Date:** 2026-06-09
**Issue:** casehubio/iot#3
**Depends on:** C1 (`api/`), C2 (`testing/`)
**Deferred:** casehubio/iot#9 (MockReactiveDeviceProvider), casehubio/parent#211 (PLATFORM.md)

---

## Overview

Implements the Home Assistant provider. Corrects C1 API design errors surfaced by real HA data: (1) `DeviceProvider` SPI blocking — contradicts ARC42STORIES §9.4 and `spi-reactive-blocking-io` protocol; (2) `PowerSensor.power/energy` both `requireNonNull` — incompatible with HA's separate power/energy sensor entities; (3) `CoverDevice.position` primitive `int` — cannot represent covers without position feedback; (4) `ThermostatMode` missing `DRY`. All are API breaks; all are correct to fix before any external consumer exists.

---

## Section 0 — API Corrections (prerequisite to `homeassistant/`)

### 0.1 DeviceProvider SPI

`api/src/main/java/io/casehub/iot/api/spi/DeviceProvider.java`:

```java
public interface DeviceProvider {
    String providerId();
    Uni<List<DeviceEntity>> discover();
    Uni<CommandResult> dispatch(DeviceCommand command);
    ProviderStatus status();   // cached state, no I/O — stays sync
}
```

Rationale: ARC42STORIES §9.4: "Provider operations are I/O-bound. Blocking signatures stall the Vert.x I/O thread when called from reactive contexts." `spi-reactive-blocking-io` protocol mandates `Uni<T>` for SPIs whose implementations perform I/O.

### 0.2 DeviceRegistry.refresh()

`api/src/main/java/io/casehub/iot/api/spi/DeviceRegistry.java`:

```java
public interface DeviceRegistry {
    Optional<DeviceEntity> findById(String deviceId);
    <T extends DeviceEntity> List<T> findByClass(Class<T> deviceClass);
    List<DeviceEntity> findByTenancyId(String tenancyId);
    List<DeviceEntity> findAll();
    Uni<Void> refresh();
}
```

Read methods stay synchronous — in-memory map lookups, no I/O.

### 0.3 CdiDeviceRegistry

`api/src/main/java/io/casehub/iot/spi/CdiDeviceRegistry.java`:

```java
@Override
public Uni<Void> refresh() {
    return Uni.join().all(
            StreamSupport.stream(providers.spliterator(), false)
                .map(p -> p.discover()
                    .onFailure().invoke(e -> LOG.warnf(e, "Provider %s failed during discover", p.providerId()))
                    .onFailure().recoverWithItem(List.of()))
                .toList()
        ).andCollectFailures()
        .map(listOfLists -> {
            synchronized (CdiDeviceRegistry.this) {   // runs on Mutiny thread pool — must synchronize
                Map<String, DeviceEntity> next = new HashMap<>();
                listOfLists.forEach(list -> list.forEach(d -> next.put(d.deviceId(), d)));
                devices = Map.copyOf(next);
            }
            return (Void) null;
        });
}

void onStartup(@Observes StartupEvent event) {
    refresh().await().indefinitely();   // startup runs on worker thread — blocking is correct
}

// Unchanged from C1 — synchronized on same lock as refresh()
void onStateChange(@ObservesAsync StateChangeEvent event) {
    updateDevice(event.after());
}

private synchronized void updateDevice(DeviceEntity device) {
    Map<String, DeviceEntity> current = devices;
    Map<String, DeviceEntity> next = new HashMap<>(current);
    next.put(device.deviceId(), device);
    devices = Map.copyOf(next);
}
```

Per-provider failure recovery: each `discover()` has `.onFailure().recoverWithItem(List.of())`. `andCollectFailures()` emits a failed `Uni` on any constituent failure; recovery before joining ensures the join always succeeds. Partial discovery is better than startup crash (C1 contract preserved).

### 0.4 PowerSensor — optional fields

HA exposes power and energy as separate sensor entities. Both `requireNonNull` makes construction impossible from a single-reading entity.

`api/src/main/java/io/casehub/iot/api/PowerSensor.java`:
- `private final BigDecimal power` and `energy` — `requireNonNull` removed
- `public Optional<BigDecimal> power()` and `public Optional<BigDecimal> energy()`
- `capabilities()` unchanged — null values in map are correct per C2 convention

`Fixtures.solarPanel()`: `power(new BigDecimal("3200")).energy(null)` — power-only sensor reflecting real HA pattern.

### 0.5 CoverDevice — optional position

HA covers without position feedback (dumb relays, curtains without sensors) have no `current_position` attribute. Defaulting `position` to `0` claims the cover is "definitely closed" when position is genuinely unknown.

`api/src/main/java/io/casehub/iot/api/CoverDevice.java`:
- `private final Integer position` (boxed, nullable — `requireNonNull` not applied)
- `public Optional<Integer> position()`
- Builder: `Integer position` field (nullable); **`public B position(Integer v)`** — boxed parameter required; calling `position(null)` is a compile error with `int`; calling `position(75)` still works via autoboxing from int literal; position defaults to `null` when not set
- `capabilities()` unchanged — `caps.put(CAP_POSITION, position)` with nullable is correct per C2 convention
- `toBuilder()` updated — `position(position)` where `position` is now `Integer`

`Fixtures.bedroomBlinds()`: `position(null).moving(false)` — no feedback sensor; or keep `position(0)` if representing a known-closed cover. Fixtures should use `Optional.empty()` to represent "unknown" — update to `position(null)`.

### 0.6 ThermostatMode — DRY added

HA climate entities expose `dry` mode (dehumidification). Mapping to `FAN_ONLY` is semantically wrong; they are distinct HVAC operation modes.

`api/src/main/java/io/casehub/iot/api/ThermostatMode.java`:
```java
public enum ThermostatMode { HEAT, COOL, AUTO, OFF, FAN_ONLY, DRY }
```

Adding an enum value is a backwards-compatible minor version bump.

### 0.7 SensorType — CO added (from rev 3)

```java
public enum SensorType { TEMPERATURE, HUMIDITY, MOTION, DOOR_WINDOW, CO2, CO, LUX, GENERIC }
```

### 0.8 MockDeviceProvider — updated for Uni<> SPI

```java
public Uni<List<DeviceEntity>> discover() {
    return Uni.createFrom().item(() -> List.copyOf(devices.values()));
}
public Uni<CommandResult> dispatch(DeviceCommand command) {
    dispatchedCommands.add(command);
    return Uni.createFrom().item(dispatchResult);
}
```

### 0.9 api/pom.xml

Add `io.smallrye.reactive:mutiny` as compile dep. Follows engine-api precedent.

### 0.10 ARC42STORIES updates

- §2 Constraints: "Async model" → "Reactive SPIs (Uni<>) for discover/dispatch; corrected in C3"
- §12 deviceId risk → mitigation: "entity_id verbatim; renames require refresh(); accepted risk"

### 0.11 Test migrations

- `CdiDeviceRegistryTest` — all `registry.refresh()` → `registry.refresh().await().indefinitely()`
- `PowerSensorTest` — Optional<BigDecimal> assertions; builder calls without requireNonNull
- `CoverDeviceTest` / `ExtensibleDeviceTest` — `device.position()` returns `Optional<Integer>`: `assertThat(device.position()).hasValue(75)` not `assertEquals(75, device.position())`
- `EnumTest.thermostatModeHasFiveValues()` → `hasSize(6)`, add `ThermostatMode.DRY`
- `EnumTest.sensorTypeHasSevenValues()` → `hasSize(8)`, verify CO and CO2 both present
- `FixturesTest` — `solarPanel()` assertions: power present, energy absent; `bedroomBlinds()` position `Optional.empty()`

---

## Section 1 — Module Structure

No new module. `runtime/` deferred until C5 bridge's threading model is known.

`homeassistant/pom.xml` compile deps:
- `casehub-iot-api`
- `quarkus-rest-client` — SmallRye MicroProfile REST Client (classic); supports `Uni<>` return types in Quarkus 3.x
- `quarkus-rest-client-jackson` — Jackson integration with native reflection registration for the classic REST client
- `quarkus-websockets-next`

ARC42STORIES §2: "Quarkus REST client" for HA; "Quarkus REST client reactive" only for OpenHAB SSE. Platform convention confirmed in claudony: `quarkus-rest-client` + `quarkus-rest-client-jackson`.

---

## Section 2 — HomeAssistant Module Design

### 2.1 Configuration

```java
@ConfigMapping(prefix = "casehub.iot.homeassistant")
public interface HomeAssistantConfig {
    String url();
    String token();
    String tenancyId();
    @WithDefault("5")   int reconnectBaseSeconds();
    @WithDefault("300") int reconnectMaxSeconds();
    @WithDefault("30")  int pingIntervalSeconds();
    @WithDefault("10")  int pongTimeoutSeconds();
}
```

Required in the consuming deployment's `application.properties`:
```properties
casehub.iot.homeassistant.url=http://homeassistant.local:8123
casehub.iot.homeassistant.token=<long-lived-access-token>
casehub.iot.homeassistant.tenancy-id=<tenancy-id>

# REST client URL bridge — MicroProfile reads quarkus.rest-client."configKey".url
quarkus.rest-client."homeassistant".url=${casehub.iot.homeassistant.url}

# REST client timeouts — without these, requests hang indefinitely and CommandResult.TIMEOUT is unreachable
quarkus.rest-client."homeassistant".connect-timeout=5000
quarkus.rest-client."homeassistant".read-timeout=10000
```

Note: Quarkus REST client classic wraps timeout conditions in `ProcessingException`. The existing `.onFailure(ProcessingException.class).recoverWithItem(CommandResult.FAILED)` recovery handles them. `CommandResult.TIMEOUT` is returned if a framework-level `TimeoutException` propagates unwrapped; both recovery chains are correct to keep.

### 2.2 HomeAssistantRestClient

```java
@RegisterRestClient(configKey = "homeassistant")
@ClientHeaderParam(name = "Authorization", value = "{lookupToken}")
public interface HomeAssistantRestClient {

    @GET @Path("/api/states")
    Uni<List<HaStateDto>> getStates();

    @POST @Path("/api/services/{domain}/{service}")
    Uni<Response> callService(@PathParam("domain") String domain,
                              @PathParam("service") String service,
                              HaServiceCallDto body);

    // CDI injection doesn't work in interface default methods — use ConfigProvider
    default String lookupToken() {
        return "Bearer " + ConfigProvider.getConfig()
            .getValue("casehub.iot.homeassistant.token", String.class);
    }
}
```

`callService` returns `Uni<Response>` — MicroProfile REST Client does not automatically throw on 4xx/5xx when using `Uni<Response>`; status is checked in `dispatch()`. `@Inject @RestClient HomeAssistantRestClient restClient` — `@RestClient` qualifier required; without it CDI cannot locate the proxy bean.

### 2.3 HomeAssistantWebSocketClient

`@WebSocketClient(clientId = "homeassistant", path = "/api/websocket")` endpoint.

**Why `@WebSocketClient` over `BasicWebSocketConnector`:** The annotated CDI bean can `@Inject` CDI beans (mapper, events, config). `BasicWebSocketConnector` handler lambdas cannot inject CDI beans — they capture from surrounding scope.

**Why `@ApplicationScoped` explicit:** `@WebSocketClient` Javadoc says "if no scope defined, Singleton is used." Explicit `@ApplicationScoped` is unambiguous and consistent with Quarkus idiom for stateful beans. Singleton and ApplicationScoped are both valid per the Javadoc; ApplicationScoped (proxied) is the casehub standard for `@ApplicationScoped` shared state.

**Why `Instance<WebSocketConnector<T>>`:** `WebSocketConnector` Javadoc explicitly states "connectors should not be reused; obtain a new connector instance via `Instance.get()`." Injecting `WebSocketConnector<T>` directly violates this contract.

```java
@WebSocketClient(clientId = "homeassistant", path = "/api/websocket")
@ApplicationScoped
public class HomeAssistantWebSocketClient {

    @Inject HomeAssistantConfig config;
    @Inject HomeAssistantEntityMapper mapper;
    @Inject Event<StateChangeEvent> stateEvents;
    @Inject Event<ProviderStatusEvent> statusEvents;
    @Inject ObjectMapper objectMapper;   // from quarkus-jackson (transitive)
    @Inject Instance<WebSocketConnector<HomeAssistantWebSocketClient>> connectorProvider;

    private volatile WebSocketClientConnection connection;
    private volatile ProviderStatus currentStatus = ProviderStatus.DISCONNECTED;
    private volatile boolean shuttingDown = false;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile ScheduledFuture<?> pongTimeoutFuture;   // one-shot per heartbeat ping
    private final AtomicInteger messageId = new AtomicInteger(0);
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);  // written from WebSocket thread + executor thread
    private final ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ha-reconnect");
            t.setDaemon(true);
            return t;
        });

    private int nextId() {
        return messageId.getAndUpdate(n -> n == Integer.MAX_VALUE ? 1 : n + 1);
    }

    public Uni<Void> connect() {
        return connectorProvider.get()
            .baseUri(URI.create(config.url()))
            .connect()
            .invoke(c -> this.connection = c)
            .replaceWithVoid();
    }

    public ProviderStatus currentStatus() { return currentStatus; }

    @OnOpen
    public void onOpen(WebSocketClientConnection conn) {
        fireStatus(ProviderStatus.CONNECTING);
        // HA sends auth_required first; no action until that arrives
    }

    @OnTextMessage
    public Uni<Void> onMessage(String text, WebSocketClientConnection conn) {
        JsonNode msg;
        try {
            msg = objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "Ignoring unparseable HA message: %s", text);
            return Uni.createFrom().voidItem();
        }
        String type = msg.path("type").asText("");
        return switch (type) {
            case "auth_required" -> sendAuth(conn);
            case "auth_ok"       -> onAuthOk(conn);
            case "auth_invalid"  -> { LOG.error("HA auth invalid — check token"); scheduleReconnect(); yield Uni.createFrom().voidItem(); }
            case "event"         -> onStateChanged(msg);
            case "pong"          -> { cancelPongTimeout(); yield Uni.createFrom().voidItem(); }
            case "result"        -> { handleResult(msg); yield Uni.createFrom().voidItem(); }
            default              -> { LOG.warnf("Unrecognised HA message type: %s", type); yield Uni.createFrom().voidItem(); }
        };
    }

    @OnClose
    public void onClose(WebSocketClientConnection conn, CloseReason reason) {
        if (!shuttingDown) {
            cancelHeartbeat();
            fireStatus(ProviderStatus.DISCONNECTED);
            scheduleReconnect();
        }
    }

    @OnError
    public void onError(WebSocketClientConnection conn, Throwable t) {
        if (!shuttingDown) {
            LOG.warnf(t, "HA WebSocket error");
            cancelHeartbeat();
            fireStatus(ProviderStatus.DISCONNECTED);
            scheduleReconnect();
        }
    }

    @PreDestroy
    public void stop() {
        shuttingDown = true;                                 // 1. suppress reconnect scheduling
        cancelHeartbeat();
        cancelPongTimeout();
        if (connection != null) connection.closeAndAwait(); // 2. close WebSocket — closeAndAwait() blocks until closed
                                                            //    close() returns cold Uni<Void> — does nothing without subscription
        executor.shutdownNow();                             // 3. cancel scheduled reconnects immediately
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> f = heartbeatFuture;
        if (f != null) f.cancel(true);
    }

    private void cancelPongTimeout() {
        ScheduledFuture<?> f = pongTimeoutFuture;
        if (f != null) { f.cancel(false); pongTimeoutFuture = null; }
    }

    // Called from ScheduledExecutorService thread — sendTextAndAwait() is safe here (not event loop)
    private void sendHeartbeat() {
        WebSocketClientConnection conn = connection;
        if (conn == null || conn.isClosed()) return;
        conn.sendTextAndAwait("{\"id\":" + nextId() + ",\"type\":\"ping\"}");
        pongTimeoutFuture = executor.schedule(this::handlePongTimeout,
            config.pongTimeoutSeconds(), TimeUnit.SECONDS);
    }

    private void handlePongTimeout() {
        LOG.warn("HA WebSocket pong timeout — reconnecting");
        WebSocketClientConnection conn = connection;
        if (conn != null) conn.closeAndAwait();
        scheduleReconnect();
    }
}
```

**State machine:**

```
OPEN
  onOpen()         → fireStatus(CONNECTING); await auth_required
  auth_required    → sendText({"type":"auth","access_token":"..."})
  auth_ok          → sendText({"id":N,"type":"subscribe_events","event_type":"state_changed"})
                     fireStatus(CONNECTED)
                     heartbeatFuture = executor.scheduleAtFixedRate(this::sendHeartbeat,
                         pingIntervalSeconds, pingIntervalSeconds, SECONDS)
  auth_invalid     → log ERROR; scheduleReconnect()

SUBSCRIBED
  type="event"     → mapOne(new_state), mapOne(old_state)
                     if before==null: changedCapabilities = Set.copyOf(after.capabilities().keySet())
                     else: deriveChangedCapabilities(before, after)
                     stateEvents.fireAsync(new StateChangeEvent(before, after, changed, Instant.now(), "homeassistant"))
  type="pong"      → cancelPongTimeout(); reconnectAttempt.set(0)
  type="result"    → log WARN if success=false; ignore if success=true
  other            → log WARN; ignore

HEARTBEAT (scheduleAtFixedRate, pingIntervalSeconds=30s, runs on executor daemon thread)
  sendHeartbeat()     → conn.sendTextAndAwait({"id":N,"type":"ping"})
                         pongTimeoutFuture = executor.schedule(handlePongTimeout, pongTimeoutSeconds, SECONDS)
  handlePongTimeout() → log WARN; conn.closeAndAwait(); scheduleReconnect()
  type="pong"         → cancelPongTimeout(); reconnectAttempt.set(0)

NOTE: HA uses application-level ping/pong JSON messages (sendTextAndAwait on executor thread).
      WebSocket protocol PING frames (sendPing/sendPingAndAwait) are NOT used — HA does not
      guarantee responding to protocol-level pings as an application heartbeat.
```

**Reconnection (exponential backoff + jitter):**

```java
private void scheduleReconnect() {
    int attempt = reconnectAttempt.getAndIncrement();   // AtomicInteger — thread-safe
    double base = config.reconnectBaseSeconds() * Math.pow(2, attempt);
    double capped = Math.min(base, config.reconnectMaxSeconds());
    double jittered = capped * (0.75 + 0.5 * ThreadLocalRandom.current().nextDouble());
    executor.schedule(() -> connect().subscribe().with(v -> {}, e -> scheduleReconnect()),
        (long)(jittered * 1000), TimeUnit.MILLISECONDS);
}
```

### 2.4 HomeAssistantProvider

```java
@ApplicationScoped
public class HomeAssistantProvider implements DeviceProvider {

    @Inject @RestClient HomeAssistantRestClient restClient;
    @Inject HomeAssistantWebSocketClient wsClient;
    @Inject HomeAssistantEntityMapper mapper;

    @PostConstruct
    void start() {
        wsClient.connect().subscribe().with(v -> {}, e -> LOG.warnf(e, "HA initial connect failed"));
    }

    public String providerId()   { return "homeassistant"; }
    public ProviderStatus status() { return wsClient.currentStatus(); }

    public Uni<List<DeviceEntity>> discover() {
        return restClient.getStates().map(mapper::mapAll);
    }

    public Uni<CommandResult> dispatch(DeviceCommand command) {
        ServiceCallSpec spec = buildServiceCall(command);   // pure in-memory — no Uni wrapper
        if (spec == null) return Uni.createFrom().item(CommandResult.FAILED);
        return restClient.callService(spec.domain(), spec.service(), spec.body())
            .map(resp -> resp.getStatus() < 300 ? CommandResult.SENT : CommandResult.FAILED)
            .onFailure(ProcessingException.class).recoverWithItem(CommandResult.FAILED)
            .onFailure(TimeoutException.class).recoverWithItem(CommandResult.TIMEOUT);
    }

    private ServiceCallSpec buildServiceCall(DeviceCommand command) {
        String entityId = command.targetDeviceId();
        String domain = entityId.contains(".") ? entityId.split("\\.")[0] : entityId;
        return switch (command.action()) {
            case DeviceCommand.ACTION_TURN_ON  -> new ServiceCallSpec(domain, "turn_on",
                new HaServiceCallDto(entityId, command.parameters()));
            case DeviceCommand.ACTION_TURN_OFF -> new ServiceCallSpec(domain, "turn_off",
                new HaServiceCallDto(entityId, Map.of()));
            case DeviceCommand.ACTION_SET_TEMPERATURE -> new ServiceCallSpec("climate", "set_temperature",
                new HaServiceCallDto(entityId, Map.of("temperature", command.parameters().get("temperature"))));
            case DeviceCommand.ACTION_LOCK   -> new ServiceCallSpec("lock",   "lock",   new HaServiceCallDto(entityId, Map.of()));
            case DeviceCommand.ACTION_UNLOCK -> new ServiceCallSpec("lock",   "unlock", new HaServiceCallDto(entityId, Map.of()));
            case DeviceCommand.ACTION_SET_POSITION -> new ServiceCallSpec("cover", "set_cover_position",
                new HaServiceCallDto(entityId, Map.of("position", command.parameters().get("position"))));
            case DeviceCommand.ACTION_SET_VOLUME -> {
                int raw = ((Number) command.parameters().get("volume")).intValue();   // Number not Integer — safe across JSON round-trips
                yield new ServiceCallSpec("media_player", "volume_set",
                    new HaServiceCallDto(entityId, Map.of("volume_level", raw / 100.0)));
            }
            default -> null;
        };
    }
}
```

---

## Section 3 — Type Mapping

`HomeAssistantEntityMapper` — `@ApplicationScoped`, constructor injection for testability:

```java
@ApplicationScoped
public class HomeAssistantEntityMapper {
    private final HomeAssistantConfig config;

    @Inject
    public HomeAssistantEntityMapper(HomeAssistantConfig config) {
        this.config = config;
    }

    public List<DeviceEntity> mapAll(List<HaStateDto> states) { ... }

    // Used by both REST (via mapAll) and WebSocket (via treeToValue conversion)
    public DeviceEntity mapOne(HaStateDto state) { ... }
}
```

WebSocket path: `objectMapper.treeToValue(stateNode, HaStateDto.class)` → `mapper.mapOne(dto)`. Unified with REST path — single mapping implementation.

Tests instantiate directly: `new HomeAssistantEntityMapper(testConfigImpl)` — no `@QuarkusTest` needed for mapper unit tests.

### 3.1 Domain → Type Table

| HA domain | Condition | casehub type |
|-----------|-----------|--------------|
| `switch` | — | `SwitchDevice` |
| `light` | — | `HomeAssistantLight extends LightDevice` |
| `climate` | — | `HomeAssistantThermostat extends ThermostatDevice` |
| `lock` | — | `HomeAssistantLock extends LockDevice` |
| `cover` | — | `CoverDevice` |
| `media_player` | — | `MediaPlayerDevice` |
| `fan` | — | `FanDevice` |
| `sensor` | `device_class` ∈ {`power`, `energy`} | `PowerSensor` |
| `sensor` / `binary_sensor` | `device_class` ∈ {`occupancy`, `motion`, `presence`} | `PresenceSensor` |
| `sensor` / `binary_sensor` | other `device_class` | `SensorDevice` |
| unknown domain | — | skip + log WARN |

### 3.2 Common Fields (all entities)

- `deviceId` = `entity_id` verbatim — accepted risk (§12 ARC42STORIES)
- `label` = `attributes.friendly_name` if present, else `entity_id`
- `available` = `!"unavailable".equals(state) && !"unknown".equals(state)`
- `lastUpdated` = `Instant.parse(state.last_updated)` — **requireNonNull in DeviceEntity**
- `tenancyId` = `config.tenancyId()`

### 3.3 SensorType Mapping

| HA `device_class` | `SensorType` |
|-------------------|-------------|
| `temperature` | `TEMPERATURE` |
| `humidity` | `HUMIDITY` |
| `motion` | `MOTION` |
| `door`, `window`, `garage_door` | `DOOR_WINDOW` |
| `carbon_dioxide` | `CO2` |
| `carbon_monoxide` | `CO` |
| `illuminance` | `LUX` |
| everything else | `GENERIC` |

### 3.4 hvac_mode → ThermostatMode Mapping

| HA `hvac_mode` | `ThermostatMode` |
|----------------|-----------------|
| `heat` | `HEAT` |
| `cool` | `COOL` |
| `heat_cool` | `AUTO` |
| `auto` | `AUTO` |
| `off` | `OFF` |
| `fan_only` | `FAN_ONLY` |
| `dry` | `DRY` |
| null / missing / unknown | `OFF` (fallback — log WARN) |

`ThermostatDevice.mode` is `requireNonNull` — the fallback to `OFF` (with log) prevents NPE when HA entities don't expose all attributes.

### 3.5 Type-Specific Attribute Mappings

**`PresenceSensor`** (binary_sensor with presence `device_class`):
- `present = "on".equals(state.state)`
- `lastSeen = Instant.parse(state.last_changed)` — **requireNonNull**

**`MediaPlayerDevice`**:
- `playing = "playing".equals(state.state)` — HA states: playing/paused/idle/off/unavailable; only "playing" means actively playing
- `volume = attributes.has("volume_level") ? (int)(attributes.get("volume_level").asDouble() * 100) : null`

**`CoverDevice`**:
- `position = attributes.has("current_position") ? Optional.of(attributes.get("current_position").asInt()) : Optional.empty()`
- `moving = "opening".equals(state.state) || "closing".equals(state.state)`

**`LightDevice` / `HomeAssistantLight`**:
- `on = "on".equals(state.state)`
- `brightness = attributes.has("brightness") ? attributes.get("brightness").asInt() : null`
- `colorTemp = attributes.has("color_temp") ? attributes.get("color_temp").asInt() : null`
- Supplement: `rgbColor` from `attributes.rgb_color`; `effect` from `attributes.effect`; `supportedColorModes` from `attributes.supported_color_modes`

**`ThermostatDevice` / `HomeAssistantThermostat`** (`climate` domain):
- `temperature_unit` attribute: `"°C"` → `CELSIUS`, `"°F"` → `FAHRENHEIT`; default `CELSIUS` if absent
- `currentTemperature = new Temperature(new BigDecimal(attributes.current_temperature.asText()), tempUnit)` — **requireNonNull**
- `targetTemperature = new Temperature(new BigDecimal(attributes.temperature.asText()), tempUnit)` — **requireNonNull**
- `mode = mapHvacMode(attributes.path("hvac_mode").asText(null))` — null-safe; falls back to `OFF`
- Supplement: `presetMode`, `swingMode`, `hvacAction` from matching attributes

**`LockDevice` / `HomeAssistantLock`**:
- `locked = "locked".equals(state.state)`
- Supplement: `changedBy` from `attributes.changed_by`; `codeSlot` from `attributes.code_slot`

**`SwitchDevice`**: `on = "on".equals(state.state)`

**`FanDevice`**: `on = "on".equals(state.state)`; `speed = attributes.has("percentage") ? attributes.get("percentage").asInt() : null`

**`PowerSensor`**:
- `device_class=power` → `power = parseOrNull(state.state)`, `energy = null`
- `device_class=energy` → `energy = parseOrNull(state.state)`, `power = null`

**`SensorDevice`** (numeric): `numericValue = parseOrNull(state.state)`; `unit = attributes.unit_of_measurement`

**`parseOrNull(String s)`** — package-private static helper in `HomeAssistantEntityMapper`. Called for all numeric state values; guarded by `available` check first (when `available=false`, state is `"unavailable"`/`"unknown"` — skip all numeric fields):

```java
private static BigDecimal parseOrNull(String s) {
    if (s == null || s.isBlank()) return null;
    try { return new BigDecimal(s); }
    catch (NumberFormatException e) { return null; }
}
```

**`binary_sensor` non-presence**: `binaryValue = "on".equals(state.state)`; `numericValue = null`; `unit = null`

**`null old_state` (new entity appears):**
```java
if (before == null) {
    changedCapabilities = Set.copyOf(after.capabilities().keySet());
} else {
    changedCapabilities = StateChangeEvent.deriveChangedCapabilities(before, after);
}
```
Never pass `null` as `before` to `deriveChangedCapabilities` — NPEs at `before.getClass()`.

### 3.6 Supplement Types — CAP_* and capabilities()

`HomeAssistantLight` — `CAP_RGB_COLOR`, `CAP_EFFECT`, `CAP_SUPPORTED_COLOR_MODES`; `capabilities()` calls `super.capabilities()` and adds these three.

`HomeAssistantThermostat` — `CAP_PRESET_MODE`, `CAP_SWING_MODE`, `CAP_HVAC_ACTION`; `capabilities()` calls `super.capabilities()` and adds these three.

`HomeAssistantLock` — `CAP_CHANGED_BY`, `CAP_CODE_SLOT`; `capabilities()` calls `super.capabilities()` and adds these two.

Without supplement CAP_* in `capabilities()`, `StateChangeEvent.deriveChangedCapabilities()` cannot detect changes to supplement fields — events for `hvacAction`, `rgbColor`, etc. would produce empty `changedCapabilities`.

---

## Section 4 — Command Dispatch

See `buildServiceCall()` in `HomeAssistantProvider`. `ServiceCallSpec` is a package-private record:

```java
record ServiceCallSpec(String domain, String service, HaServiceCallDto body) {}
```

**Failure recovery:**
- `ProcessingException` (network-level: connection refused, host unreachable) → `FAILED`
- `TimeoutException` → `TIMEOUT`
- HTTP 4xx/5xx (detected via `resp.getStatus()`) → `FAILED`

---

## Section 5 — Testing

### Unit tests (no Quarkus, direct instantiation)

`HomeAssistantEntityMapperTest`:
```java
// @WithDefault annotations only apply via SmallRye Config — not to anonymous implementations.
// All 7 methods must be implemented explicitly in plain JUnit tests.
HomeAssistantConfig testConfig = new HomeAssistantConfig() {
    public String url()               { return "http://localhost"; }
    public String token()             { return "test-token"; }
    public String tenancyId()         { return "t1"; }
    public int reconnectBaseSeconds() { return 5; }
    public int reconnectMaxSeconds()  { return 300; }
    public int pingIntervalSeconds()  { return 30; }
    public int pongTimeoutSeconds()   { return 10; }
};
HomeAssistantEntityMapper mapper = new HomeAssistantEntityMapper(testConfig);
```

Covers: all domain mappings, all attribute extractions (lastUpdated, lastSeen, isPresent, volume, position Optional), temperature unit resolution, hvac_mode table, SensorType resolutions (CO vs CO2), PowerSensor one-field-only, null old_state changedCapabilities, unknown domain skipped.

### Integration tests (`@QuarkusTest`)

`HomeAssistantProviderTest` — MockWebServer for REST and WebSocket; full state machine exercise; reconnect after close; heartbeat ping/pong lifecycle; pong resets reconnectAttempt.

### Structural tests

Supplement type `capabilities()` override + CAP_* constants verified per type. Enum size tests updated.

---

## Section 6 — Key Files

```
homeassistant/src/main/java/io/casehub/iot/homeassistant/
├── HomeAssistantProvider.java
├── HomeAssistantRestClient.java
├── HomeAssistantWebSocketClient.java
├── HomeAssistantEntityMapper.java      constructor-injected @ApplicationScoped
├── HomeAssistantLight.java
├── HomeAssistantThermostat.java
├── HomeAssistantLock.java
└── internal/
    ├── HaStateDto.java                 package-private — HA /api/states JSON shape
    ├── HaServiceCallDto.java           package-private — service call body
    └── ServiceCallSpec.java            package-private record — domain/service/body
```

---

## Out of Scope

- `MockReactiveDeviceProvider` — casehubio/iot#9
- PLATFORM.md — casehubio/parent#211
- OpenHAB provider — C4
- Bridge runtime — C5 (determines if `runtime/` module needed)
- HA entity `unique_id` stable deviceId — entity registry API; accepted risk per §12
- YAML fixture loading — casehubio/iot#8

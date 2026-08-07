# casehub-iot -- Contributor Guide

> Internal architecture reference for platform builders extending casehub-iot -- provider implementations, bridge internals, SPIs, webapp infrastructure, and testing.

**GitHub:** [casehubio/iot](https://github.com/casehubio/iot)

---

## Internal Architecture

### DeviceProvider SPI

`DeviceProvider` (`io.casehub.iot.api.spi.DeviceProvider`) is the provider contract:

```java
public interface DeviceProvider {
    String providerId();
    List<DeviceEntity> discover();
    CommandResult dispatch(DeviceCommand command);
    ProviderStatus status();
}
```

Provider implementations are CDI `@ApplicationScoped` beans -- auto-discovered via `@Any Instance<DeviceProvider>`. Discovery returns the full device inventory; dispatch sends a command to a specific device; status reports connection health.

All SPIs are **blocking** -- designed for virtual threads per ADR-0005. No `Uni<>` return types in the SPI layer. Implementations that need async internals (e.g. BridgeDeviceProvider WebSocket) block at the method boundary.

Provider activation uses `@LookupIfProperty(name = "casehub.iot.<provider>.enabled", stringValue = "true")` -- disabled providers are invisible to `Instance<DeviceProvider>`. All provider config properties must be `Optional<String>` to prevent SmallRye startup validation failure.

REST clients are created programmatically via `RestClientBuilder` (not `@RegisterRestClient`) -- base URLs are resolved at runtime to support auto-discovery. `ClientHeadersFactory` is silently ignored by `RestClientBuilder.register()` -- use `ClientRequestFilter` (plain JAX-RS provider) instead.

### DeviceEntity Builder Patterns

Four device types use a **two-level AbstractBuilder** pattern for vendor subclassing: `LightDevice`, `ThermostatDevice`, `LockDevice`, `CoverDevice`. These have `protected` constructors.

Seven device types use a **single-level Builder** pattern: `SwitchDevice`, `SensorDevice`, `PresenceSensor`, `PowerSensor`, `MediaPlayerDevice`, `FanDevice`, `CameraDevice`. These expose `toBuilder()` and `static builder()` methods.

All types use `@JsonDeserialize(builder = X.Builder.class)` with `@JsonPOJOBuilder(withPrefix = "")` for Jackson deserialization.

### DeviceTypeIdResolver

`io.casehub.iot.api.DeviceTypeIdResolver` handles polymorphic serialization of `DeviceEntity` subtypes. Compound type IDs follow the format `DEVICE_CLASS:SimpleClassName` (e.g. `LIGHT:HomeAssistantLight`). Provider-specific supplement types must register via `DeviceTypeIdResolver.registerType(compoundId, type)` at startup for cross-process deserialization.

### Vendor Supplement Types

Provider modules extend common types only for fields that have no cross-vendor equivalent. Common interface first, supplement last resort.

**Home Assistant (`homeassistant` module):**
- `HomeAssistantThermostat extends ThermostatDevice` -- `presetMode`, `swingMode`, `hvacAction`
- `HomeAssistantLight extends LightDevice` -- `rgbColor`, `effect`, `supportedColorModes`
- `HomeAssistantLock extends LockDevice` -- `changedBy`, `codeSlot`

**OpenHAB (`openhab` module):**
- `OpenHabThermostat extends ThermostatDevice` -- `heatingDemand`, `coolingDemand`
- `OpenHabLight extends LightDevice` -- `hsb` (HSB colour)
- `OpenHabRollershutter extends CoverDevice` -- inverted position semantics (OH-specific)

---

## Full Module Inventory

| Module | Artifact | Layer | Contents |
|--------|----------|-------|----------|
| `api` | `casehub-iot-api` | L1+L2 | Core SPIs (`DeviceProvider`, `DeviceRegistry`, `DeviceStateHistoryProvider`), 11 device subclasses, `StateChangeEvent`, `DeviceCommand`, `CommandResult`, `IoTCloudEventAdapter`, `IoTCommandAuditEvent`, enums (`DeviceClass`, `SensorType`, `ThermostatMode`, `ProviderStatus`), `CdiDeviceRegistry @ApplicationScoped @DefaultBean`, `DeviceTypeIdResolver`, `BridgeMessage` sealed interface (7 variants), `BridgeAuditEvent`, `BridgeAuditStore` SPI, `BridgeEventFilter` SPI, `Temperature` value type. Jackson polymorphic serialization annotations. Depends on `casehub-platform-api`. **Public API, semver discipline.** |
| `homeassistant` | `casehub-iot-homeassistant` | L3 | `HomeAssistantProvider @ApplicationScoped` -- REST API + WebSocket event stream. `HomeAssistantEntityMapper`, `HomeAssistantWebSocketClient`, `HomeAssistantRestClient`, `HomeAssistantDiscovery` (mDNS). Supplement types: `HomeAssistantThermostat`, `HomeAssistantLight`, `HomeAssistantLock`. Config via `@ConfigMapping(prefix = "casehub.iot.homeassistant")`: enabled, url, token, reconnect params, ping/pong, discovery timeout. |
| `openhab` | `casehub-iot-openhab` | L4 | `OpenHabProvider @ApplicationScoped` -- REST API + SSE event stream. Layered Equipment+Thing discovery: `OpenHabEntityMapper` (semantic model), `OpenHabThingResolver` (thing-type category + channel inference), `OpenHabSseClient` (4-phase pipeline with dual cache layers), `OpenHabDeviceBuilder` (shared construction), `OpenHabRestClient`, `OpenHabAuthFilter` (`ClientRequestFilter`). Supplement types: `OpenHabThermostat`, `OpenHabLight`, `OpenHabRollershutter`. Config via `@ConfigMapping(prefix = "casehub.iot.openhab")`: enabled, url, auth (bearer/basic structured), reconnect params, coalescing window, thing discovery toggle, discovery timeout. |
| `testing` | `casehub-iot-testing` | L5 | `MockDeviceProvider`, `MockDeviceRegistry`, `Fixtures` (11 Java factory methods + `standardHome()`), `DeviceFixtureLoader` (YAML parsing), `DeviceTypeHandler` SPI (11 handlers registered via `ServiceLoader`), `DeviceTypeRegistry`, `DeviceFixtureDefaults`, `StateChangeEventPublisher` (`@ApplicationScoped`). Test scope only. |
| `bridge` | `casehub-iot-bridge` | L6 | Local bridge agent -- `BridgeEventObserver` (`@ObservesAsync StateChangeEvent`), `BridgeCloudClient` (WebSocket to cloud), `BridgeCommandDispatcher`, `BridgeConnectionManager`, `BridgeFilterChain` (CDI-discovered filter chain), `BridgeEventStore` SPI with `InMemoryBridgeEventStore` and `PersistentBridgeEventStore` implementations, `BridgeAgentConfig`. Standalone Quarkus app. |
| `bridge-server` | `casehub-iot-bridge-server` | L6 | Cloud-side library: `BridgeDeviceProvider implements DeviceProvider` -- remote devices look local. `BridgeWebSocketEndpoint` (WebSocket server), `BridgeConnectionRegistry`, `BridgeReadinessCheck`, `BridgeServerConfig`, `DeviceIdNamespacer` (tenancy-prefixed device IDs). Audit: `LoggingBridgeAuditObserver` (always active), `StoringBridgeAuditObserver` (persists to `BridgeAuditStore`), `NoOpBridgeAuditStore @DefaultBean`. |
| `bridge-persistence-memory` | `casehub-iot-bridge-persistence-memory` | L6 | `InMemoryBridgeAuditStore` -- `@Alternative @Priority(100)`, bounded ring buffer. For Pi and test isolation. |
| `bridge-persistence-jpa` | `casehub-iot-bridge-persistence-jpa` | L6 | `JpaBridgeAuditStore` -- durable audit persistence with JSONB message storage. Flyway migrations, Testcontainers PostgreSQL for tests. Configurable `@Scheduled` purge job for audit data retention. |
| `mcp` | `casehub-iot-mcp` | -- | `IoTDeviceMcpTool @ApplicationScoped` -- 4 MCP tools (`iot_get_devices`, `iot_get_state`, `iot_send_command`, `iot_get_history`). Library module. Host-agnostic: injects `DeviceRegistry`, `Instance<DeviceProvider>`, `Instance<DeviceStateHistoryProvider>`, `Event<IoTCommandAuditEvent>`, `McpIdentityContext`. All methods `@Blocking` + `@RolesAllowed(IoTRoles.VIEWER/OPERATOR)`. Tenancy-filtered via `McpIdentityContext` (graceful fallback for unsecured hosts). |
| `webapp-api` | `casehub-iot-webapp-api` | L7 | Reusable IoT ganglia (`TemperatureThresholdGanglion`, `PowerAnomalyGanglion`, `MotionAtTimeGanglion`, `LockStateGanglion`, `DeviceUnavailableGanglion`), case descriptors (`HvacAnomalyCaseDescriptor`, `SafetyAlertCaseDescriptor`, `SecurityAlertCaseDescriptor`, `GenericResponseCaseDescriptor`), worker functions (`DeviceCommandWorkerFunction`, `HouseholdNotificationWorkerFunction`, `HumanDecisionWorkerFunction`), `IoTActionRiskClassifier`, `DismissalGangliaObserver`, REST request/response records, CBR classes (`IoTCbrFeatureSchemas`, `IoTCbrFeatureExtractors`, `IoTCbrRetrievalService`, `ResolutionConfidence`, `ResolutionSuggestion`, `SuppressionEvaluator`, `WorkItemPredictionService`, `WorkItemFeatureExtractor`), AI resolution records (`AiResolutionPlan`, `AiResolutionPromptBuilder`, `PlannedActionSpec`, `ExecutedActionResult`, `AiEscalationContext`). Tier 1 -- no JPA, no Quarkus runtime. |
| `webapp-drools` | `casehub-iot-webapp-drools` | L8 | DroolsCEP temporal pattern ganglia: `SustainedTemperatureRiseGanglion`, `MultiRoomMotionGanglion`. Event types: `TemperatureReading`, `MotionEvent`. Extractors: `TemperatureReadingExtractor`, `MotionEventExtractor`. Activates by classpath presence. |
| `webapp` | `casehub-iot-webapp` | L9 | Standalone Quarkus app -- operational console. REST resources: `DeviceResource`, `CaseResource`, `SituationResource`, `WorkItemResource`, `HealthResource`, `ProviderResource`, `BridgeResource`, `DeviceSseResource`. Case engine wiring: `HvacAnomalyCaseHub`, `SafetyAlertCaseHub`, `SecurityAlertCaseHub`, `GenericResponseCaseHub`, `IoTCaseInputContributor`. CBR: `IoTCbrSchemaRegistration`, `IoTCbrRetrievalServiceProducer`, `CbrRetentionJob`, `WorkItemPredictionServiceProducer`, `WorkItemOutcomeRecorder`. Persistence: `JpaDeviceStateHistoryProvider`, `IoTDeviceStateHistoryEntity`, `IoTCaseCommandLogEntity`, `IoTSituationDefinitionEntity`, `StateHistoryRetentionJob`, `SuppressionLogEntry`. AI resolution: `IoTAiResolutionAgent`, `IoTAiResolutionConfig`, `IoTCbrReEvaluationObserver`. Triage: `IoTQueueViewInitializer`, `IoTTriageConfig`. Suppression: `IoTSuppressionProducer`, `SuppressionConfigMapping`, `SuppressionLogObserver`. Observer: `StateChangeHistoryObserver`. Situation: `JpaRuntimeSituationDefinitionProvider`. TypeScript pages via Quinoa. Three-datasource Flyway layout. |

---

## Provider Architecture

### Home Assistant

1:1 entity mapping -- each HA `entity_id` maps to one `DeviceEntity`. 

**Discovery:** REST `GET /api/states` -- `HomeAssistantEntityMapper` maps HA domain + `device_class` to the device hierarchy.

**Real-time:** WebSocket API (`/api/websocket`) via `HomeAssistantWebSocketClient`. Auth handshake, then `subscribe_events` for `state_changed`. Each event carries `old_state` and `new_state` -- diff produces `changedCapabilities`. Reconnect with exponential backoff + jitter (base 5s, max 5min). Ping/pong keep-alive (30s/10s defaults).

**Auto-discovery:** `HomeAssistantDiscovery` uses JmDNS to find `_home-assistant._tcp.local.` when URL is not configured. Timeout configurable via `discovery-timeout-seconds`.

**Command dispatch:** `POST /api/services/{domain}/{service}` with `entity_id` and service data mapped from `DeviceCommand.parameters`.

**Config:** `@ConfigMapping(prefix = "casehub.iot.homeassistant")` on `HomeAssistantConfig`:
- `enabled` (boolean, default `false`)
- `url` (Optional<String>)
- `token` (Optional<String>)
- `reconnectBaseSeconds` (int, default `5`)
- `reconnectMaxSeconds` (int, default `300`)
- `pingIntervalSeconds` (int, default `30`)
- `pongTimeoutSeconds` (int, default `10`)
- `discoveryTimeoutSeconds` (int, default `5`)

### OpenHAB

Equipment Group mapping -- one OpenHAB Equipment Group with multiple member Point items maps to a single `DeviceEntity`.

**Discovery (Phase 1 -- Equipment):** REST `GET /rest/items?type=Equipment&recursive=true`. `OpenHabEntityMapper` maps semantic tags (e.g. `Measurement+Temperature` to current temperature, `Control+Switch` to on/off state, `Setpoint+Temperature` to target temperature).

**Discovery (Phase 2 -- Things):** `OpenHabThingResolver` discovers Things directly when `thingDiscoveryEnabled` is true (default). Resolves `OpenHabThingDto` and linked items to `ResolvedDeviceFields` using a two-signal model: thing-type category (binding metadata) merged with channel itemType inference. Priority-based channel scanning: Color > Dimmer > Rollershutter > Player > Power/Energy > Thermostat > Temperature > Humidity > Switch > Contact > Number. `OpenHabDeviceBuilder` is shared between Equipment and Thing paths.

**Real-time:** SSE event stream via `OpenHabSseClient`. `connect()` runs a 4-phase pipeline: Equipment mapping, Thing index build, Thing mapping for unmapped Things, item state fetch for unmapped Things. Dual cache layers: `equipmentCache`/`deviceCache` (Equipment path) and `thingCache`/`thingDeviceCache` (Thing path). Equipment-level coalescing -- individual item state changes are resolved to their parent Equipment, re-mapped, and emitted as a single `StateChangeEvent` after a configurable coalescing window (default 50ms). SSE `ThingStatusInfoChangedEvent` updates availability on both layers.

**Auto-discovery:** mDNS (`_openhab-server._tcp.local.`) with SSDP fallback (raw UDP multicast M-SEARCH on `239.255.255.250:1900`).

**Command dispatch:** `POST /rest/items/{itemName}` -- target item resolved from semantic tags matching the command action.

**Auth:** Structured config: `auth.bearer.token` for bearer auth, `auth.basic.username` + `auth.basic.password` for HTTP basic. `OpenHabAuthFilter implements ClientRequestFilter` (not `ClientHeadersFactory` -- that is silently ignored by `RestClientBuilder.register()`).

**Config:** `@ConfigMapping(prefix = "casehub.iot.openhab")` on `OpenHabConfig`:
- `enabled` (boolean, default `false`)
- `url` (Optional<String>)
- `auth` (nested: `bearer.token`, `basic.username`/`basic.password`)
- `reconnectBaseSeconds` (int, default `5`)
- `reconnectMaxSeconds` (int, default `300`)
- `coalesceWindowMs` (int, default `50`)
- `thingDiscoveryEnabled` (boolean, default `true`)
- `discoveryTimeoutSeconds` (int, default `10`)

### Provider Activation Pattern

Both providers ship in a single Docker image. Activation via:

```java
@ApplicationScoped
@LookupIfProperty(name = "casehub.iot.homeassistant.enabled", stringValue = "true")
public class HomeAssistantProvider implements DeviceProvider { ... }
```

When `enabled` is absent or not `"true"`, the provider bean is not instantiated. No `@PostConstruct` runs, no REST client creation, no guard code needed. All consumption goes through `Instance<DeviceProvider>`.

All config properties use `Optional<String>` -- SmallRye Config validates `@ConfigMapping` properties at startup regardless of bean lifecycle, so required properties on a disabled provider would crash the app before `@LookupIfProperty` evaluates.

### Lazy Init

Both providers have empty `@PostConstruct` methods -- lazy init is used instead to avoid startup races with test servers. REST clients are created on first `discover()` or `status()` call via `RestClientBuilder.newBuilder().baseUri(resolvedUrl).register(authFilter).build(...)`.

---

## Bridge Architecture

### Bridge Agent (local, `bridge` module)

Standalone Quarkus app running on-premises or at the edge. Components:

- **`BridgeEventObserver`** -- `@ObservesAsync StateChangeEvent`, forwards to cloud via `BridgeCloudClient`
- **`BridgeCloudClient`** -- WebSocket client to bridge-server
- **`BridgeConnectionManager`** -- manages connection lifecycle with reconnection
- **`BridgeCommandDispatcher`** -- receives commands from cloud, routes to local `DeviceProvider` via `DeviceRegistry` lookup
- **`BridgeFilterChain`** -- CDI-discovered chain of `BridgeEventFilter` implementations. Filters run by `priority()` (lower first); any filter can suppress an event with reason.
- **`BridgeEventStore`** SPI with two implementations: `InMemoryBridgeEventStore` (volatile), `PersistentBridgeEventStore` (durable store-and-forward for crash-resilient event buffering)
- **`BridgeAgentConfig`** -- `@ConfigMapping` for cloud endpoint, reconnection params

### Bridge Server (cloud, `bridge-server` module)

Library added to cloud Quarkus apps. Components:

- **`BridgeDeviceProvider implements DeviceProvider`** -- remote devices look local to cloud consumers via the `DeviceProvider` SPI. `DeviceIdNamespacer` prefixes device IDs with tenancy for multi-tenant isolation.
- **`BridgeWebSocketEndpoint`** -- WebSocket server endpoint. Handles `@OnOpen`, `@OnClose`, `@OnTextMessage`. Deserializes `BridgeMessage` variants. Fires `BridgeAuditEvent` for every protocol message, then processes (e.g. fires `StateChangeEvent` for `StateChange` messages).
- **`BridgeConnectionRegistry`** -- tracks active connections by tenancy
- **`BridgeReadinessCheck`** -- Quarkus health check
- **`BridgeServerConfig`** -- `@ConfigMapping`

### Bridge Audit Trail

`BridgeAuditEvent` CDI event with `BridgeAuditEventType` enum (8 types: `STATE_CHANGE`, `REPLAYED_STATE_CHANGE`, `STATE_SNAPSHOT`, `PROVIDER_STATUS_CHANGE`, `COMMAND_SENT`, `COMMAND_RESPONSE`, `AGENT_CONNECTED`, `AGENT_DISCONNECTED` -- no HEARTBEAT).

Dual-trail pattern:
- **`LoggingBridgeAuditObserver`** -- always active, structured JSON logging
- **`StoringBridgeAuditObserver`** -- persists to `BridgeAuditStore` SPI

`BridgeAuditStore` SPI implementations:
- `NoOpBridgeAuditStore` (`@DefaultBean` in bridge-server) -- fallback when no persistence module is on classpath
- `InMemoryBridgeAuditStore` (`@Alternative @Priority(100)` in bridge-persistence-memory) -- bounded ring buffer
- `JpaBridgeAuditStore` (bridge-persistence-jpa) -- durable PostgreSQL persistence with JSONB, Flyway migrations, configurable `@Scheduled` purge job

### Bridge Wire Protocol

`BridgeMessage` is a sealed interface in `api/bridge/` with 7 variants: `StateChange`, `StateSnapshot`, `ProviderStatusChange`, `Command`, `CommandResponse`, `Heartbeat`, `ReplayedStateChange`. All carry `tenancyId` and `timestamp`. Jackson `@JsonTypeInfo(use = NAME, property = "@type")` with `@JsonSubTypes`.

### Docker Deployment

**Image:** `ghcr.io/casehubio/iot-bridge` -- `eclipse-temurin:21-jre-alpine`, non-root user (UID 1001), Quarkus fast-jar layout. Multi-arch: ARM64 (Raspberry Pi 4/5) + x86_64 via `docker buildx`.

**Docker Compose** (`bridge/docker-compose.yml`): single service, `network_mode: host` (required for mDNS/SSDP multicast discovery), named volume for persistent event store, health check via `/q/health/ready`.

**Dockerfile:** `bridge/src/main/docker/Dockerfile.jvm`

**Deployment guide:** `bridge/DEPLOYMENT.md` -- architecture diagram, prerequisites, configuration reference, network requirements, data persistence, updating, troubleshooting, security considerations, multi-platform support.

### Deployment Topologies

6 topologies: SaaS, hybrid, multi-site, constrained edge, dev, multiple consumers. Hybrid mode adds Drools rules and YAML triggers to the bridge deployment as classpath dependencies -- standard Quarkus CDI extension. They fire locally via `@ObservesAsync StateChangeEvent` on the bridge's own CDI container. No bridge-specific configuration -- "hybrid" is a deployment topology choice, not a bridge mode.

---

## Webapp Architecture

### Module Tiers

- **webapp-api** (L7, Tier 1) -- Pure domain logic. No JPA, no Quarkus runtime. Contains ganglia, case descriptors, worker functions, risk classification, CBR logic, AI resolution records, REST request/response types.
- **webapp-drools** (L8) -- DroolsCEP temporal pattern ganglia. Activates by classpath presence. Contains `SustainedTemperatureRiseGanglion`, `MultiRoomMotionGanglion` with their event types and extractors.
- **webapp** (L9) -- Standalone Quarkus app wiring everything together.

### REST API Surface

| Resource | Base Path | Key Operations |
|----------|-----------|----------------|
| `DeviceResource` | `/api/devices` | List (with filters), get by ID, dispatch command, state history |
| `DeviceSseResource` | `/api/devices/stream` | SSE stream -- initial snapshot + replace operations on state changes |
| `CaseResource` | `/api/cases` | List, get detail, get CBR suggestions, accept suggestion |
| `SituationResource` | `/api/situations` | CRUD definitions, list active, dismiss, get suggestions, suppression history/stats/override |
| `WorkItemResource` | `/api/workitems` | List, claim, complete, get outcome prediction |
| `HealthResource` | `/api/health` | System overview -- providers, devices, bridges, connectivity |
| `ProviderResource` | `/api/providers` | List status, get by ID, refresh all/single |
| `BridgeResource` | `/api/bridge` | Active connections, paginated audit trail with filters |

### Case Engine Integration

Four case types, each with a case descriptor in webapp-api and a CaseHub wiring class in webapp:

| Case Type | Descriptor | CaseHub Class |
|-----------|------------|---------------|
| HVAC anomaly | `HvacAnomalyCaseDescriptor` | `HvacAnomalyCaseHub` |
| Safety alert | `SafetyAlertCaseDescriptor` | `SafetyAlertCaseHub` |
| Security alert | `SecurityAlertCaseDescriptor` | `SecurityAlertCaseHub` |
| Generic response | `GenericResponseCaseDescriptor` | `GenericResponseCaseHub` |

`IoTCaseInputContributor` implements the `CaseInputContributor` SPI (`casehub-ras-api`) -- feeds device metadata into case working layer.

### Ganglia (Situational Awareness)

**Standard ganglia** (webapp-api, always available):
- `TemperatureThresholdGanglion`
- `PowerAnomalyGanglion`
- `MotionAtTimeGanglion`
- `LockStateGanglion`
- `DeviceUnavailableGanglion`

**DroolsCEP ganglia** (webapp-drools, classpath-activated):
- `SustainedTemperatureRiseGanglion` -- detects sustained temperature rise over time
- `MultiRoomMotionGanglion` -- detects correlated motion across rooms

`DismissalGangliaObserver` -- closes ganglia on situation dismissal.

### CBR Infrastructure

Case-based reasoning for IoT situation resolution. Logic in webapp-api, CDI wiring in webapp.

**Feature schemas** (`IoTCbrFeatureSchemas`): 4 `CbrFeatureSchema` instances -- `hvacAnomaly()`, `safetyAlert()`, `securityAlert()`, `genericResponse()`. Each includes common fields (deviceClass, roomType, hourOfDay, dayType, season) plus schema-specific fields.

**Retrieval** (`IoTCbrRetrievalService`): wraps `CbrCaseMemoryStore`, builds `CbrQuery`, returns `List<ResolutionSuggestion>` with `caseId`, `similarityScore`, `problem`, `solution`, `outcome`, `confidence`, `matchedFeatures`, `featureSimilarities`, `planSteps`.

**Feature extractors** (`IoTCbrFeatureExtractors`): static extractors per case type -- `extractHvacAnomalyFeatures`, `extractSafetyAlertFeatures`, `extractSecurityAlertFeatures`, `extractGenericResponseFeatures`. Derives temporal features from `eventTimestamp`.

**Confidence model** (`ResolutionConfidence`): `bestSimilarity`, `outcomeConsistency`, `matchCount`, `ConfidenceLevel` (HIGH/MEDIUM/LOW/NONE). Static `compute()` method.

**False-positive suppression** (`SuppressionEvaluator`): evaluates whether a situation is a false positive based on CBR history. Uses `SuppressionConfig` with `SuppressionTier` thresholds and `IoTSuppressionTriggerPolicy`.

**Work item prediction** (`WorkItemPredictionService`): predicts outcome distribution, resolution time (p50/p90), and suggested assignees based on CBR history. Uses `WorkItemFeatureExtractor` and `WorkItemContext`.

**CDI wiring (webapp):**
- `IoTCbrSchemaRegistration` -- registers all schemas on `@Observes StartupEvent`
- `IoTCbrRetrievalServiceProducer` -- CDI `@Produces` method
- `CbrRetentionJob` -- `@Scheduled` purge for stale CBR cases
- `WorkItemPredictionServiceProducer` -- CDI `@Produces` method
- `WorkItemOutcomeRecorder` -- records outcomes back to CBR store
- `IoTSuppressionProducer` -- CDI `@Produces` for suppression evaluator
- `SuppressionConfigMapping` -- `@ConfigMapping` for suppression thresholds
- `SuppressionLogObserver` -- logs suppression events

### AI Resolution Agent

`IoTAiResolutionAgent` (`@ApplicationScoped`) in the webapp module -- LLM-driven autonomous resolution agent:

- Polls via `@Scheduled` for new case queue entries
- Claims entries from the queue via `CaseQueueService`
- Gathers CBR suggestions via `IoTCbrRetrievalService`
- Builds LLM prompt via `AiResolutionPromptBuilder` from case context, CBR suggestions, and available actions
- Calls LLM (with retry, semaphore-gated concurrency) to produce `AiResolutionPlan`
- Risk-checks planned actions via `ActionRiskClassifier`
- Executes autonomous-safe actions (device commands, notifications); escalates high-risk actions
- Sweeps stale entries via timeout
- Records escalation context for human review

**Config:** `IoTAiResolutionConfig` -- `@ConfigMapping` for poll interval, concurrency, timeouts.

### CBR Re-Evaluation on Context Changes

`IoTCbrReEvaluationObserver` (`@ApplicationScoped`) -- event-driven observer that detects when case context changes while a case is in the AI resolution queue:

- Observes `CaseContextUpdatedEvent` (engine CDI event) for working layer changes
- Filters by AI queue membership and debounces (30s per case)
- Re-runs CBR retrieval with current features via `IoTCbrRetrievalService`
- If similarity band drops below AI threshold, escalates to `iot-operator-assisted` or `iot-operator-manual` via `CaseQueueService.escalate()`
- Downward-only re-routing (AI → operator queues, never the reverse)
- Interacts with the agent through queue state -- existing status guard handles race conditions

### Persistence

Three datasource layout with Flyway migrations:

- **Device state history:** `IoTDeviceStateHistoryEntity`, `JpaDeviceStateHistoryProvider` (implements `DeviceStateHistoryProvider` SPI), `StateHistoryRetentionJob`
- **Case command log:** `IoTCaseCommandLogEntity`
- **Situation definitions:** `IoTSituationDefinitionEntity`, `JpaRuntimeSituationDefinitionProvider`
- **Suppression log:** `SuppressionLogEntry`

`StateChangeHistoryObserver` (`@ObservesAsync StateChangeEvent`) persists state snapshots to the history table.

### Triage

- `IoTQueueViewInitializer` -- sets up case queue views on startup
- `IoTTriageConfig` -- `@ConfigMapping` for triage parameters
- `IoTCbrCaseQueueRoutingStrategy` -- CBR-aware case triage routing

---

## Testing Infrastructure

### Mock Providers

- **`MockDeviceProvider`** -- implements `DeviceProvider`. Programmatic device registration (`addDevice`, `removeDevice`, `clear`), configurable dispatch result (`setDispatchResult`), command recording (`dispatchedCommands()`), status override (`setStatus`). Not thread-safe -- designed for sequential test use.

- **`MockDeviceRegistry`** -- implements `DeviceRegistry`. In-memory device store with `addDevice`, `addDevices`, `clear`. `refresh()` is a no-op.

### Fixture Mechanisms

Two authoring paths producing `List<DeviceEntity>`:

**1. Programmatic fixtures (`Fixtures`):**
Static factory methods for every device type:
- `hallwaySwitch()`, `livingRoomLight()`, `livingRoomThermostat()`, `outdoorTemperature()`, `frontDoorPresence()`, `solarPanel()`, `frontDoorLock()`, `bedroomBlinds()`, `livingRoomSpeaker()`, `bedroomFan()`, `securityCamera()`
- `standardHome()` -- returns all 11 devices as an immutable list

Constants: `DEFAULT_TENANT = "default-tenant"`, `EPOCH = Instant.parse("2026-01-01T00:00:00Z")`

**2. YAML-driven fixtures (`DeviceFixtureLoader`):**
- `DeviceFixtureLoader.load(String classpathResource)` -- static convenience method
- Parses YAML files with optional `defaults` block (tenancyId, lastUpdated, available) and `devices` list
- Each device must have a `type` field matching a `DeviceTypeHandler` type name
- 11 handlers registered via `ServiceLoader` (`META-INF/services/io.casehub.iot.testing.DeviceTypeHandler`): switch, light, thermostat, sensor, presence_sensor, power_sensor, lock, cover, media_player, fan, camera
- `DeviceTypeRegistry.discover()` uses `ServiceLoader.load()` to find all handlers
- `DeviceTypeHandler.applyCommonFields()` sets base fields; each handler adds type-specific parsing

### StateChangeEventPublisher

`@ApplicationScoped` CDI bean. `publish(DeviceEntity before, DeviceEntity after, String providerId)` auto-derives `changedCapabilities` and fires via `Event.fireAsync()`. Returns `CompletionStage<StateChangeEvent>` for test synchronization.

---

## Configuration Reference

### Tenancy

Single root property: `casehub.iot.tenancy-id` (env var `CASEHUB_IOT_TENANCY_ID`). Injected via `@ConfigProperty(name = "casehub.iot.tenancy-id")` across all modules.

### Home Assistant (`casehub.iot.homeassistant.*`)

| Property | Type | Default | Purpose |
|----------|------|---------|---------|
| `enabled` | `boolean` | `false` | Activates provider |
| `url` | `Optional<String>` | -- | HA URL (mDNS fallback) |
| `token` | `Optional<String>` | -- | Long-lived access token |
| `reconnect-base-seconds` | `int` | `5` | Backoff base |
| `reconnect-max-seconds` | `int` | `300` | Backoff cap |
| `ping-interval-seconds` | `int` | `30` | WebSocket keep-alive |
| `pong-timeout-seconds` | `int` | `10` | Pong deadline |
| `discovery-timeout-seconds` | `int` | `5` | mDNS discovery timeout |

### OpenHAB (`casehub.iot.openhab.*`)

| Property | Type | Default | Purpose |
|----------|------|---------|---------|
| `enabled` | `boolean` | `false` | Activates provider |
| `url` | `Optional<String>` | -- | OpenHAB URL (mDNS/SSDP fallback) |
| `auth.bearer.token` | `String` | -- | API token |
| `auth.basic.username` | `String` | -- | Basic auth username |
| `auth.basic.password` | `String` | -- | Basic auth password |
| `reconnect-base-seconds` | `int` | `5` | Backoff base |
| `reconnect-max-seconds` | `int` | `300` | Backoff cap |
| `coalesce-window-ms` | `int` | `50` | SSE event coalescing window |
| `thing-discovery-enabled` | `boolean` | `true` | Enable Thing-scoped discovery |
| `discovery-timeout-seconds` | `int` | `10` | mDNS/SSDP discovery timeout |

---

## Depended On By

| Repo | What it uses |
|------|-------------|
| `casehub-life` | Device discovery, state events, command dispatch for household automation |
| `casehub-ops` | IoT desired-state domain implementation |

---

## Current State

- All SPIs are blocking (virtual-thread-aligned per ADR-0005)
- `DeviceEntity.location()` is nullable -- populated by OpenHAB (from `thing.location()`), null for HA (area registry integration pending)
- Device class vocabulary aligned with Matter Device Type Library -- 11 device types
- Jackson annotations on `api` for `DeviceTypeIdResolver` polymorphic serialization -- compound type IDs (e.g. `LIGHT:HomeAssistantLight`)
- Device metadata flows into case working layer via `IoTCaseInputContributor` -- CDI implementation of `CaseInputContributor` SPI (`casehub-ras-api`)
- Docker image: `ghcr.io/casehubio/iot-bridge` (JVM, multi-arch ARM64+x86_64)
- ARC42STORIES.MD chapters C1-C7 all complete
- IoTAiResolutionAgent with LLM-driven autonomous resolution, risk classification, and escalation
- CBR with 4 feature schemas, temporal recency weighting, false-positive suppression, work item outcome prediction, case triage routing

---

## Design Documents

- **ARC42STORIES:** `ARC42STORIES.MD` (root) -- full architectural narrative, C1-C7 complete
- **Foundation design spec:** `docs/superpowers/specs/2026-06-05-iot-foundation-design.md`
- **Bridge deployment:** `bridge/DEPLOYMENT.md`
- **Specs:** `docs/superpowers/specs/` -- 23 design specs covering all features
- **Plans:** `docs/superpowers/plans/` and `docs/plans/`

---

## Open Issues

| # | Title | Area |
|---|-------|------|
| 42 | PostgreSQL table partitioning for bridge_audit_event | bridge-persistence-jpa |
| 46 | Evaluate extracting webapp domain logic to application-tier repo | webapp |
| 48 | Epic: Case-Based Reasoning (CBR) for IoT situation handling | webapp (epic) |
| 67 | Household notifications via platform subscription engine + connector bridge | webapp |
| 74 | RBAC, tenancy filtering, and principal propagation for MCP tools | mcp |
| 77 | WebSocket/SSE streaming of device state changes via MCP | mcp |
| 81 | Queue listing REST endpoints for AI resolution views | webapp |
| 82 | Re-routing on context changes (CBR re-evaluation) | webapp |
| 83 | Multi-turn LLM conversation for complex resolutions | webapp |
| 84 | Custom model fine-tuning and prompt versioning | webapp |
| 85 | Agent performance metrics and observability | webapp |

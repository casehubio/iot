# Bridge Operations and Audit — Design Spec

**Issues:** #32 (Docker Compose + deployment), #33 (provider auto-discovery), #34 (server-side audit event log)  
**Deferred:** #35 (BridgeAuditStore SPI for structured query/retrieval)  
**Branch:** `issue-32-bridge-ops-and-audit`  
**Date:** 2026-06-25

---

## Overview

Three operational capabilities for the bridge ecosystem:

1. **Docker Compose + deployment guide** — production container image and compose file for Raspberry Pi / Linux deployment
2. **Provider auto-discovery** — mDNS and SSDP discovery of Home Assistant and OpenHAB on the local network
3. **Server-side audit event log** — CDI-based audit trail for all bridge protocol messages, with optional compliance ledger

These are largely independent features unified by an operational theme: making the bridge deployable, self-configuring, and observable.

### Cross-Cutting: TenancyId Consolidation

All three `@ConfigMapping` interfaces (`BridgeAgentConfig`, `HomeAssistantConfig`, `OpenHabConfig`) currently define their own `tenancyId()` property. These MUST match in any valid deployment — providers stamp entities with their config's tenancyId, and the bridge stamps wire messages with its own. Three properties that must always hold the same value is a divergence bug waiting to happen.

**Fix:** Consolidate to a single `casehub.iot.tenancy-id` root property. Remove `tenancyId()` from all three `@ConfigMapping` interfaces. Every consumer injects via `@ConfigProperty(name = "casehub.iot.tenancy-id")` directly. One env var (`CASEHUB_IOT_TENANCY_ID`), zero divergence risk.

Affected files:
- `HomeAssistantEntityMapper` — 11 usages of `config.tenancyId()` → injected field
- `OpenHabEntityMapper` — `config.tenancyId()` → injected field
- `OpenHabSseClient` — `config.tenancyId()` passed to `OpenHabThingResolver` → injected field
- `BridgeConnectionManager` — `config.tenancyId()` for X-Tenancy-ID header → injected field
- `BridgeEventObserver` — `config.tenancyId()` for BridgeMessage stamping → injected field
- `BridgeCloudClient` — `config.tenancyId()` for Heartbeat message stamping → injected field

### Cross-Cutting: REST Client URL Binding

Provider modules currently bind REST client base URLs via SmallRye Config property expressions in `application.properties`:

```properties
# homeassistant/application.properties
quarkus.rest-client."homeassistant".url=${casehub.iot.homeassistant.url}

# openhab/application.properties
quarkus.rest-client."openhab".url=${casehub.iot.openhab.url}
quarkus.rest-client."openhab-sse".url=${casehub.iot.openhab.url}
```

SmallRye resolves `${...}` expressions at config startup — before CDI starts, before `@PostConstruct` runs, before `@LookupIfProperty` is evaluated. This creates two structural incompatibilities with the spec's design:

**Failure 1 — Disabled provider with absent URL:** Making `url` `Optional<String>` on the `@ConfigMapping` interface doesn't help. The property expression `${casehub.iot.homeassistant.url}` is in `application.properties`, resolved by SmallRye directly — not through the ConfigMapping. If the property doesn't exist, `NoSuchElementException` crashes the app at startup, even though the provider is disabled.

**Failure 2 — Auto-discovery produces a URL after REST client binding:** Even with a dummy default (`${casehub.iot.homeassistant.url:http://unused.local}`), the `@RestClient` bean has an immutable base URL baked in at config time. When mDNS discovers `http://192.168.1.50:8123` in `@PostConstruct`, the REST client still points at the dummy URL. Quarkus `@RestClient` base URLs cannot be changed after creation.

The WebSocket clients don't have this problem — `HomeAssistantWebSocketClient.connect()` uses `connectorProvider.get().baseUri(URI.create(config.url())).connect()`, resolving the URL programmatically at connect time.

**Fix — switch REST clients to programmatic creation via `RestClientBuilder`:**

Remove the property expressions from `application.properties`. In the provider's `@PostConstruct`, resolve the URL (from config or discovery), then create the REST client programmatically:

```java
@PostConstruct
void start() {
    String resolvedUrl = config.url()
        .orElseGet(() -> discovery.resolve(config.discoveryTimeoutSeconds()));

    this.restClient = RestClientBuilder.newBuilder()
        .baseUri(URI.create(resolvedUrl))
        .register(new BearerAuthFilter(config.token().orElseThrow()))
        .build(HomeAssistantRestClient.class);

    wsClient.connect(resolvedUrl);
}
```

The REST client interfaces keep their JAX-RS annotations (`@GET`, `@Path`, `@POST`) — these work identically with `RestClientBuilder`. The following annotations are removed:

| Annotation | Interface | Reason |
|------------|-----------|--------|
| `@RegisterRestClient(configKey = "homeassistant")` | `HomeAssistantRestClient` | No CDI registration — client created programmatically |
| `@ClientHeaderParam(name = "Authorization", value = "{lookupToken}")` | `HomeAssistantRestClient` | Auth handled by `ClientRequestFilter` on the builder |
| `lookupToken()` default method | `HomeAssistantRestClient` | Uses `ConfigProvider.getConfig().getValue(...)` which also fails on absent properties — replaced by filter |
| `@RegisterRestClient(configKey = "openhab")` | `OpenHabRestClient` | Same — programmatic creation |
| `@RegisterClientHeaders(OpenHabAuthHeadersFactory.class)` | `OpenHabRestClient` | `OpenHabAuthHeadersFactory` deleted — replaced by `OpenHabAuthFilter` (`ClientRequestFilter`) registered on builder |
| `@RegisterRestClient(configKey = "openhab-sse")` | `OpenHabSseRestClient` | Same — programmatic creation |
| `@RegisterClientHeaders(OpenHabAuthHeadersFactory.class)` | `OpenHabSseRestClient` | Same — `OpenHabAuthFilter` on builder |

**Auth migration:**

- **Home Assistant:** `lookupToken()` (which uses `ConfigProvider.getConfig().getValue("casehub.iot.homeassistant.token", ...)`) is replaced by a `ClientRequestFilter` registered on the `RestClientBuilder`. The filter adds the `Authorization: Bearer <token>` header using the token from the (now Optional) config, validated at provider startup.

- **OpenHAB:** `OpenHabAuthHeadersFactory` is deleted. `ClientHeadersFactory` is a MicroProfile extension interface, not a JAX-RS provider — `RestClientBuilder.register()` silently ignores it (no error, no auth header, 401 on every request). Replaced by `OpenHabAuthFilter implements ClientRequestFilter` — a plain POJO carrying the same logic (bearer vs basic, mutual exclusion check). Created in `@PostConstruct` with `new OpenHabAuthFilter(config.auth())` and registered on the builder via `.register(authFilter)`.

**Affected files:**

- `homeassistant/src/main/resources/application.properties` — remove `quarkus.rest-client."homeassistant".url` expression and timeouts
- `openhab/src/main/resources/application.properties` — remove `quarkus.rest-client."openhab".url` and `"openhab-sse".url` expressions and timeouts
- `HomeAssistantRestClient` — remove `@RegisterRestClient`, `@ClientHeaderParam`, `lookupToken()` method
- `OpenHabRestClient` — remove `@RegisterRestClient`, `@RegisterClientHeaders`
- `OpenHabSseRestClient` — remove `@RegisterRestClient`, `@RegisterClientHeaders`
- `OpenHabAuthHeadersFactory` — **deleted**. `ClientHeadersFactory` is not a JAX-RS provider; replaced by `OpenHabAuthFilter implements ClientRequestFilter` (plain POJO, same auth logic)
- `HomeAssistantProvider` — create REST client programmatically in `@PostConstruct`; field changes from `@Inject @RestClient` to plain field set in `@PostConstruct`
- `OpenHabProvider` — create REST client programmatically in `@PostConstruct`
- `OpenHabSseClient` — create SSE REST client programmatically

---

## 1. Docker Compose + Deployment (#32)

### Scope

Production deployment artifact for the bridge agent. Not a dev-environment compose — the daily dev loop uses `MockDeviceProvider` + `mvn quarkus:dev`.

### Dockerfile

Location: `bridge/src/main/docker/Dockerfile.jvm`

- Base image: `eclipse-temurin:21-jre-alpine` (matches Claudony pattern)
- Quarkus fast-jar layout (`quarkus-app/` directory structure)
- Non-root user (UID 1001)
- Exposes port 8080 (Quarkus HTTP — health endpoint only; bridge is a WebSocket client)
- Image includes both HA and OpenHAB provider modules on the classpath — each activates independently via `@LookupIfProperty` (see Section 2)

### docker-compose.yml

Location: `bridge/docker-compose.yml`

Single-service compose for production deployment:

```yaml
services:
  bridge:
    image: ghcr.io/casehubio/iot-bridge:latest
    network_mode: host
    restart: unless-stopped
    env_file: .env
    volumes:
      - bridge-data:/app/data/bridge-events
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/q/health/ready"]
      interval: 30s
      timeout: 5s
      retries: 3

volumes:
  bridge-data:
```

**`network_mode: host` is required.** mDNS uses multicast UDP on port 5353; SSDP uses multicast UDP on port 1900. Neither works through Docker's bridge network. Home Assistant's own Docker deployment recommends `--net=host` for the same reason.

**Volume mount serves the existing `PersistentBridgeEventStore`** — the bridge agent already buffers events to `data/bridge-events/` as NDJSON when the cloud connection drops. `BridgeConnectionManager.replayBufferedEvents()` sends these as `ReplayedStateChange` messages on reconnect. The volume ensures buffered events survive container restarts.

### .env.example

```properties
# Tenancy — shared by bridge and all providers
CASEHUB_IOT_TENANCY_ID=default

# Cloud connection
CASEHUB_IOT_BRIDGE_CLOUD_ENDPOINT=wss://cloud.example.com/iot/bridge
CASEHUB_IOT_BRIDGE_TOKEN=changeme

# Home Assistant (providers default to disabled — enable explicitly)
CASEHUB_IOT_HOMEASSISTANT_ENABLED=true
CASEHUB_IOT_HOMEASSISTANT_TOKEN=your-ha-long-lived-access-token
# CASEHUB_IOT_HOMEASSISTANT_URL=http://192.168.1.100:8123  # optional — auto-discovered via mDNS if omitted

# OpenHAB
# CASEHUB_IOT_OPENHAB_ENABLED=true
# CASEHUB_IOT_OPENHAB_AUTH_BEARER_TOKEN=your-openhab-api-token
# CASEHUB_IOT_OPENHAB_URL=http://192.168.1.101:8080  # optional — auto-discovered via mDNS/SSDP if omitted
```

### Multi-Arch Build

ARM64 (Raspberry Pi) + x86_64 via `docker buildx` in GitHub Actions. Addition to existing `.github/workflows/publish.yml`:

- Build multi-arch image after `mvn deploy`
- Push to `ghcr.io/casehubio/iot-bridge`
- Tags: `latest` + version tag from POM

### Deployment Guide

Location: `bridge/DEPLOYMENT.md`

Covers: prerequisites, configuration reference, deploy/verify/update workflow, troubleshooting (connectivity, discovery, event buffering).

---

## 2. Provider Auto-Discovery (#33)

### Placement

Internal to each provider module. No new SPI. Discovery is provider-specific knowledge — HA knows its mDNS service type, OpenHAB knows its SSDP URN. The commonality ("resolve URL if not configured") is a one-liner pattern, not an interface.

### Provider Activation Mechanism

Both provider modules ship in the Docker image. Activation is controlled by `@LookupIfProperty`:

```java
@ApplicationScoped
@LookupIfProperty(name = "casehub.iot.homeassistant.enabled", stringValue = "true")
public class HomeAssistantProvider implements DeviceProvider { ... }
```

When `enabled` is absent or `false`, the provider bean is not discoverable via `Instance<DeviceProvider>`. `CdiDeviceRegistry` and `BridgeCommandDispatcher` (which iterate `@Any Instance<DeviceProvider>`) simply don't see it. `@PostConstruct` never runs. No zombie beans, no guard code — the provider doesn't exist when disabled.

This is the Quarkus-native mechanism for conditional bean activation. Verified: no code in the codebase injects a specific provider directly — all consumption is through `Instance<DeviceProvider>`.

### Activation Logic (both providers)

1. If `enabled` property is absent or `false` → bean not instantiated, not discovered
2. If `enabled=true` and `url` is explicitly configured → use it, create REST client programmatically, skip discovery
3. If `enabled=true` and `url` is not configured → attempt discovery with timeout, create REST client with discovered URL
4. If discovered → use discovered URL, log at INFO
5. If discovery fails → startup fails with clear error naming what was tried

### Home Assistant — mDNS

- **Dependency:** `org.jmdns:jmdns` in `homeassistant/pom.xml`
- **Service type:** `_home-assistant._tcp.local.`
- **Resolution:** JmDNS listener → `ServiceInfo` provides IP + port → construct `http://<ip>:<port>/api/`
- **Timeout:** `casehub.iot.homeassistant.discovery-timeout-seconds` (default 5)
- **Multiple instances:** use first resolved, log others at INFO
- **Implementation:** package-private `HomeAssistantDiscovery` class, called from provider startup

### OpenHAB — mDNS First, SSDP Fallback

- **Dependency:** same `org.jmdns:jmdns` in `openhab/pom.xml`
- **mDNS service types:** `_openhab-server._tcp.local.` (HTTP) and `_openhab-server-ssl._tcp.local.` (HTTPS). Both are advertised by OpenHAB. Prefer SSL variant when found.
- **SSDP fallback:** raw UDP multicast M-SEARCH on `239.255.255.250:1900` (~80 lines, no library). Filter responses for OpenHAB device description, extract REST URL. No jupnp dependency — full UPnP stack is unnecessary for service discovery alone.
- **Timeout:** `casehub.iot.openhab.discovery-timeout-seconds` (default 10 — longer because two protocols tried sequentially)
- **Implementation:** package-private `OpenHabDiscovery` class

### Provider Configuration Changes (Breaking)

Both HA and OpenHAB `@ConfigMapping` interfaces change:

**HomeAssistantConfig:**

| Property | Before | After |
|----------|--------|-------|
| `enabled` | (absent) | `@WithDefault("false") boolean enabled()` |
| `url` | `String url()` (required) | `Optional<String> url()` |
| `token` | `String token()` (required) | `Optional<String> token()` — validated programmatically when `enabled=true` |
| `tenancyId` | `String tenancyId()` (required) | Removed — consolidated to `casehub.iot.tenancy-id` (see Cross-Cutting) |
| `discoveryTimeoutSeconds` | (absent) | `@WithDefault("5") int discoveryTimeoutSeconds()` |

**OpenHabConfig:**

| Property | Before | After |
|----------|--------|-------|
| `enabled` | (absent) | `@WithDefault("false") boolean enabled()` |
| `url` | `String url()` (required) | `Optional<String> url()` |
| `auth` | `Auth auth()` (nested, bearer/basic already Optional) | Unchanged — already Optional internally |
| `tenancyId` | `String tenancyId()` (required) | Removed — consolidated to `casehub.iot.tenancy-id` (see Cross-Cutting) |
| `discoveryTimeoutSeconds` | (absent) | `@WithDefault("10") int discoveryTimeoutSeconds()` |

All previously-required properties (`url`, `token`, `tenancyId`) must become `Optional<String>` or be removed. SmallRye Config validates ALL `@ConfigMapping` properties at startup regardless of bean lifecycle — a disabled provider with missing required properties crashes the app before any `@PostConstruct` runs.

### Docker Interaction

`network_mode: host` in the compose file ensures the container can send/receive multicast traffic on the LAN. Without it, mDNS and SSDP discovery silently fail — no error, just no responses. The deployment guide documents this requirement.

---

## 3. Server-Side Audit Event Log (#34)

### Design Decision: CDI Events, Not SPI

The agreed approach (B) proposed an audit SPI in `api/bridge/`. Deeper analysis revealed this doesn't support the dual-trail audit pattern: CDI selects one SPI implementation (highest priority), but dual-trail requires BOTH operational logging AND compliance ledger active simultaneously. CDI events naturally support multiple independent observers. The audit contract is a CDI event type, not an interface.

### BridgeAuditEvent

Location: `api/src/main/java/io/casehub/iot/api/bridge/BridgeAuditEvent.java`

```java
public record BridgeAuditEvent(
    String tenancyId,
    Instant receivedAt,
    BridgeAuditEventType eventType,
    @Nullable String correlationId,
    @Nullable String deviceId,
    @Nullable BridgeMessage message
) {}
```

- `correlationId` — non-null for `COMMAND_SENT` and `COMMAND_RESPONSE` only
- `deviceId` — non-null for device-scoped events only
- `message` — the raw wire message for compliance hash verification. **Null for `AGENT_CONNECTED` and `AGENT_DISCONNECTED`** — these fire on `@OnOpen`/`@OnClose` where no `BridgeMessage` exists.

### BridgeAuditEventType

Location: `api/src/main/java/io/casehub/iot/api/bridge/BridgeAuditEventType.java`

```java
public enum BridgeAuditEventType {
    STATE_CHANGE,
    REPLAYED_STATE_CHANGE,
    STATE_SNAPSHOT,
    PROVIDER_STATUS_CHANGE,
    COMMAND_SENT,
    COMMAND_RESPONSE,
    AGENT_CONNECTED,
    AGENT_DISCONNECTED
}
```

No `HEARTBEAT` — heartbeats are protocol noise, not auditable events.

### Firing Points

Audit fires from **two locations** in bridge-server:

| Firing location | Audit types |
|-----------------|-------------|
| `@OnOpen` | `AGENT_CONNECTED` |
| `@OnClose` | `AGENT_DISCONNECTED` |
| `@OnTextMessage` | `STATE_CHANGE`, `REPLAYED_STATE_CHANGE`, `STATE_SNAPSHOT`, `PROVIDER_STATUS_CHANGE`, `COMMAND_RESPONSE` — 5 of 7 `BridgeMessage` variants (Heartbeat and anomalous inbound Command excluded) |
| `BridgeDeviceProvider.dispatch()` | `COMMAND_SENT` |

`COMMAND_SENT` fires from `BridgeDeviceProvider.dispatch()` because commands flow server→agent. The `@OnTextMessage` handler for an inbound `Command` is an anomaly path (logs a warning) — it is NOT the command-send path.

Audit fires BEFORE existing CDI event dispatch in `@OnTextMessage`. In `dispatch()`, audit fires when the outbound `BridgeMessage.Command` is constructed (before WebSocket send).

### Replayed Events: Audit Yes, Event Bus No

`ReplayedStateChange` fires `BridgeAuditEvent` (captured in the audit trail) but still does NOT fire `StateChangeEvent` on the CDI event bus. Replayed events are historical — firing them as live events would trigger automations based on stale data. This is unchanged from current behavior (`BridgeWebSocketEndpoint` line 112-114 logs at debug and drops).

### Operational Observer (Always On)

Location: `bridge-server/src/main/java/io/casehub/iot/bridge/server/audit/LoggingBridgeAuditObserver.java`

```java
@ApplicationScoped
public class LoggingBridgeAuditObserver {
    void onAudit(@ObservesAsync BridgeAuditEvent event) {
        // structured JSON log: tenancyId, eventType, deviceId, correlationId, timestamp
    }
}
```

Always active when bridge-server is on the classpath. Uses Quarkus structured logging — queryable via log aggregation. This is the operational trail.

### Compliance Observer (Future, Opt-In)

Not built in this iteration. Design accommodates it:

- A future `casehub-iot-bridge-ledger` module would contain an `@ObservesAsync BridgeAuditEvent` handler
- Writes to `BridgeLedgerEntry extends LedgerEntry` (JOINED inheritance, per `ledger-subclass-extension` protocol)
- Activated by classpath presence — add the module as a dependency, observer auto-discovers
- Multiple observers coexist: operational logging + compliance ledger fire independently

### Query/Retrieval

Deferred to #35. Operational queries go through log aggregation. Structured query via a `BridgeAuditStore` SPI follows the Store SPI pattern from `module-tier-structure` when a consuming app has a concrete need.

---

## Testing Strategy

### Discovery Tests

mDNS/SSDP requires multicast network access — won't work in CI containers. Strategy:

- **Unit tests:** mock JmDNS `ServiceInfo` responses, verify URL construction and timeout handling
- **Integration tests:** `@QuarkusTest` with discovery disabled (URL explicitly configured) — existing test pattern, no change
- **Manual verification:** documented in deployment guide — run against real HA/OpenHAB

### REST Client Tests

- **Unit tests:** verify `RestClientBuilder` creates clients with correct base URI and auth filter
- **Integration tests:** existing `@QuarkusTest` tests continue to work — they configure URLs explicitly, so REST clients are created programmatically with those URLs in `@PostConstruct`

### Audit Tests

- **Unit tests:** fire `BridgeAuditEvent`, verify `LoggingBridgeAuditObserver` produces structured log output
- **Integration tests:** send messages through `BridgeWebSocketEndpoint`, verify audit events fire for all message types including `ReplayedStateChange`. Verify `COMMAND_SENT` fires from `BridgeDeviceProvider.dispatch()`.
- **Regression:** verify `ReplayedStateChange` still does NOT fire `StateChangeEvent` (existing behavior preserved)

### Docker Tests

- **Build test:** `docker build` succeeds, image starts, health check passes
- **Compose test:** `docker compose up` with mock cloud endpoint, bridge connects and reports healthy
- **Multi-arch:** CI builds ARM64 and x86_64 images — platform-specific testing is manual (Pi)

### Provider Activation Tests

- **`@LookupIfProperty` test:** verify `Instance<DeviceProvider>` yields only enabled providers
- **Disabled provider test:** verify Quarkus starts successfully when a provider module is on the classpath but `enabled=false` — no config validation failure, no `@PostConstruct` execution, no REST client creation

---

## Platform Coherence Review

| Concern | Status |
|---------|--------|
| Module tier structure | API stays Tier 1 (BridgeAuditEvent is a record, no JPA). Bridge-server stays Tier 2 (CDI, no JPA). |
| Consumer SPI placement | No new SPI — CDI event type in `api/bridge/` is the contract. |
| CDI async event tenancy | `BridgeAuditEvent.tenancyId` is a required field — observers can perform tenant-scoped operations. |
| Dual-trail audit pattern | Operational trail via logging observer. Compliance trail opt-in via future classpath module. Both fire independently via CDI events. |
| Persistence backend CDI priority | Not applicable — no SPI, no persistence tier. Future Store SPI (#35) would follow the ladder. |
| PLATFORM.md | IoT deployment section needs updating to reflect Docker Compose availability and auto-discovery. |
| TenancyId consolidation | Three redundant tenancyId properties → one root `casehub.iot.tenancy-id`. Breaking change, correct by design. |
| REST client binding | Property expressions removed from application.properties. Clients created programmatically via `RestClientBuilder` — compatible with both Optional URLs and runtime discovery. |

---

## Out of Scope

- Native image Dockerfile (GraalVM reflection config for `DeviceTypeIdResolver` is non-trivial — separate issue)
- Dev-environment compose with HA/OpenHAB containers
- `BridgeAuditStore` SPI for structured query (#35 — deferred)
- Compliance ledger module (`casehub-iot-bridge-ledger` — designed for but not built)
- EndpointRegistry integration for discovered providers

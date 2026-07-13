# CaseHub IoT

## Project Type

type: java

## Repository Role

Foundation IoT device abstraction layer for the CaseHub ecosystem. Provides typed device class hierarchy, provider SPIs, and platform implementations for Home Assistant and OpenHAB. Consumed by application-tier repos (casehub-life, future property management, elder care) — never modified by them.

**Design spec:** `docs/superpowers/specs/2026-06-05-iot-foundation-design.md`  
**Research:** Available in `casehubio/parent` — `docs/superpowers/research/2026-06-05-home-automation-research.md`

## Build Commands

```bash
# Build all modules
mvn --batch-mode install

# Publish to GitHub Packages (CI only — requires GITHUB_TOKEN)
mvn --batch-mode deploy -DskipTests
```

## Module Structure

| Module | Artifact | Purpose |
|--------|----------|---------|
| `api` | `casehub-iot-api` | Core SPIs (reactive `Uni<>`), typed device class hierarchy, and `IoTCloudEventAdapter` (`StateChangeEvent → CloudEvent`). Depends on `casehub-platform-api`. **Public API, semver discipline** |
| `homeassistant` | `casehub-iot-homeassistant` | Home Assistant provider (REST + WebSocket) and HA supplement types |
| `openhab` | `casehub-iot-openhab` | OpenHAB provider (REST + SSE, semantic model) and OpenHAB supplement types |
| `testing` | `casehub-iot-testing` | MockDeviceProvider, fixture devices (Java `Fixtures` + YAML `DeviceFixtureLoader`), `DeviceTypeHandler` SPI, StateChangeEventPublisher — test scope only |
| `bridge-persistence-jpa` | `casehub-iot-bridge-persistence-jpa` | JPA-backed `BridgeAuditStore` — PostgreSQL with JSONB message storage, Flyway migrations, optional `@Scheduled` retention purge |
| `bridge-persistence-memory` | `casehub-iot-bridge-persistence-memory` | In-memory bounded ring buffer `BridgeAuditStore` — `@Alternative @Priority(100)`, for Pi and test isolation |
| `bridge` | `casehub-iot-bridge` | Local bridge agent (standalone Quarkus app) — event relay with CDI-discovered filter chain, WebSocket cloud client, command dispatch |
| `bridge-server` | `casehub-iot-bridge-server` | Cloud-side `BridgeDeviceProvider implements DeviceProvider` — remote devices look local. Library added as dependency by cloud consumers. |
| `webapp-api` | `casehub-iot-webapp-api` | Reusable IoT JavaSwitch ganglia, case descriptors, worker functions, ActionRiskClassifier, REST interfaces. Tier 1 — no JPA, no Quarkus runtime. |
| `webapp-drools` | `casehub-iot-webapp-drools` | DroolsCEP temporal pattern ganglia (`SustainedTemperatureRiseRule`, `MultiRoomMotionRule`). Activates by classpath presence. |
| `webapp` | `casehub-iot-webapp` | Standalone Quarkus app — operational console with RAS situational awareness, case orchestration, REST API, SSE, TypeScript pages via Quinoa. Three-datasource Flyway layout. |

## Key Rules

- `casehub-iot-api` is a **public API surface**. No breaking changes without a major version bump. Community automations in casehub-life and beyond depend on it.
- Vendor supplement types (HA, OpenHAB) extend common types only for fields that have no cross-vendor equivalent. Common interface first, supplement last resort.
- Device class vocabulary is aligned with the Matter Device Type Library.
- `iot-testing` is never a compile or runtime dependency for downstream consumers — test scope only. Provider modules (HA, OpenHAB) use `<optional>true</optional>` to compile against `DeviceTypeHandler` without propagating transitively.
- The bridge module has no domain logic — pure event forwarding and command relay.
- Provider activation uses `@LookupIfProperty(name = "casehub.iot.<provider>.enabled", stringValue = "true")` — disabled providers are invisible to `Instance<DeviceProvider>`. All provider config properties must be `Optional<String>` to prevent SmallRye startup validation failure.
- REST clients are created programmatically via `RestClientBuilder` (not `@RegisterRestClient`) — base URLs are resolved at runtime to support auto-discovery.
- Single tenancy property: `casehub.iot.tenancy-id` — never per-module `tenancyId()` in `@ConfigMapping`.
- Device metadata (deviceClass, roomType, eventTimestamp) flows into the case working layer via `IoTCaseInputContributor` — a CDI implementation of the `CaseInputContributor` SPI (`casehub-ras-api`). It resolves the device from `DeviceRegistry` using the CloudEvent correlationKey (`device/<deviceId>`). No CaseHub overrides needed.
- `DeviceEntity.location()` is nullable — populated by OpenHAB (from `thing.location()`), null for HA (area registry integration pending).
- Docker image: `ghcr.io/casehubio/iot-bridge` (JVM, multi-arch ARM64+x86_64). Deployment guide: `bridge/DEPLOYMENT.md`.

## Cross-Repo Conventions

Protocols shared across all modules live in the **casehub garden** (`../garden/docs/protocols/`). Do not write protocol files in this repo.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `docs/superpowers/specs/` |
| writing-plans (plans) | workspace `plans/` |
| handover | workspace `HANDOFF.md` |
| idea-log | workspace `IDEAS.md` |
| design-snapshot | workspace `snapshots/` |
| adr | `docs/adr/` |
| write-blog | workspace `blog/` |

## Routing

| Artifact   | Destination | Notes |
|------------|-------------|-------|
| adr        | project     | lands in `docs/adr/` |
| blog       | workspace   | staged here; published via publish-blog |
| design     | project     | |
| snapshots  | workspace   | |
| specs      | project     | lands in `docs/superpowers/specs/` |
| plans      | workspace   | |
| handover   | workspace   | |

## Work Tracking

**Issue tracking:** enabled  
**GitHub repo:** casehubio/iot

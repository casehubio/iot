# IoT Webapp — Operational Console with Situational Awareness

**Issue:** #TBD — to be created before implementation begins
**Date:** 2026-07-01
**Status:** Design

## Summary

A standalone Quarkus application (`casehub-iot-webapp`) that surfaces the full IoT platform as a usable operational console. Devices, providers, bridge connectivity, audit trail — all visible and controllable through composable TypeScript pages. Beyond operational views, the webapp wires RAS situational awareness and casehub-engine case orchestration to close the automation loop: device events trigger situations, situations create cases, cases dispatch device commands and human WorkItems.

Two modules: `webapp-api` (reusable ganglia, case descriptors, worker functions, page component functions) and `webapp` (standalone Quarkus app with full foundation wiring). Designed so casehub-life or any consumer can import `webapp-api` for the reusable pieces without pulling the full application.

## Module Structure

### `webapp-api` (`casehub-iot-webapp-api`)

Domain logic module. Depends on `casehub-ras-api` (Ganglion SPI, SituationDefinition), `casehub-engine-api` (CaseDefinition, WorkerFunction), `casehub-worker-api` (Worker, Capability). JAX-RS annotations and Mutiny types are `provided`-scope (inert without a runtime, per `module-tier-structure` protocol). DroolsCEP ganglia are in a separate `webapp-drools` module (see below):

- **JavaSwitch ganglion implementations** — threshold-based detection (no heavy runtime deps)
- **Default situation definitions** — JavaSwitch-only situations in classpath YAML (`META-INF/ras-iot-situations.yaml`). Drools-dependent situations ship in `webapp-drools` (see below).
- **Case descriptors** — `*CaseDescriptor` POJOs for each case type (worker lambdas, capability routing, SLA policies per `case-definition-layers` protocol)
- **YamlCaseHub subclasses** — one per case type, loading YAML definitions
- **Worker functions** — device-command-dispatch, household-notification, human-decision
- **IoT ActionRiskClassifier** — risk-gates device commands by type
- **REST resource interfaces** — JAX-RS annotations, request/response records
- **Page component functions** — reusable TypeScript functions (`deviceKpiRow`, `deviceTable`, etc.) live in `webapp/src/main/webapp/` but designed as extractable to a shared npm package if a second consumer needs them

### `webapp-drools` (`casehub-iot-webapp-drools`)

DroolsCEP temporal pattern ganglia. Separated from `webapp-api` because Drools is a heavy runtime dependency (`drools-engine`, `kie-api`). Consumers that only need JavaSwitch ganglia import `webapp-api` without pulling Drools:

- **DroolsCEP ganglia** — `SustainedTemperatureRiseRule`, `MultiRoomMotionRule`
- **Drools-dependent situation definitions** — `META-INF/ras-iot-drools-situations.yaml` (fire-risk, intrusion, hvac-failure)
- Activates by classpath presence (standard Quarkus pattern)

### `webapp` (`casehub-iot-webapp`)

Standalone Quarkus application:

- REST resource implementations wiring to IoT SPIs
- TypeScript pages via Quinoa (`src/main/webapp/`)
- Foundation layer wiring (all dependencies declared in pom.xml)
- `application.properties` — datasource, provider activation, Flyway locations
- `JpaRuntimeSituationDefinitionProvider` — database-backed, merges with classpath defaults
- YAML case definitions for the four starter case types
- SSE endpoints for live device state push

### Existing modules (unchanged)

`api`, `homeassistant`, `openhab`, `bridge`, `bridge-server`, `bridge-persistence-jpa`, `bridge-persistence-memory`, `testing` — the webapp consumes these, does not modify them.

## Foundation Layer Wiring

| Layer | Dependency | Purpose |
|-------|-----------|---------|
| IoT | `casehub-iot-api` | Device hierarchy, DeviceProvider SPI, BridgeAuditStore |
| IoT | `casehub-iot-homeassistant` | HA provider (runtime, activate via config) |
| IoT | `casehub-iot-openhab` | OpenHAB provider (runtime, activate via config) |
| IoT | `casehub-iot-bridge-server` | Remote devices via bridge agents |
| IoT | `casehub-iot-bridge-persistence-jpa` | Durable audit trail with retention |
| IoT | `casehub-iot-webapp-api` | Ganglia, case descriptors, worker functions |
| RAS | `casehub-ras` runtime | Situation detection from CloudEvents. Note: `SituationDefinitionProvider` SPI lives in `io.casehub.ras.runtime` (not the public API) — compile dependency on RAS runtime is required. Promotion to `casehub-ras-api` proposed (see casehub-ras#23). |
| RAS | `casehub-ras-drools` | CEP ganglia for temporal patterns |
| Connectors | `casehub-connectors-core` | Outbound household notifications (email, Slack, push) |
| RAS | `casehub-ras-persistence-jpa` | Durable situation context |
| Engine | `casehub-engine` + `casehub-engine-scheduler-quartz` | Case orchestration |
| Work | `casehub-work` | WorkItem inbox for human decisions |
| Ledger | `casehub-ledger` | Tamper-evident audit of cases and decisions |
| Platform | `casehub-platform` (runtime scope) | `MockCurrentPrincipal` `@DefaultBean` — provides `CurrentPrincipal` with configurable tenancy ID (default: `DEFAULT_TENANT_ID`). Required for Pi/standalone deployments without OIDC. |
| Platform | `casehub-platform-expression` | JQ evaluation for engine |

### Deferred (not in v1)

- `casehub-engine-ledger` — trust-weighted agent routing (no agent routing in v1)
- `casehub-openclaw` — agent provisioning (built-in workers only in v1)
- `casehub-engine-flow` — workflow-based workers (plain lambdas sufficient)
- `casehub-qhorus` — agent messaging (no agent-to-agent communication in v1)

### Datasource Layout

Three named datasources — required because upstream migration files have fixed version numbers. RAS (V1–V4), bridge (V1–V2), and work (V1–V39) all have overlapping versions that cannot be renumbered. No two of these can share a Flyway datasource.

**Default datasource** — IoT-owned migrations only:
- `db/iot-bridge/migration` — bridge audit tables (V1–V2, upstream-owned)
- `db/iot-webapp/migration` — runtime situation definitions, case command log, device state history (V500–V599)

**Named datasource `iot-work`** — work + ledger (proven coexistence, non-overlapping ranges):
- `db/work/migration` — WorkItem tables (V1–V39 core, upstream-owned)
- `db/ledger/migration` — ledger base tables (V1000–V1010, per protocol)

**Named datasource `iot-ras`** — RAS isolated (V1–V4 collides with both bridge and work):
- `db/ras/migration` — RAS situation context tables (V1–V4, upstream-owned)

All three datasources can point to the same physical database — separate Flyway tracking tables prevent version collisions. Or they can be separate databases for independent scaling.

## REST API

### Device Resources

- `GET /api/devices` — all devices across all providers. Filters: `deviceClass`, `providerId`, `tenancyId`, `available`. Returns typed device state.
- `GET /api/devices/{deviceId}` — single device with full state and capabilities.
- `POST /api/devices/{deviceId}/commands` — dispatch a command (`action` + `parameters`). Returns `CommandResult`.
- `GET /api/devices/{deviceId}/history` — state change events for a device. Filters: `from`, `to`, `limit`. Backed by a new `StateChangeHistoryObserver` (`@ObservesAsync StateChangeEvent`) that persists state transitions to `iot_device_state_history` table (see Data Model). This is independent of the bridge audit trail — it captures all state changes regardless of deployment topology (local, bridge, or hybrid).

### Provider Resources

- `GET /api/providers` — all registered providers with status (CONNECTED/CONNECTING/DISCONNECTED).
- `GET /api/providers/{providerId}` — single provider detail + device count.
- `POST /api/providers/refresh` — trigger device re-discovery across all providers. `DeviceRegistry.refresh()` rediscovers all providers (no per-provider refresh exists in the SPI). Per-provider refresh would require a new `refresh(String providerId)` method on `DeviceRegistry` — deferred unless a concrete need arises (iot#43).

### Bridge Resources

- `GET /api/bridge/connections` — connected tenancies from `BridgeConnectionRegistry`.
- `GET /api/bridge/audit` — audit trail. Filters: `tenancyId`, `eventType`, `deviceId`, `correlationId`, `from`, `to`, `offset`, `limit`.

### Situation Resources

- `GET /api/situations/definitions` — all situation definitions (classpath + runtime).
- `POST /api/situations/definitions` — create runtime situation definition.
- `PUT /api/situations/definitions/{situationId}` — update runtime definition.
- `DELETE /api/situations/definitions/{situationId}` — delete runtime definition. Classpath defaults cannot be deleted, only overridden.
- `GET /api/situations/active` — active situation contexts with detection history, confidence levels.

### Case Resources

- `GET /api/cases` — open cases triggered by situations. Filters: `status`, `situationId`, `from`, `to`.
- `GET /api/cases/{caseId}` — case detail with event log, worker results, planned actions.

### WorkItem Resources

- `GET /api/workitems` — pending human tasks. Filters: `status`, `caseId`.
- `POST /api/workitems/{workItemId}/claim` — claim a task.
- `POST /api/workitems/{workItemId}/complete` — complete with outcome (approve/reject/custom).

### Health Resource

- `GET /api/health/overview` — composite: provider statuses, bridge connections, active situation count, open case count, pending WorkItem count.

### Live Data (SSE)

- `GET /api/devices/stream` — SSE endpoint pushing device state changes in real-time. Uses the pages push protocol operations (`snapshot`, `append`, `replace`, `remove`). Note: SSE reconnect gets a full snapshot — the pages SSE source does not support seq-based incremental reconnect (that is WebSocket-only). Browser `EventSource` auto-reconnect triggers a full re-fetch, which is acceptable for IoT device state (the full device set is small and the snapshot is authoritative).

## Pages — TypeScript DSL via Quinoa

The webapp frontend lives in `webapp/src/main/webapp/`. Pages are built with `@casehubio/ui` and `@casehubio/data` — type-safe, composable, extractable as functions.

### Datasets

```typescript
dataset("devices", "/api/devices");
dataset("device-events", "sse:///api/devices/stream");
dataset("providers", "/api/providers");
dataset("situations-active", "/api/situations/active");
dataset("situation-defs", "/api/situations/definitions");
dataset("cases", "/api/cases");
dataset("workitems", "/api/workitems");
dataset("health", "/api/health/overview");
dataset("audit", "/api/bridge/audit");
```

### Reusable Component Functions

Defined in `webapp/src/main/webapp/src/components/` — designed as extractable to a shared npm package when a second consumer needs them:

```typescript
function deviceKpiRow(datasetId: string) {
  return columns([3, 3, 3, 3],
    [metric({ title: "Total", lookup: lookup(datasetId, groupBy(null, count("deviceId"))) })],
    [metric({ title: "Online", lookup: lookup(datasetId, filterBy("available", "EQUALS_TO", "true"), groupBy(null, count("deviceId"))) })],
    [metric({ title: "Providers", lookup: lookup(datasetId, groupBy(null, distinct("providerId"))) })],
    [metric({ title: "Alerts", lookup: lookup("situations-active", groupBy(null, count("situationId"))) })],
  );
}

function deviceTable(datasetId: string) {
  return withId("device-table", table({
    sortable: true, pageSize: 20, csvExport: true,
    filter: { enabled: true, listening: true },
    lookup: lookup(datasetId, sortBy("lastUpdated", "DESCENDING")),
  }));
}
```

### Application Shell

```typescript
export default page("IoT Console",
  sidebar(
    ["Health", healthPage()],
    ["Devices", devicesPage()],
    ["Situations", situationsPage()],
    ["Cases", casesPage()],
    ["Work Items", workItemsPage()],
    ["Audit", auditPage()],
    ["Providers", providersPage()],
  ),
  { settings: { mode: "dark" } },
);
```

### Page Descriptions

**Health** (landing page) — metric cards for provider count (green/red), bridge connections, active situations, open cases, pending WorkItems. Provider status table with colour-coded badges. 10s polling refresh on the health dataset.

**Devices** — KPI row (total, online, providers, alerts). Device table with cross-filtering by device class selector. Row click navigates to device detail sub-page. Device detail: metric cards for current state values, action buttons for commands (on/off, set temperature, lock/unlock, position, volume — conditional by `deviceClass`), timeline of state change history. Live SSE updates via `device-events` dataset.

**Situations** — tabs: Active | Definitions. Active tab: table of active situations with columns for situationId, correlationKey, confidence, signal count, first/last signal. Badge component for detection signal (NOISE/WEAK/DETECTED colour-coded). Definitions tab: table of all definitions with action buttons for create/edit/delete runtime definitions. Form for situation definition editing (event types, chain mode, trigger config). 15s refresh.

**Cases** — table of open cases with columns for caseId, situation source, status, created, pending actions count. Row click expands to case detail: event log timeline, worker results, planned actions with approve/reject action buttons. 30s refresh.

**Work Items** — table of pending human tasks with columns for description, case reference, priority, SLA deadline, status. Action buttons: claim, complete (approve/reject/custom outcome). Countdown component for SLA deadline. Filter by status (OPEN/CLAIMED/EXPIRED). 15s refresh.

**Audit** — full event history table with columns for timestamp, event type, device, correlation ID, message summary. Filters: date range picker, event type selector, device selector. CSV export enabled. Expandable rows for full message JSON.

**Providers** — provider status table with badge (CONNECTED green, DISCONNECTED red, CONNECTING amber). Per-provider: device count, refresh action button. Bridge connections table: tenancy ID, connected since. 30s refresh.

## RAS Wiring — Situation Detection

### Automation Loop

The loop is wired by dependency and CDI — no custom bridge code:

1. IoT provider detects state change → fires `StateChangeEvent` (CDI async)
2. `IoTCloudEventAdapter` (existing in `casehub-iot-api`) observes → produces `CloudEvent`
3. `RasEngine` (from `casehub-ras`) observes `CloudEvent` → routes to matching ganglia
4. Ganglia evaluate → accumulate `DetectionResult` on `SituationContext`
5. Threshold crossed → `CaseTrigger.fire()` → `CaseHub.startCase()` with situation context
6. Engine orchestrates case → workers dispatch device commands, create WorkItems
7. Device commands flow back through `DeviceProvider.dispatch()` → closing the loop

### Shipped Ganglion Implementations (in `webapp-api`)

**JavaSwitch ganglia** — simple threshold-based detection:

| Ganglion | Event Type | Detection Logic |
|----------|-----------|----------------|
| `TemperatureThresholdGanglion` | `io.casehub.iot.state_change.sensor`, `...thermostat` | Temperature exceeds/drops below configurable bounds. Handles unit conversion via `Temperature.toCelsius()`. |
| `MotionAtTimeGanglion` | `io.casehub.iot.state_change.presence_sensor` | Motion detected outside configured hours (e.g., 23:00–06:00). |
| `DeviceUnavailableGanglion` | all `io.casehub.iot.state_change.*` | Device goes `available=false`. |
| `LockStateGanglion` | `io.casehub.iot.state_change.lock` | Unexpected unlock (locked → unlocked transition). |
| `PowerAnomalyGanglion` | `io.casehub.iot.state_change.power_sensor` | Power spikes above configurable threshold. |

**DroolsCEP ganglia** — temporal pattern detection:

| Ganglion | Detection Logic |
|----------|----------------|
| `SustainedTemperatureRiseRule` | Temperature rising steadily across N readings over M minutes (sliding window). |
| `MultiRoomMotionRule` | Motion detected in 3+ rooms within a configurable time window. Correlates by tenancy. |

### Default Situation Definitions

Split across two YAML resources by ganglion dependency. Both are loaded by `SituationDefinitionProvider` at startup and merged with runtime definitions.

**`META-INF/ras-iot-situations.yaml`** in `webapp-api` — JavaSwitch-only ganglia:

| Situation ID | Event Types | Chain Mode | Trigger Mode | Event Buffer Delay | Case Trigger |
|-------------|------------|------------|-------------|-------------------|--------------|
| `unexpected-unlock` | `state_change.lock` | `Or(lock-state)` | `Repeating(PT30M)` | `null` (immediate) | `CaseTriggerConfig("io.casehub.iot", "security-alert", "1.0")` |
| `device-offline` | all `state_change.*` | `Or(device-unavailable)` | `FireOnce` | `PT5S` | `CaseTriggerConfig("io.casehub.iot", "generic-response", "1.0")` |

**`META-INF/ras-iot-drools-situations.yaml`** in `webapp-drools` — references DroolsCEP ganglia:

| Situation ID | Event Types | Chain Mode | Trigger Mode | Event Buffer Delay | Case Trigger |
|-------------|------------|------------|-------------|-------------------|--------------|
| `fire-risk` | `state_change.sensor`, `state_change.thermostat` | `And(temperature-threshold, sustained-rise)` | `Repeating(PT5M)` | `null` (immediate) | `CaseTriggerConfig("io.casehub.iot", "safety-alert", "1.0")` |
| `intrusion` | `state_change.presence_sensor` | `Threshold(motion-at-time, multi-room-motion, 0.7)` | `Repeating(PT10M)` | `PT2S` | `CaseTriggerConfig("io.casehub.iot", "security-alert", "1.0")` |
| `hvac-failure` | `state_change.thermostat` | `Count(sustained-rise, 3)` | `FireOnce` | `null` (immediate) | `CaseTriggerConfig("io.casehub.iot", "hvac-anomaly", "1.0")` |

Consumers importing `webapp-api` without `webapp-drools` get only the two JavaSwitch situations. Adding `webapp-drools` to the classpath activates the three Drools-dependent situations.

`TriggerMode.Repeating(cooldown)` prevents duplicate cases for the same ongoing situation — fire-risk with 5-minute cooldown won't create a new safety-alert case every time a temperature reading arrives during a sustained fire event. `EventBufferDelay` batches rapid-fire events (e.g., multiple presence sensors triggering within seconds) before evaluation. `null` = evaluate immediately.

### Runtime Situation Overrides

`JpaRuntimeSituationDefinitionProvider` implements `SituationDefinitionProvider`. At startup it loads definitions from the `iot_situation_definition` table scoped to `CurrentPrincipal.tenancyId()` and merges with classpath defaults. Database definitions with the same `situationId` for the caller's tenancy override classpath ones. Each tenant can independently override the same classpath situation definition. The REST API (`POST/PUT/DELETE /api/situations/definitions`) writes to this table with `tenancy_id` from `CurrentPrincipal`.

## Case Definitions

Four YAML case definitions in `webapp/src/main/resources/iot/`. Each YAML declares `metadata.namespace: io.casehub.iot` and `metadata.version: "1.0"` — these must match the `CaseTriggerConfig` triple in the situation definitions, because `DefaultCaseTrigger.findCaseHub()` matches by `(namespace, name, version)`.

### safety-alert (`io.casehub.iot / safety-alert / 1.0`)

Capabilities: `device-command-dispatch`, `household-notification`, `human-acknowledgement`.

Flow: immediately dispatch safety commands (kill HVAC, unlock doors) → notify household → create WorkItem for acknowledgement. No waiting for human approval before automated response — safety first.

Completion: human acknowledges the alert.

### security-alert

Capabilities: `device-command-dispatch`, `camera-activation`, `household-notification`, `human-decision`.

Flow: lock doors + activate cameras → notify household → create WorkItem for human decision (false alarm / escalate / call authorities).

Gate: automated lock is immediate, but escalation requires human WorkItem approval via `ActionRiskClassifier`.

Completion: human resolves.

### hvac-anomaly

Capabilities: `device-command-dispatch`, `household-notification`, `human-review`.

Flow: attempt setpoint correction → if command fails or temperature doesn't respond within correlation window → notify household → create WorkItem for manual triage.

Completion: temperature returns to range or human resolves.

### generic-response

Capabilities: `human-triage`.

Flow: create WorkItem with situation context (detections, confidence, device states). Human decides what to do. No automated device commands.

This is the target case for runtime-defined situations where the user hasn't specified automated responses.

## Case Definition Pattern (per `case-definition-layers` protocol)

Each case type follows the platform's three-layer architecture: YAML (structure) + `*CaseDescriptor` (business logic) + `YamlCaseHub` subclass (runtime entry point).

### YamlCaseHub Subclasses (in `webapp`)

Each subclass injects its companion `*CaseDescriptor` and overrides `augment()` to register programmatic workers. `YamlCaseHub.getDefinition()` is `final` — it loads YAML, calls `augment()` once inside a double-checked lock, then caches. Without `augment()`, the YAML loads structure but no executable worker logic.

```java
@ApplicationScoped
public class SafetyAlertCaseHub extends YamlCaseHub {
    @Inject SafetyAlertCaseDescriptor descriptor;

    public SafetyAlertCaseHub() { super("iot/safety-alert.yaml"); }

    @Override
    protected void augment(CaseDefinition definition) {
        descriptor.registerWorkers(definition);
    }
}

@ApplicationScoped
public class SecurityAlertCaseHub extends YamlCaseHub {
    @Inject SecurityAlertCaseDescriptor descriptor;

    public SecurityAlertCaseHub() { super("iot/security-alert.yaml"); }

    @Override
    protected void augment(CaseDefinition definition) {
        descriptor.registerWorkers(definition);
    }
}

@ApplicationScoped
public class HvacAnomalyCaseHub extends YamlCaseHub {
    @Inject HvacAnomalyCaseDescriptor descriptor;

    public HvacAnomalyCaseHub() { super("iot/hvac-anomaly.yaml"); }

    @Override
    protected void augment(CaseDefinition definition) {
        descriptor.registerWorkers(definition);
    }
}

@ApplicationScoped
public class GenericResponseCaseHub extends YamlCaseHub {
    @Inject GenericResponseCaseDescriptor descriptor;

    public GenericResponseCaseHub() { super("iot/generic-response.yaml"); }

    @Override
    protected void augment(CaseDefinition definition) {
        descriptor.registerWorkers(definition);
    }
}
```

### Case Descriptors (in `webapp-api`)

Each case type has a `*CaseDescriptor` POJO carrying worker lambdas, capability routing, and SLA policies:

| Descriptor | Worker Lambdas | Capabilities |
|-----------|---------------|-------------|
| `SafetyAlertCaseDescriptor` | device-command-dispatch, household-notification, human-acknowledgement | `DeviceCommandWorkerFunction`, `HouseholdNotificationWorkerFunction`, `HumanDecisionWorkerFunction` |
| `SecurityAlertCaseDescriptor` | device-command-dispatch, camera-activation, household-notification, human-decision | Same worker functions with different parameters |
| `HvacAnomalyCaseDescriptor` | device-command-dispatch, household-notification, human-review | Setpoint correction + escalation |
| `GenericResponseCaseDescriptor` | human-triage | `HumanDecisionWorkerFunction` only |

Workers are registered programmatically via `Worker.builder()` with lambdas in the descriptor's `registerWorkers(CaseDefinition)` method, called from the CaseHub's `augment()` override — not as standalone classes.

### Worker Functions (in `webapp-api`)

| Worker Function | Input (from case context) | Action | Output |
|--------|--------------------------|--------|--------|
| `DeviceCommandWorkerFunction` | `targetDeviceId`, `action`, `parameters` | Calls `DeviceProvider.dispatch()` | `CommandResult` as `WorkerOutcome` |
| `HouseholdNotificationWorkerFunction` | `tenancyId`, message template | Fires notification via `casehub-connectors` (`ConnectorService.send()`) | Delivery confirmation |
| `HumanDecisionWorkerFunction` | situation context, options | Creates WorkItem via casehub-work | Blocks until WorkItem completes with outcome |

## IoT ActionRiskClassifier (in `webapp-api`)

| Command Type | Risk Level | Gate |
|-------------|-----------|------|
| Safety commands (unlock during fire, kill HVAC during fire) | LOW | No gate — immediate execution |
| Lock commands | MEDIUM | WorkItem approval required |
| HVAC adjustments (normal operation) | LOW | No gate |
| Camera activation | LOW | No gate |
| Custom/unknown commands | HIGH | Always gated |

## Data Model — New Tables

### `iot_situation_definition`

Flyway: `db/iot-webapp/migration/V500__create_iot_situation_definition.sql`

| Column | Type | Constraint |
|--------|------|-----------|
| `id` | UUID | NOT NULL PRIMARY KEY |
| `situation_id` | VARCHAR(255) | NOT NULL |
| `tenancy_id` | VARCHAR(255) | NOT NULL |
| `definition` | JSONB | NOT NULL — full `SituationDefinition` serialized |
| `created_at` | TIMESTAMP WITH TIME ZONE | NOT NULL |
| `updated_at` | TIMESTAMP WITH TIME ZONE | NOT NULL |

Constraints:
- `UNIQUE(situation_id, tenancy_id)` — each tenant can independently override the same classpath situation definition. Single-tenant deployments (Pi/standalone with `DEFAULT_TENANT_ID`) are unaffected.

Index: `idx_iot_situation_def_tenancy` on `(tenancy_id)`.

### `iot_case_command_log`

Flyway: `db/iot-webapp/migration/V501__create_iot_case_command_log.sql`

| Column | Type | Constraint |
|--------|------|-----------|
| `id` | UUID | NOT NULL PRIMARY KEY |
| `case_id` | UUID | NOT NULL |
| `tenancy_id` | VARCHAR(255) | NOT NULL |
| `device_id` | VARCHAR(255) | NOT NULL |
| `action` | VARCHAR(50) | NOT NULL |
| `result` | VARCHAR(20) | NOT NULL — SENT / FAILED / TIMEOUT |
| `dispatched_at` | TIMESTAMP WITH TIME ZONE | NOT NULL |
| `correlation_id` | VARCHAR(255) | nullable |

Indexes:
- `idx_iot_case_cmd_case` on `(case_id)`
- `idx_iot_case_cmd_device` on `(device_id)`
- `idx_iot_case_cmd_tenancy_time` on `(tenancy_id, dispatched_at DESC)`

Distinct from the bridge audit trail — this records commands dispatched by case workers specifically, with case context. The bridge audit captures all traffic regardless of origin. The REST API queries `iot_case_command_log` for case-scoped command history and `BridgeAuditStore` for raw traffic. No merge or dedup — they are complementary views (case-context vs transport-level) with different consumers.

### `iot_device_state_history`

Flyway: `db/iot-webapp/migration/V502__create_iot_device_state_history.sql`

| Column | Type | Constraint |
|--------|------|-----------|
| `id` | UUID | NOT NULL PRIMARY KEY |
| `tenancy_id` | VARCHAR(255) | NOT NULL |
| `device_id` | VARCHAR(255) | NOT NULL |
| `provider_id` | VARCHAR(255) | NOT NULL |
| `device_class` | VARCHAR(50) | NOT NULL |
| `state_snapshot` | JSONB | NOT NULL — serialized DeviceEntity after state |
| `changed_capabilities` | TEXT[] | NOT NULL — capabilities that changed |
| `occurred_at` | TIMESTAMP WITH TIME ZONE | NOT NULL |

Indexes:
- `idx_iot_state_hist_device_time` on `(device_id, occurred_at DESC)`
- `idx_iot_state_hist_tenancy_time` on `(tenancy_id, occurred_at DESC)`

Populated by `StateChangeHistoryObserver` (`@ObservesAsync StateChangeEvent`) — captures every state transition with full device snapshot for the `/api/devices/{deviceId}/history` endpoint.

**Retention policy:** Configurable via `casehub.iot.webapp.state-history.retention-days` (default: 30). A `@Scheduled` purge job runs at `casehub.iot.webapp.state-history.purge-interval` (default: `PT1H`) and executes bulk `DELETE FROM iot_device_state_history WHERE occurred_at < :cutoff`. Same pattern as bridge audit retention (`casehub.iot.bridge.audit-store.jpa.retention-days` / `purge-interval`).

### Flyway Version Coordination

Three-datasource layout eliminates all cross-module version collisions. Upstream modules ship fixed version numbers (RAS V1–V4, bridge V1–V2, work V1–V39) that cannot be renumbered by consumers. RAS, bridge, and work all overlap at V1–V4, so no two of these can share a Flyway datasource.

| Datasource | Locations | Versions | Collision risk |
|-----------|----------|---------|---------------|
| Default | bridge (V1–V2), webapp (V500–V599) | No overlap | None |
| `iot-work` | work (V1–V39), ledger (V1000–V1010) | No overlap | None (proven in casehub-life) |
| `iot-ras` | RAS (V1–V4) | Isolated | None |

## Authentication and Authorization

`@RolesAllowed` annotations on all REST resources, following the casehub-life pattern (life#40). Auth is activated by adding `casehub-platform-oidc` as a compile dependency — annotations are inert without it (per `auth-retrofit-readiness` protocol).

| Endpoint | Role | Rationale |
|----------|------|-----------|
| `GET /api/devices`, `GET /api/providers`, `GET /api/bridge/*`, `GET /api/health/*` | `iot-viewer` | Read-only operational views |
| `POST /api/devices/{deviceId}/commands` | `iot-operator` | Device command dispatch |
| `POST /api/providers/refresh` | `iot-operator` | Provider re-discovery |
| `GET /api/situations/definitions` | `iot-viewer` | Read situation definitions (needed to understand active situations) |
| `POST/PUT/DELETE /api/situations/definitions` | `iot-admin` | Situation definition management |
| `GET /api/cases`, `GET /api/workitems` | `iot-viewer` | Case and work views |
| `POST /api/workitems/{workItemId}/claim`, `POST .../complete` | `iot-operator` | WorkItem lifecycle |

For single-machine/Pi deployments without an OIDC provider, all endpoints are accessible without authentication (annotations inert). Production multi-tenant deployments wire `casehub-platform-oidc`.

## Tenant Isolation

All REST endpoints that return tenant-scoped data filter by `CurrentPrincipal.tenancyId()`. The `CurrentPrincipal` implementation is resolved by CDI `@DefaultBean` priority:

1. **`OidcCurrentPrincipal`** — when `casehub-platform-oidc` is on the classpath. Tenancy from JWT claim. Production multi-tenant deployments.
2. **`QhorusInboundCurrentPrincipal`** — when `casehub-qhorus` is on the classpath (not in v1). Tenancy from `X-Tenancy-ID` header.
3. **`MockCurrentPrincipal`** — default fallback from `casehub-platform` (runtime scope). Returns configurable `casehub.tenancy.default-id` (defaults to `DEFAULT_TENANT_ID`). Pi/standalone deployments.

Specifically:
- `GET /api/devices` filters by `tenancyId` on `DeviceEntity`
- `GET /api/situations/definitions` returns definitions for the caller's tenancy
- `POST /api/situations/definitions` sets `tenancy_id` from `CurrentPrincipal.tenancyId()`
- All case, WorkItem, and audit queries include `tenancy_id` filter

## Failure Modes

### Automation Loop Failures

| Step | Failure | Behaviour |
|------|---------|-----------|
| 3. RasEngine routes to ganglion | Ganglion throws exception | RasEngine catches per-ganglion, logs at ERROR, skips that ganglion's result. Other ganglia in the chain continue. Situation evaluation completes with available detections. |
| 5. CaseTrigger.fire() | `CaseHub.startCase()` fails (DB down, duplicate case) | Logged at ERROR. `SituationEvaluator.executeDecision()` calls `store.resetTriggerClaim()` and returns `false` (not terminated) — the situation stays alive regardless of `TriggerMode`. For `FireOnce`: next qualifying event re-evaluates, `DefaultRasTriggerPolicy` returns `CREATE_CASE` again, claim is reattempted. For `Repeating`: same retry after cooldown elapses. No situation gets permanently stuck on `fire()` failure. |
| 6. Worker dispatch | `DeviceProvider.dispatch()` returns FAILED | `DeviceCommandWorkerFunction` returns `WorkerOutcome.FAILED`. Engine records failure in case event log. For safety-alert: case creates escalation WorkItem for human review. For hvac-anomaly: transitions to notification + human-review path. |
| 6. Worker dispatch | Provider DISCONNECTED | `dispatch()` returns `CommandResult.FAILED` immediately (existing provider behaviour). Same escalation path as dispatch failure. Safety-alert case creates WorkItem: "Device command failed — provider disconnected, manual intervention required." |
| 1–4. Situation storm | Multiple devices trigger same situation simultaneously | `TriggerMode` controls this: `FireOnce` creates one case then suppresses further triggers. `Repeating(cooldown)` creates one case then suppresses for the cooldown duration. Multiple simultaneous events within the `eventBufferDelay` window are batched before evaluation. |
| 6. Notification | `ConnectorService.send()` fails | `HouseholdNotificationWorkerFunction` returns `WorkerOutcome.FAILED`. Non-blocking — device commands execute independently of notification success. Case event log records the failure. |

## Updated Module Table

| Module | Artifact | Purpose |
|--------|----------|---------|
| `webapp-api` | `casehub-iot-webapp-api` | Reusable IoT JavaSwitch ganglia, case descriptors, worker functions, page component functions, REST interfaces, ActionRiskClassifier |
| `webapp-drools` | `casehub-iot-webapp-drools` | DroolsCEP temporal pattern ganglia (`SustainedTemperatureRiseRule`, `MultiRoomMotionRule`). Activates by classpath presence. |
| `webapp` | `casehub-iot-webapp` | Standalone Quarkus app — full foundation wiring, REST endpoints, TypeScript pages via Quinoa, runtime situation definitions, case definitions, YamlCaseHub subclasses |

## Design Decisions

**Three datasources** — not for scaling (the IoT webapp has lightweight persistence), but for Flyway version isolation. Upstream modules (RAS V1–V4, bridge V1–V2, work V1–V39) all have overlapping version numbers that cannot be renumbered. RAS collides with both bridge and work at V1–V4, so no two of these three can share a Flyway datasource. Default holds IoT-owned tables (bridge + webapp), `iot-work` holds work + ledger (proven coexistence from casehub-life), `iot-ras` isolates RAS. All three can point to the same physical database for simple deployments.

**No qhorus dependency in v1.** Agent-to-agent messaging is not needed when the webapp runs built-in worker functions directly. When OpenClaw integration is added later, qhorus comes in as part of that.

**TypeScript DSL for pages, not YAML.** Full type safety, IDE autocompletion, composable as functions. YAML is only used when pages are defined at runtime (the situation definition editor). The pages guide is explicit: always prefer the TypeScript DSL.

**Classpath + database merge for situation definitions.** Shipped defaults give the webapp value out of the box. Runtime overrides via the REST API and pages UI let users define custom situations without redeployment. Database definitions with matching `situationId` win — users can override but not delete shipped defaults (delete removes the override, restoring the classpath default).

**Safety-first risk classification.** During a fire, the system unlocks doors and kills HVAC without waiting for human approval. Lock commands in non-emergency contexts require human approval. This is a deliberate asymmetry — false negatives on safety are worse than false positives.

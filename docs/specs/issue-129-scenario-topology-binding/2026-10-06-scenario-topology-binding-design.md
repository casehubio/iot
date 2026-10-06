# Scenario-Topology Binding Design

**Issue:** casehubio/iot#129  
**Branch:** issue-129-scenario-topology-binding  
**Status:** Design  

## Problem

The topology dual-view (#119) renders device hierarchy as a spatial tree and operational graph. The scenario engine (#117, #126) orchestrates device commands and desired-state convergence. These two systems operate independently — when a scenario step executes, there is no visual indication of which devices or zones are being affected.

Users running a multi-step scenario (e.g., "apply nightmode preset") cannot see which devices are converging, which have completed, or which have failed without switching to a different view.

## Design

### Architecture Overview

```
┌──────────────────────────┐     CDI events      ┌──────────────────────────┐
│  DesiredStateDelivery-   │ ──────────────────►  │  ScenarioTopology-       │
│  Handler                 │  ScenarioBinding-    │  Binder                  │
│  (scenario module)       │  Event               │  (webapp module)         │
└──────────────────────────┘                      │                          │
                                                  │  Normalises events →     │
                                                  │  writes to Broadcast-    │
                                                  │  Processor               │
                                                  └───────────┬──────────────┘
                                                              │
                                                    SSE stream │
                                                  ┌───────────▼──────────────┐
                                                  │  /api/topology/stream    │
                                                  │  TopologyStreamEvent     │
                                                  │  (device-state +         │
                                                  │   binding operations)    │
                                                  └───────────┬──────────────┘
                                                              │
                                               ┌──────────────┴──────────────┐
                                               │                             │
                                    ┌──────────▼──────┐         ┌────────────▼────┐
                                    │ iot-topology-    │         │ iot-topology-   │
                                    │ tree             │         │ graph           │
                                    │ (highlightMap)   │         │ (highlightMap)  │
                                    └─────────────────┘         └─────────────────┘
```

The design follows the existing CaseHub pattern: domain code fires CDI events, an observer bridges to the topology SSE stream, and frontend components render from SSE-derived state.

**Command binding (IoTCommandPlugin path) is deferred** — the plugin SPI does not thread execution context, so command events cannot be correlated with scenario executions. See casehubio/iot#131 for the follow-up.

### Layer 1: CDI Events (casehub-iot-api + casehub-iot-scenario)

#### ScenarioBindingEvent

A new CDI event type in `casehub-iot-api`, modelled as a sealed interface with typed variants:

```java
public sealed interface ScenarioBindingEvent {
    String executionId();
    String tenancyId();

    record StepStart(
        String executionId, String tenancyId,
        String stepName, Set<String> deviceIds
    ) implements ScenarioBindingEvent {}

    record DeviceProvisioned(
        String executionId, String tenancyId,
        String stepName, String deviceId
    ) implements ScenarioBindingEvent {}

    record DeviceFailed(
        String executionId, String tenancyId,
        String stepName, String deviceId, String reason
    ) implements ScenarioBindingEvent {}

    record StepComplete(
        String executionId, String tenancyId,
        String stepName, int provisioned, int failed
    ) implements ScenarioBindingEvent {}

    record StepFailed(
        String executionId, String tenancyId,
        String stepName, int provisioned, int failed,
        List<String> failedDetails
    ) implements ScenarioBindingEvent {}

    record Clear(
        String executionId, String tenancyId
    ) implements ScenarioBindingEvent {}
}
```

The `executionId` is a handler-generated UUID, scoped to a single step execution. Each invocation of `execute()` generates a fresh UUID. This provides natural isolation — no cross-step or cross-scenario corruption.

The `tenancyId` is included on every event for future per-tenant SSE filtering (see §Multi-tenancy note below).

CDI observers can pattern-match on specific variants (`@Observes ScenarioBindingEvent.StepStart`) or observe all variants (`@Observes ScenarioBindingEvent`).

#### Emission from DesiredStateDeliveryHandler

The delivery handler fires synchronous CDI events (`Event.fire()`) at three points:

1. **After plan computation, before provisioning** — `StepStart` with all device IDs that will be provisioned (derived from the plan, not from raw goals)
2. **After each device provision** — `DeviceProvisioned` or `DeviceFailed` with the individual device ID
3. **After provisioning completes** — `StepComplete` or `StepFailed` with counts and summary

Synchronous firing guarantees ordering: start → per-device updates → complete.

**Why synchronous (`Event.fire()`) instead of async (`Event.fireAsync()`)?** The existing `StateChangeEvent` uses `fireAsync()` + `@ObservesAsync` because state change notifications are fire-and-forget — ordering between independent device state changes doesn't matter. Binding events have strict lifecycle semantics: `StepStart` must arrive before any `DeviceProvisioned` events, and `StepComplete` must arrive after all per-device updates. Async firing through CDI's async delivery pool would lose this ordering guarantee. The trade-off is that synchronous events add serialization latency to each provision iteration — acceptable because per-device provision is already I/O-bound (network call to device provider).

```java
// In DesiredStateDeliveryHandler.execute():
String executionId = UUID.randomUUID().toString();
IoTGoals goals;
try {
    goals = resolveGoals(data);
} catch (Exception e) {
    return StepOutcome.fail(stepName, e.getMessage());
}

try {
    return reconcile(stepName, goals, executionId);
} catch (Exception e) {
    return StepOutcome.fail(stepName, "Reconciliation failed: " + e.getMessage());
}
```

```java
// In reconcile():
var compilationResult = compiler.compile(goals, graphFactory);
if (!(compilationResult instanceof CompilationResult.SingleGraph sg)) {
    return StepOutcome.fail(stepName,
            "Unexpected compilation result: "
                    + compilationResult.getClass().getSimpleName());
}

DesiredStateGraph graph = sg.graph();
var actual = actualStateAdapter.readActual(graph, tenancyId);
var plan = planner.plan(graph, actual);

// Derive device IDs from the plan — only devices that will actually be provisioned
Set<String> planDeviceIds = plan.flatAdditions().stream()
    .filter(s -> s.action() == StepAction.PROVISION)
    .map(s -> extractDeviceId(s.node()))
    .collect(toSet());

// Device-level deduplication: physical devices produce two graph nodes
// (physical + config). Track outcomes by device ID, not node ID.
boolean stepStartFired = false;
Map<String, String> deviceOutcomes = new LinkedHashMap<>();
List<String> failedDetails = new ArrayList<>();

try {
    if (!planDeviceIds.isEmpty()) {
        bindingEvent.fire(new ScenarioBindingEvent.StepStart(
            executionId, tenancyId, stepName, planDeviceIds));
        stepStartFired = true;
    }

    for (OrderedStep step : plan.flatAdditions()) {
        if (step.action() == StepAction.PROVISION) {
            var result = provisioner.provision(
                    step.node(), new ProvisionContext(tenancyId, graph));
            String deviceId = extractDeviceId(step.node());
            if (result instanceof ProvisionResult.Success
                    || result instanceof ProvisionResult.AlreadyConverged) {
                if (!deviceOutcomes.containsKey(deviceId)) {
                    deviceOutcomes.put(deviceId, "provisioned");
                    bindingEvent.fire(new ScenarioBindingEvent.DeviceProvisioned(
                        executionId, tenancyId, stepName, deviceId));
                }
            } else if (result instanceof ProvisionResult.Failed f) {
                deviceOutcomes.put(deviceId, "failed");
                failedDetails.add(deviceId + ": " + f.reason());
                bindingEvent.fire(new ScenarioBindingEvent.DeviceFailed(
                    executionId, tenancyId, stepName, deviceId, f.reason()));
            } else {
                deviceOutcomes.put(deviceId, "failed");
                failedDetails.add(deviceId + ": " + result.getClass().getSimpleName());
                bindingEvent.fire(new ScenarioBindingEvent.DeviceFailed(
                    executionId, tenancyId, stepName, deviceId,
                    result.getClass().getSimpleName()));
            }
        }
    }

    int provisioned = (int) deviceOutcomes.values().stream()
        .filter("provisioned"::equals).count();
    int failed = (int) deviceOutcomes.values().stream()
        .filter("failed"::equals).count();

    if (stepStartFired) {
        if (failed > 0) {
            bindingEvent.fire(new ScenarioBindingEvent.StepFailed(
                executionId, tenancyId, stepName, provisioned, failed, failedDetails));
        } else {
            bindingEvent.fire(new ScenarioBindingEvent.StepComplete(
                executionId, tenancyId, stepName, provisioned, failed));
        }
    }
} catch (Exception e) {
    if (stepStartFired) {
        bindingEvent.fire(new ScenarioBindingEvent.Clear(executionId, tenancyId));
    }
    throw e;
}

// Return StepOutcome as before
```

The `extractDeviceId()` helper maps graph node IDs back to device IDs by stripping the `-config` suffix. The `IoTGoalCompiler` creates two nodes per physical device: `NodeId.of(goal.deviceId())` (physical) and `NodeId.of(goal.deviceId() + "-config")` (config), with a dependency edge from config to physical. Both can appear in `plan.flatAdditions()` with `StepAction.PROVISION`. The `deviceOutcomes` map deduplicates at the device level: `DeviceProvisioned` fires only for the first successful node per device (subsequent nodes for the same device are skipped), while `DeviceFailed` always fires and overrides any prior success — if the physical node succeeds but the config node fails, the device transitions from `provisioned` to `failed` on the frontend. Completion counts (`provisioned`, `failed`) are computed from `deviceOutcomes` and reflect device counts, not node counts.

#### DeviceCommandEvent (retained as audit event — not used for binding)

The `DeviceCommandEvent` record is retained as a general-purpose command audit event in `casehub-iot-api`. However, the `ScenarioTopologyBinder` does **not** observe it for binding purposes. The `IoTCommandPlugin` is a `@Plugin` record without CDI access, and neither the plugin SPI nor `DeliveryContext` threads execution context. Without an `executionId`, command events cannot be correlated with scenario step executions.

Command binding is tracked in casehubio/iot#131. It requires changes to the plugin execution SPI to thread execution context, which is outside the scope of this issue.

### Layer 2: ScenarioTopologyBinder (casehub-iot-webapp)

A dedicated `@ApplicationScoped` CDI observer bean. Responsibilities:

1. **Observe `ScenarioBindingEvent`** — from the delivery handler. Translate each variant into binding-typed `TopologyStreamEvent` operations and push to the `BroadcastProcessor`.

The binder needs access to the topology `BroadcastProcessor`. `TopologyStreamEvent` must first be extracted from `DefaultIoTTopologyApi` into a standalone record (see Layer 3). The binder then receives a shared `BroadcastProcessor<TopologyStreamEvent>` injectable — either extracted into a `TopologyStreamBroadcaster` bean, or via a `broadcast(TopologyStreamEvent)` method exposed on `DefaultIoTTopologyApi`.

```java
@ApplicationScoped
public class ScenarioTopologyBinder {

    @Inject BroadcastProcessor<TopologyStreamEvent> broadcaster;

    void onBindingEvent(@Observes ScenarioBindingEvent event) {
        var streamEvent = switch (event) {
            case ScenarioBindingEvent.StepStart ss ->
                TopologyStreamEvent.binding("binding-start", Map.of(
                    "executionId", ss.executionId(),
                    "stepName", ss.stepName(),
                    "deviceIds", ss.deviceIds()));
            case ScenarioBindingEvent.DeviceProvisioned dp ->
                TopologyStreamEvent.binding("binding-update", Map.of(
                    "executionId", dp.executionId(),
                    "deviceId", dp.deviceId(),
                    "status", "PROVISIONED"));
            case ScenarioBindingEvent.DeviceFailed df ->
                TopologyStreamEvent.binding("binding-update", Map.of(
                    "executionId", df.executionId(),
                    "deviceId", df.deviceId(),
                    "status", "FAILED"));
            case ScenarioBindingEvent.StepComplete sc ->
                TopologyStreamEvent.binding("binding-complete", Map.of(
                    "executionId", sc.executionId(),
                    "stepName", sc.stepName(),
                    "outcome", "OK",
                    "provisioned", sc.provisioned(),
                    "failed", sc.failed()));
            case ScenarioBindingEvent.StepFailed sf ->
                TopologyStreamEvent.binding("binding-complete", Map.of(
                    "executionId", sf.executionId(),
                    "stepName", sf.stepName(),
                    "outcome", "FAILED",
                    "provisioned", sf.provisioned(),
                    "failed", sf.failed()));
            case ScenarioBindingEvent.Clear c ->
                TopologyStreamEvent.binding("binding-clear", Map.of(
                    "executionId", c.executionId()));
        };
        broadcaster.onNext(streamEvent);
    }
}
```

### Layer 3: SSE Stream Extension (casehub-iot-webapp)

The existing `/api/topology/stream` SSE endpoint broadcasts `TopologyStreamEvent(operation, nodes)`. This record is currently defined as a nested record inside `DefaultIoTTopologyApi` (line 64). **It must be extracted into a standalone record** in the `webapp` module's service package — the binder (a separate bean) needs to create `TopologyStreamEvent` instances, which requires the type to be accessible outside `DefaultIoTTopologyApi`.

The extracted record gains a `binding` field for binding operations:

```java
public record TopologyStreamEvent(
    String operation,
    List<TopologyNode> nodes,
    Map<String, Object> binding
) {
    public TopologyStreamEvent(String operation, List<TopologyNode> nodes) {
        this(operation, nodes, null);
    }

    public static TopologyStreamEvent binding(String operation, Map<String, Object> data) {
        return new TopologyStreamEvent(operation, List.of(), data);
    }
}
```

New operation types:

| Operation | Payload | Description |
|-----------|---------|-------------|
| `binding-start` | `{executionId, stepName, deviceIds[]}` | Step is beginning, highlight these devices |
| `binding-update` | `{executionId, deviceId, status}` | Individual device provisioned/failed |
| `binding-complete` | `{executionId, stepName, outcome, summary}` | Step finished, clear highlights |
| `binding-clear` | `{executionId}` | Abnormal termination — clear step highlights |

Existing operations (`snapshot`, `update`) continue unchanged.

**D2 event schema mapping:** D2's `device-update (status: PROVISIONING)` is not a separate SSE event. The `binding-start` event implicitly puts all announced devices into the "provisioning" state on the frontend. D2's `TIMEOUT` status is not relevant to the desired-state provision path — `ProvisionResult` has no Timeout variant; timeouts manifest as `Failed(reason)`. Command-path timeout handling is deferred to casehubio/iot#131.

### Layer 4: Frontend Components

#### Data Flow — ConfigurablePanel SSE Subscription

The IoT topology components (`iot-topology-tree`, `iot-topology-graph`) are hosted via the `hostPanel()` DSL function in `topology.ts`. The page definition passes `endpoint` and `sseEndpoint` as `panelProps`:

```typescript
hostPanel("iot-topology-tree", {
  endpoint: "/api/topology",
  sseEndpoint: "/api/topology/stream",
  selectionTopic: "topology-device",
})
```

The components implement the `ConfigurablePanel` interface. The `configure()` method receives the panel props, sets up REST data fetching from `endpoint`, and subscribes to `sseEndpoint` for live updates. The SSE event handler splits `TopologyStreamEvent` by operation:

- Node operations (`snapshot`, `update`) → update internal `nodes` state (existing behaviour, currently missing — this also enables live device state updates)
- Binding operations (`binding-*`) → update internal `highlightMap` state (new)

This follows the same self-contained data pattern used by `blocks-topology-viewer` and `reconciliation-status`, both of which manage their own SSE subscriptions via `SSEManager`.

**Note:** `blocks-topology-viewer` is a **platform-level service topology viewer** dealing with `TopologySnapshot` containing services with statuses like `RUNNING`, `DEGRADED`, etc. It is unrelated to the IoT device topology and is NOT part of this design's rendering chain.

#### SSE Subscription Lifecycle

The IoT topology components follow the established platform pattern for SSE lifecycle management (as used by `blocks-topology-viewer` and `reconciliation-status`):

**Cleanup on disconnect:** `disconnectedCallback()` must unsubscribe from the SSE stream via `_sseManager.unsubscribe(sseEndpoint, handler)`. Without this, navigating away from the topology page leaves a dangling `EventSource` connection.

**Re-configuration:** `willUpdate(changed)` checks `changed.has('sseEndpoint')`. If the SSE endpoint changes, the old subscription is removed before creating the new one:

```typescript
private _sseManager = new SSEManager();
private _sseHandler = (event: SSEEvent) => { /* handle TopologyStreamEvent */ };

override disconnectedCallback(): void {
  if (this.sseEndpoint) {
    this._sseManager.unsubscribe(this.sseEndpoint, this._sseHandler);
  }
  super.disconnectedCallback();
}

override willUpdate(changed: PropertyValues): void {
  if (changed.has('sseEndpoint')) {
    const old = changed.get('sseEndpoint') as string | undefined;
    if (old) this._sseManager.unsubscribe(old, this._sseHandler);
    if (this.sseEndpoint) this._sseManager.subscribe(this.sseEndpoint, this._sseHandler);
  }
}
```

**SSE reconnection and stale highlights:** `SSEManager` handles connection failures with exponential backoff (1s base, 30s max). On reconnect, the SSE endpoint delivers a fresh topology snapshot, resynchronizing node state. However, binding events are transient — a `binding-complete` lost during disconnection won't be re-sent, leaving stale highlights. The server-side safety net (the `Clear` event fired on exceptions — see §Layer 1) ensures `StepStart` is always paired with a terminating event. For the narrow window where a terminating event is lost in transit, the binding lifecycle is typically seconds, so `SSEManager`'s reconnect will complete within the same execution. No client-side staleness timeout is specified — provisioning duration is variable (depends on device provider network calls), making a fixed timeout unreliable.

#### HighlightState Model

```typescript
interface HighlightState {
  status: 'active' | 'provisioned' | 'failed';
  executionId: string;
  stepName: string;
}

// highlightMap: Record<string, HighlightState>
// keyed by deviceId
```

**Status mapping from D2 event schema:**
- D2's `PROVISIONING` → `active` (set by `binding-start`)
- D2's `PROVISIONED` → `provisioned` (set by `binding-update` with status `PROVISIONED`)
- D2's `FAILED` → `failed` (set by `binding-update` with status `FAILED`)

#### SSE Event Handling

State machine for the highlight map:
- `binding-start` → set all `deviceIds` to `{status: 'active', executionId, stepName}`
- `binding-update` with status `PROVISIONED` → update device to `{status: 'provisioned', ...}`
- `binding-update` with status `FAILED` → update device to `{status: 'failed', ...}`
- `binding-complete` → remove all entries with matching `executionId` (after a brief 1.5s "done" flash)
- `binding-clear` → remove all entries with matching `executionId` immediately

#### Tree View Rendering

`IoTTopologyTree` gains an internal `highlightMap` state (derived from SSE binding events). Rendering changes:

**Device nodes:** When `highlightMap[deviceId]` exists, apply a CSS class based on status:
- `active` — pulsing accent border (scenario is targeting this device)
- `provisioned` — green check badge
- `failed` — red X badge

**Branch nodes (zone grouping):** When ≥2 devices in a branch have highlights, the branch header shows a zone-level badge: e.g., "3 active", "2 provisioned, 1 failed". The branch header gets a subtle background tint. Individual device nodes still show their status when the branch is expanded.

Zone aggregation logic:
```typescript
function computeZoneHighlight(branch: TreeBranch, highlightMap: Record<string, HighlightState>): ZoneHighlight | null {
  const highlighted = branch.devices.filter(d => highlightMap[d.deviceId]);
  if (highlighted.length < 2) return null;
  const counts = { active: 0, provisioned: 0, failed: 0 };
  highlighted.forEach(d => counts[highlightMap[d.deviceId]!.status]++);
  return { total: highlighted.length, counts };
}
```

#### Graph View Rendering

`IoTTopologyGraph` gains an internal `highlightMap` state (derived from SSE binding events). Rendering changes:

**Individual nodes:** When `highlightMap[deviceId]` exists, the node gets a highlight border:
- `active` — pulsing accent glow
- `provisioned` — green border
- `failed` — red border with alert icon

**Graph header summary:** During active scenario execution (any entries in `highlightMap`), a summary line appears above the graph grid: "N devices in M zones affected" — computed from the highlight map and node location data.

#### CSS

```css
/* Device node highlights */
.device-node.highlight-active {
  animation: pulse-highlight 1.5s ease-in-out infinite;
  border-left: 3px solid var(--pages-accent-color, #1a73e8);
}
.device-node.highlight-provisioned {
  border-left: 3px solid var(--pages-success-color, #22c55e);
}
.device-node.highlight-failed {
  border-left: 3px solid var(--pages-error-color, #ef4444);
}

/* Zone highlight */
.branch-node.zone-highlight {
  background: var(--pages-accent-color-subtle, #e8f0fe);
}

/* Graph node highlights */
.graph-node.highlight-active {
  box-shadow: 0 0 8px var(--pages-accent-color, #1a73e8);
  animation: pulse-glow 1.5s ease-in-out infinite;
}

/* Keyframe definitions */
@keyframes pulse-highlight {
  0%, 100% { border-left-color: var(--pages-accent-color, #1a73e8); opacity: 1; }
  50% { border-left-color: var(--pages-accent-color, #1a73e8); opacity: 0.5; }
}

@keyframes pulse-glow {
  0%, 100% { box-shadow: 0 0 8px var(--pages-accent-color, #1a73e8); }
  50% { box-shadow: 0 0 16px var(--pages-accent-color, #1a73e8); }
}
```

### Multi-tenancy note

The existing topology SSE broadcast has a known tenancy leak (ARC42STORIES.MD line 1480): the `BroadcastProcessor` stream is unfiltered — all SSE clients receive all broadcast events regardless of tenancy. The initial `streamTopology()` snapshot uses `tenancyId` for filtering, but updates from the broadcast are unfiltered.

Binding events inherit this limitation. `ScenarioBindingEvent` carries `tenancyId` so that per-tenant filtering can be added when the broadcast stream is upgraded — see casehubio/iot#132 for the follow-up.

## Module Impact

| Module | Changes |
|--------|---------|
| `api` | New `ScenarioBindingEvent` sealed interface with typed variants. `DeviceCommandEvent` record (existing, audit only — not used for binding). |
| `scenario` | `DesiredStateDeliveryHandler` fires `ScenarioBindingEvent` variants at plan/provision/complete lifecycle points. |
| `webapp` | New `ScenarioTopologyBinder` CDI observer. `TopologyStreamEvent` extracted from `DefaultIoTTopologyApi` to standalone record, extended with binding operations. `BroadcastProcessor` extracted to shared injectable. |
| `webapp` (frontend) | `topology-tree.ts` — implement `ConfigurablePanel`, SSE subscription, `highlightMap` state, zone badge rendering, CSS highlights. `topology-graph.ts` — implement `ConfigurablePanel`, SSE subscription, `highlightMap` state, node glow, summary header. `topology-tree-model.ts` — `computeZoneHighlight()` function. |
| `testing` | `ScenarioBindingEvent` test fixtures. Topology component tests with highlight state. |

## Boundary Rules

- `ScenarioBindingEvent` is in `casehub-iot-api` — part of the IoT domain vocabulary. Other modules that observe IoT scenario activity can depend on it.
- `DeviceCommandEvent` is in `casehub-iot-api` — general-purpose command audit event. Not specific to scenarios. Not used for binding in this design.
- The binder lives in `webapp` — it has access to the CDI events (from scenario module) and the SSE stream (from topology API).
- No changes to `casehub-pages-scenario` or `casehub-pages-push`. The binding is entirely within the IoT repo boundary.

## Testing Strategy

**Unit tests:**
- `ScenarioTopologyBinder` — verify CDI event → `TopologyStreamEvent` translation for each variant. Mock `BroadcastProcessor`.
- `DesiredStateDeliveryHandler` — verify binding events fire at correct lifecycle points with correct device sets (derived from plan, not goals).
- `computeZoneHighlight()` — zone aggregation logic with various device distributions.
- `HighlightState` state machine — verify transitions from SSE events.

**Integration tests:**
- Full SSE flow: fire `ScenarioBindingEvent` → verify SSE stream receives binding operation.
- Frontend: render `IoTTopologyTree` with highlight state → verify CSS classes and zone badges.

## References

- `DesiredStateDeliveryHandler.java:31` — delivery handler that resolves devices and provisions
- `IoTCommandPlugin.java:15` — command plugin with single device ID
- `DeviceCommandDispatcher` (scenario module) — CDI bean that dispatches commands
- `DefaultIoTTopologyApi.java:24-65` — topology SSE stream with BroadcastProcessor
- `TopologyStreamEvent` record — existing SSE event type (nested in DefaultIoTTopologyApi:64)
- `topology-tree.ts:14-112` — tree component with branch rendering and aggregates
- `topology-graph.ts:14-78` — graph component with flat node grid
- `topology.ts:1-20` — page definition (pure DSL, uses hostPanel with endpoint/sseEndpoint)
- `topology-tree-model.ts` — `TopologyNode`, `TreeBranch`, `TopologyAggregate`
- `blocks-topology-viewer` (pages package) — platform service topology viewer, NOT used for IoT
- `StateChangeEventPublisher.java:12` — uses `fireAsync()` (contrast with binding's synchronous fire)
- `IoTPushEndpoint.java:21` — push WebSocket endpoint (not used — SSE chosen instead)
- `EventBroadcaster` (pages-push JAR) — broadcast API (not used — SSE chosen for binding)
- casehubio/iot#119 — topology dual-view (dependency, completed)
- casehubio/iot#126 — desired-state delivery handler (dependency, completed)
- casehubio/iot#121 — IoT desired state epic (parent)
- casehubio/iot#131 — command binding with execution context (follow-up, deferred)
- casehubio/iot#132 — topology SSE tenancy filtering (follow-up, deferred)

# Device Topology Dual-View — Design Spec

**Issue:** casehubio/iot#119
**Branch:** issue-121-iot-desired-state-epic
**Date:** 2026-10-02
**Status:** Draft

## Overview

Add a device topology visualization with two views of the same data:

- **Logical view** — spatial hierarchy tree (Org → Building → Floor → Room → Device)
- **Physical view** — operational graph with dependency edges from desired state

Both views show drift status overlays (converged, permitted drift, unexpected drift, absent, unmonitored). A toggle switches between views on a new top-level "Topology" page. Live updates via SSE on `StateChangeEvent`.

## Architecture

Three layers:

1. **View model** (`webapp-api`) — `TopologyResponse` with nodes, edges, and location aggregates
2. **Assembler + API** (`webapp`) — `TopologyAssembler` CDI bean joins `DeviceRegistry` with optional `DesiredStateGraph`/`ActualState`/`DriftPolicy`. Exposed via `@McpDomain("iot/topology")` REST endpoint with SSE streaming.
3. **Frontend** (`webapp` TypeScript) — two new Lit web components (`blocks-topology-tree`, `blocks-topology-graph`) on a new `topology.ts` page

### Data Flow

```
DeviceRegistry.findAll()  ──┐
                             ├──▶ TopologyAssembler ──▶ TopologyResponse ──▶ REST / SSE
DesiredStateGraph (optional) ┘
     + ActualState
     + DriftPolicy
```

The `DesiredStateGraph`, `ActualState`, and `DriftPolicy` are injected as `Instance<>` — when the desiredstate module is absent, all devices appear as `UNMONITORED` with no edges. The topology feature degrades gracefully to a pure spatial tree.

## View Model

### TopologyNode

```java
record TopologyNode(
    String deviceId,
    String label,
    DeviceClass deviceClass,
    List<String> locationPath,
    boolean available,
    Instant lastUpdated,
    DriftStatus driftStatus,
    String driftDetail
)
```

`locationPath` is derived by splitting `DeviceEntity.location()` on `/`. Null or empty locations produce an empty list — the frontend groups these under "Unassigned" at the tree root.

### DriftStatus

```java
enum DriftStatus {
    CONVERGED,        // NodeStatus.PRESENT, actual matches desired
    PERMITTED_DRIFT,  // NodeStatus.DRIFTED + DriftDecision.Exempt
    UNEXPECTED_DRIFT, // NodeStatus.DRIFTED + DriftDecision.Reconcile
    ABSENT,           // NodeStatus.ABSENT — expected device missing
    UNKNOWN,          // NodeStatus.UNKNOWN or SUSPENDED
    UNMONITORED       // No DesiredNode exists for this device
}
```

### Drift Status Mapping

| NodeStatus | DriftDecision | DriftStatus | Visual |
|------------|---------------|-------------|--------|
| PRESENT | — | CONVERGED | Green |
| DRIFTED | Exempt | PERMITTED_DRIFT | Amber |
| DRIFTED | Reconcile | UNEXPECTED_DRIFT | Red |
| ABSENT | — | ABSENT | Red |
| UNKNOWN/SUSPENDED | — | UNKNOWN | Grey |
| (no desired state) | — | UNMONITORED | Green outline |

`driftDetail` carries context for PERMITTED_DRIFT (e.g. "revert in 12m" from `ExemptionSpec.revertCondition()`).

### TopologyEdge

```java
record TopologyEdge(
    String sourceDeviceId,
    String targetDeviceId,
    String label
)
```

Edges are derived from `DesiredStateGraph` ordering dependencies. Each `DesiredNode` dependency that maps to an IoT device becomes a directed edge. The `label` describes the relationship (e.g. "powers", "depends-on").

### TopologyResponse

```java
record TopologyResponse(
    List<TopologyNode> nodes,
    List<TopologyEdge> edges,
    Map<String, TopologyAggregate> locationAggregates
)
```

### TopologyAggregate

```java
record TopologyAggregate(
    int total,
    int converged,
    int permittedDrift,
    int unexpectedDrift,
    int absent,
    int unknown,
    int unmonitored
)
```

Pre-computed per location path segment (e.g. key "HQ/Floor2" aggregates all devices under that subtree). Used by the tree view for collapsed-node summaries.

## Backend

### TopologyAssembler

CDI bean in `webapp`. Injected dependencies:

- `DeviceRegistry` — required
- `Instance<DesiredStateGraph>` — optional (absent when desiredstate module not present)
- `Instance<ActualStateAdapter>` — optional
- `Instance<DriftPolicy>` — optional

Assembly steps:

1. `DeviceRegistry.findAll()` (filtered by tenancy via `CurrentPrincipal`)
2. Parse each device's `location` string by splitting on `/` to produce `locationPath`
3. If `DesiredStateGraph` is resolvable:
   a. Read `ActualState` via `ActualStateAdapter`
   b. For each device, find its `DesiredNode` by matching `NodeId` to `deviceId`
   c. Evaluate drift: check `NodeStatus` from `ActualState`, apply `DriftPolicy` to determine `DriftDecision`
   d. Map to `DriftStatus` enum per the mapping table above
   e. Extract ordering edges from `DesiredStateGraph` dependencies
4. If `DesiredStateGraph` is not resolvable: all devices get `DriftStatus.UNMONITORED`, no edges
5. Compute `locationAggregates` by walking location paths and summing drift status counts per subtree
6. Return `TopologyResponse`

### DefaultIoTTopologyApi

`@McpDomain("iot/topology")` class in `webapp`:

- `GET /api/topology` — calls `TopologyAssembler.assemble(tenancyId)`, returns `TopologyResponse`
- SSE stream via `@PlatformStream` — observes `StateChangeEvent`, re-evaluates affected nodes and pushes incremental `TopologyNode` updates

The SSE stream pushes individual node updates (not full re-assembly) when a `StateChangeEvent` fires. The assembler provides a `reassembleNode(deviceId)` method for incremental updates.

SSE event shape:
```json
{"operation": "update", "node": { ... TopologyNode fields ... }}
```

Operations: `update` (node state changed), `add` (new device discovered), `remove` (device no longer available). The frontend patches its local `TopologyResponse` accordingly.

## Frontend

### blocks-topology-tree

New Lit web component in the blocks-ui packages.

**Input properties:**
- `nodes: TopologyNode[]`
- `locationAggregates: Record<string, TopologyAggregate>`
- `selection-topic: string` (default: `"topology-device"`)

**Rendering:**
- Builds a tree from `locationPath` arrays — each unique path prefix becomes a tree node
- Collapsible branches with expand/collapse toggle
- Leaf nodes (devices) show:
  - Device class icon (standardized icon set by `DeviceClass`)
  - Label
  - Drift status badge (colored dot: green/amber/red/grey, green outline for unmonitored)
- Collapsed branch nodes show aggregate summary: "42 devices, 2 permitted drifts, 0 unexpected"
- Devices with null/empty location grouped under "Unassigned" root node
- Click on device emits `emitPagesEvent(this, selectionTopic, { deviceId })`

**Accessibility:**
- `role="tree"` / `role="treeitem"` / `role="group"` structure
- `aria-expanded` on collapsible nodes
- Keyboard navigation: Enter/ArrowRight to expand, ArrowLeft to collapse

### blocks-topology-graph

New Lit web component in the blocks-ui packages.

**Input properties:**
- `nodes: TopologyNode[]`
- `edges: TopologyEdge[]`
- `selection-topic: string` (default: `"topology-device"`)

**Rendering:**
- Uses `graph-core` to create a graph model from `TopologyNode[]` and `TopologyEdge[]`
- Uses `graph-renderer` for layout (force-directed or layered for sparse graphs)
- Each node:
  - Device class icon
  - Label
  - Background/border color by drift status (green/amber/red/grey)
  - Lock icon overlay for devices with `drift: never` policy (derived from `driftDetail`)
- Directed edges rendered between dependent devices
- Devices without edges appear as independent nodes in the layout
- Click on node emits `emitPagesEvent(this, selectionTopic, { deviceId })`

### topology.ts (page)

New top-level page registered in the webapp page list.

```typescript
page("Topology",
  rows(
    // View toggle — tree vs graph
    hostPanel("blocks-topology-toggle", {
      views: ["tree", "graph"],
      default: "tree",
    }),

    // Tree view (shown when toggle = tree)
    hostPanel("blocks-topology-tree", {
      endpoint: "/api/topology",
      sseEndpoint: "/api/topology/stream",
      selectionTopic: "topology-device",
    }),

    // Graph view (shown when toggle = graph)
    hostPanel("blocks-topology-graph", {
      endpoint: "/api/topology",
      sseEndpoint: "/api/topology/stream",
      selectionTopic: "topology-device",
    }),
  ),
)
```

Both views share the same data endpoint and SSE connection. The toggle controls visibility — only one view renders at a time.

## Module Placement

| Artifact | Module | Rationale |
|----------|--------|-----------|
| `TopologyNode`, `TopologyEdge`, `TopologyResponse`, `TopologyAggregate`, `DriftStatus` | `webapp-api` | Tier 1 view model records, no runtime deps |
| `TopologyAssembler`, `DefaultIoTTopologyApi` | `webapp` | Runtime CDI beans with `DeviceRegistry` and optional desiredstate injection |
| `blocks-topology-tree`, `blocks-topology-graph` | `webapp` (TypeScript, `.casehub-packages`) | Frontend Lit components |
| `topology.ts` | `webapp` (TypeScript, `src/pages/`) | Page definition |

No new Maven modules. All backend code fits in existing `webapp-api` (records) and `webapp` (CDI beans). Frontend components follow the existing blocks-ui pattern in `.casehub-packages/packages/`.

## Testing

### Backend

- **`TopologyAssemblerTest`** — unit test:
  - Location parsing: `"HQ/Floor2/Kitchen"` → `["HQ", "Floor2", "Kitchen"]`
  - Location parsing: `null` → `[]` (unassigned)
  - Location parsing: `"Kitchen"` → `["Kitchen"]` (flat single-level)
  - Drift status mapping for each NodeStatus × DriftDecision combination
  - Edge extraction from DesiredStateGraph dependencies
  - Graceful degradation when DesiredStateGraph is absent (all UNMONITORED, no edges)
  - Location aggregate computation (subtree rollup)

- **`DefaultIoTTopologyApiTest`** — integration test:
  - REST endpoint returns correct TopologyResponse
  - SSE stream delivers node updates on StateChangeEvent
  - Tenancy filtering applied

### Frontend

- **`blocks-topology-tree.test.ts`**:
  - Tree rendering from locationPath data
  - Collapse/expand toggling
  - Aggregate display on collapsed nodes
  - Unassigned grouping for empty locationPath
  - Drift status badge colors

- **`blocks-topology-graph.test.ts`**:
  - Node rendering with drift colors
  - Edge rendering between dependent nodes
  - Independent nodes (no edges) positioned correctly
  - Selection event emission

## Scope Boundaries

**In scope:**
- Backend view model and assembler
- REST endpoint with SSE streaming
- Two frontend Lit components (tree + graph)
- New topology page
- Drift status visualization
- Location hierarchy from parsed strings
- Dependency edges from DesiredStateGraph

**Out of scope:**
- Device class icon registry (tracked separately in #116 parent epic)
- Location data enrichment from providers (HA area registry)
- Custom edge declarations in YAML goals
- Topology page interaction with device detail pane (can use existing device detail page via navigation)
- Drag-and-drop or manual topology editing

## References

- `DeviceEntity.java:28` — location field (nullable String)
- `DeviceRegistry.java` — SPI for device lookup
- `DesiredStateGraph` — platform desiredstate API (ordering dependencies)
- `DriftPolicy` → `DriftDecision` — platform drift evaluation
- `IoTActualStateAdapter.java` — IoT adapter for reading device state
- `IoTFaultPolicy.java` — IoT fault policy (escalation)
- `blocks-decomposition-tree.ts` — pattern reference for tree Lit component
- `blocks-dag-viewer.ts` — pattern reference for graph Lit component
- `graph-core`, `graph-renderer` — graph rendering infrastructure
- `DefaultIoTDeviceApi.java` — existing device API pattern
- Terraform dependency graph (HashiCorp) — prior art for desired-state-derived edges
- Microsoft Aspire resource graph — prior art for resource dependency visualization
- LF-Edge EVE depgraph — prior art for configuration item dependency graphs
- Issue #119 — feature specification
- Issue #116 — parent epic

# Topology SSE Tenancy Filtering

**Issue:** casehubio/iot#132
**Parent:** casehubio/iot#121
**Status:** Design

## Problem

The topology SSE broadcast (`BroadcastProcessor` in `DefaultIoTTopologyApi`) leaks events across tenants. The initial `streamTopology()` snapshot is correctly tenant-filtered via `assembler.assemble(tenancyId)`, but subsequent broadcast events from `onStateChange` and `ScenarioTopologyBinder` reach all connected SSE clients regardless of tenancy.

This is a known concern documented in ARC42STORIES.MD line 1480.

## Solution

Add `tenancyId` to `TopologyStreamEvent` as a server-side-only field (`@JsonIgnore`), and filter the broadcast stream per subscriber — matching the pattern already used by `DefaultIoTDeviceApi.streamDevices()`.

## Changes

### 1. TopologyStreamEvent — add tenancyId field

Add `tenancyId` as the first field of the record. Annotate with `@JsonIgnore` to exclude from serialization. Update both constructors and the `binding()` factory method.

```java
public record TopologyStreamEvent(
        @JsonIgnore String tenancyId,
        String operation,
        List<TopologyNode> nodes,
        Map<String, Object> binding
) {
    public TopologyStreamEvent(String tenancyId, String operation, List<TopologyNode> nodes) {
        this(tenancyId, operation, nodes, null);
    }

    public static TopologyStreamEvent binding(String tenancyId, String operation, Map<String, Object> data) {
        return new TopologyStreamEvent(tenancyId, operation, List.of(), data);
    }
}
```

### 2. DefaultIoTTopologyApi — filter broadcast, tag events

In `streamTopology()`, filter the broadcast by tenancyId:

```java
Multi<TopologyStreamEvent> updates = broadcaster
        .filter(e -> e.tenancyId().equals(tenancyId));
```

In `onStateChange()`, pass tenancyId from the device entity:

```java
broadcaster.onNext(new TopologyStreamEvent(
        device.tenancyId(), "update", List.of(node)));
```

In the snapshot creation, tag with the connection's tenancyId (this is filtered by construction, but consistent):

```java
return new TopologyStreamEvent(tenancyId, "snapshot", response.nodes());
```

### 3. ScenarioTopologyBinder — pass tenancyId from binding events

`ScenarioBindingEvent` already carries `tenancyId()` on all variants (sealed interface method). Pass it through:

```java
TopologyStreamEvent.binding(event.tenancyId(), "binding-start", Map.of(...));
```

All 5 switch cases in `onBindingEvent` get `event.tenancyId()` as the first argument to `TopologyStreamEvent.binding()`.

### 4. Tests

Update `DefaultIoTTopologyApiTest` and `ScenarioTopologyBinderTest` to construct events with tenancyId. Add a test that verifies the filter: subscribe with tenant A, broadcast events for tenants A and B, assert only tenant A's events arrive.

## Scope

- 3 production files modified: `TopologyStreamEvent`, `DefaultIoTTopologyApi`, `ScenarioTopologyBinder`
- 2 test files updated: `DefaultIoTTopologyApiTest`, `ScenarioTopologyBinderTest`
- No API changes (TopologyNode, TopologyResponse unchanged)
- No frontend changes (tenancyId is `@JsonIgnore`)
- No new modules or dependencies

## Non-Goals

- Cross-tenant admin stream (different endpoint, different design)
- Filtering the device stream (already correct in `DefaultIoTDeviceApi`)
- Removing tenancyId from `DeviceResponse` wire format (separate concern)

## References

- `DefaultIoTDeviceApi.java:129-143` — existing per-subscriber filter pattern
- `ScenarioBindingEvent.java:7-8` — tenancyId on sealed interface
- `ARC42STORIES.MD:1480` — known SSE tenancy leak concern
- `docs/specs/issue-129-scenario-topology-binding/2026-10-06-scenario-topology-binding-design.md:460` — #129 design noting this gap
- casehubio/iot#129 — parent issue that added ScenarioBindingEvent with tenancyId

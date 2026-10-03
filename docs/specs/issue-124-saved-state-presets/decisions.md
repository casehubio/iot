# Decisions — #124 Saved State Presets

## Core framing

A preset is a named desired state — an `IoTGoals` YAML file in a known directory. No special type. Load, compile, and reconcile use the existing desiredstate pipeline. The only additions are name resolution, an `import:` merge directive, and a REST surface.

## D1: Module placement

**Choice:** Preset name resolution and `import:` merge in `desiredstate` module; REST surface in `webapp`
**Alternatives:**
- New `casehub-iot-presets` module — more isolation but adds module proliferation
- Webapp only — couples preset logic to runtime, blocks reuse by bridge or other consumers
**Rationale:** Presets are named desired states. Name resolution is a loading concern (belongs with `IoTGoalLoader`). REST is a runtime concern (belongs in `webapp`). No new concepts needed.
**Trade-offs:** desiredstate module grows, but presets are tightly coupled to goals anyway.
**Sources:** IoTGoalLoader.java, IoTGoalCompiler.java, webapp REST pattern
**Exploration:** quick
**Status:** captured

## D2: Composition model

**Choice:** YAML `import:` directive with one-level resolution (no transitive imports)
**Alternatives:**
- Transitive imports — recursive resolution with cycle detection; more powerful but adds complexity disproportionate to value
- Graph-level overlay — composition after compilation via `DesiredStateGraph.overlay()`; loses YAML-level visibility of what a preset contains
**Rationale:** Operators see the full expanded preset at the YAML level. One-level keeps the mental model simple — a preset imports named presets, never a chain. If deeper composition is needed later, transitive can be added without breaking one-level presets.
**Trade-offs:** Cannot compose presets-of-presets without flattening manually.
**Sources:** IoTGoalLoader.merge(), DesiredStateGraph.overlay()
**Exploration:** quick
**Status:** captured

## D3: Conflict resolution during import merge

**Choice:** Last-wins merge — later imports override earlier for shared deviceIds, with deep-merge of config maps
**Alternatives:**
- Reject conflicts — fail on duplicate deviceId; limits composition to non-overlapping presets
- Explicit override syntax — verbose `overrides:` block; most explicit but heavyweight for common case
**Rationale:** Order-sensitive last-wins is predictable and familiar (CSS cascade, Map.putAll). Operators control priority by import order. Needs a new `mergeGoals()` method that deep-merges config maps instead of the existing `merge()` which rejects duplicates.
**Trade-offs:** Silent override — an operator might not realize one preset shadows another's device config. Mitigated by the diff endpoint showing full resolved state.
**Sources:** IoTGoalLoader.merge() validation logic
**Exploration:** quick
**Status:** captured

## D4: Apply semantics

**Choice:** Synchronous — block until convergence completes, return full result
**Alternatives:**
- Async with correlation ID — 202 + polling/SSE; better for slow large deployments
- Both via query param — flexibility at cost of two code paths
**Rationale:** Virtual threads make blocking cheap. Presets target a handful of devices, not thousands. Synchronous gives immediate feedback and simpler client code. If async is needed later, it's additive.
**Trade-offs:** Large presets with slow devices could time out. Acceptable for the IoT scale (tens of devices, not thousands).
**Sources:** Virtual threads (ADR-0005), existing synchronous SPI pattern
**Exploration:** quick
**Status:** captured

## D5: Diff format

**Choice:** Per-device property diff — `[{deviceId, deviceClass, changes: [{property, current, desired}]}]`
**Alternatives:**
- DesiredStateGraph diff — richer but harder to consume
- Flat key-value diff — loses device grouping and class metadata
**Rationale:** Matches DeviceConfigSpec granularity. Easy to render in UI — one card per device, property-level changes within. Includes deviceClass for icon rendering in topology view.
**Trade-offs:** Doesn't expose graph-level information (dependency edges). Acceptable — preset diff is about property changes, not graph structure.
**Sources:** DeviceConfigSpec, IoTActualStateAdapter
**Exploration:** quick
**Status:** captured

## D6: Preset discovery

**Choice:** `GET /iot/presets` list endpoint — scans directory, returns metadata per preset
**Alternatives:**
- No discovery endpoint — simpler but operators must know preset names out-of-band
**Rationale:** Low implementation cost (directory scan + YAML header parse). Essential for any future UI that offers preset selection.
**Trade-offs:** Filesystem scan on every call. Negligible for a small directory of YAML files.
**Sources:** Existing REST pattern (`@PlatformQuery`)
**Exploration:** quick
**Status:** captured

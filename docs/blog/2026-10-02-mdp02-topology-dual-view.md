---
title: "Two views of the same devices"
date: 2026-10-02
author: mdp
entry_type: note
subtype: diary
projects: [casehub-iot]
tags: [topology, drift, spatial-tree, operational-graph, desiredstate, lit]
issues: [119, 121]
---

# Two views of the same devices

A device list answers "what devices exist and what's their state." Useful,
but it doesn't answer two other questions that matter when you're
operating a fleet: "where are they?" and "how do they relate to each
other?"

The first question is spatial. Devices live in locations — HQ, Floor 2,
Kitchen. The answer is a tree: a spatial hierarchy you can collapse and
expand, with aggregate health at each level. The second question is
operational. Devices have dependencies — the thermostat depends on the
temperature sensor, the alarm depends on all door locks being armed. The
answer is a graph: nodes are devices, edges are operational dependencies,
and colour tells you what's drifted from desired state.

<svg viewBox="0 0 720 300" xmlns="http://www.w3.org/2000/svg" style="max-width:720px;font-family:system-ui,sans-serif">
  <!-- Spatial tree (left) -->
  <text x="100" y="20" font-size="14" font-weight="700" fill="#333" text-anchor="middle">Spatial Tree</text>
  <text x="100" y="36" font-size="10" fill="#888" text-anchor="middle">"Where are they?"</text>

  <!-- Root -->
  <rect x="60" y="50" width="80" height="26" rx="4" fill="#e3f2fd" stroke="#42a5f5"/>
  <text x="74" y="67" font-size="10" fill="#1565c0">HQ (5/6)</text>

  <!-- Floor 1 -->
  <line x1="100" y1="76" x2="60" y2="100" stroke="#90caf9"/>
  <rect x="20" y="100" width="80" height="26" rx="4" fill="#e8f5e9" stroke="#66bb6a"/>
  <text x="30" y="117" font-size="10" fill="#2e7d32">Floor 1 (3/3)</text>

  <!-- Floor 2 -->
  <line x1="100" y1="76" x2="140" y2="100" stroke="#90caf9"/>
  <rect x="100" y="100" width="80" height="26" rx="4" fill="#fff3e0" stroke="#ffa726"/>
  <text x="108" y="117" font-size="10" fill="#e65100">Floor 2 (2/3)</text>

  <!-- Devices under Floor 1 -->
  <line x1="60" y1="126" x2="25" y2="150" stroke="#c8e6c9"/>
  <circle cx="25" cy="160" r="8" fill="#66bb6a"/>
  <text x="38" y="164" font-size="9" fill="#555">Light</text>

  <line x1="60" y1="126" x2="60" y2="150" stroke="#c8e6c9"/>
  <circle cx="60" cy="160" r="8" fill="#66bb6a"/>
  <text x="73" y="164" font-size="9" fill="#555">Therm</text>

  <line x1="60" y1="126" x2="95" y2="150" stroke="#c8e6c9"/>
  <circle cx="95" cy="160" r="8" fill="#66bb6a"/>
  <text x="108" y="164" font-size="9" fill="#555">Lock</text>

  <!-- Devices under Floor 2 -->
  <line x1="140" y1="126" x2="120" y2="150" stroke="#ffe0b2"/>
  <circle cx="120" cy="160" r="8" fill="#66bb6a"/>
  <text x="133" y="164" font-size="9" fill="#555">Light</text>

  <line x1="140" y1="126" x2="155" y2="150" stroke="#ffe0b2"/>
  <circle cx="155" cy="160" r="8" fill="#66bb6a"/>
  <text x="168" y="164" font-size="9" fill="#555">Sensor</text>

  <line x1="140" y1="126" x2="185" y2="150" stroke="#ffe0b2"/>
  <circle cx="185" cy="160" r="8" fill="#ef5350"/>
  <text x="198" y="164" font-size="9" fill="#c62828">Lock ⚠</text>

  <!-- Divider -->
  <line x1="240" y1="30" x2="240" y2="280" stroke="#e0e0e0" stroke-dasharray="4,4"/>

  <!-- Operational graph (right) -->
  <text x="480" y="20" font-size="14" font-weight="700" fill="#333" text-anchor="middle">Operational Graph</text>
  <text x="480" y="36" font-size="10" fill="#888" text-anchor="middle">"How do they relate?"</text>

  <!-- Nodes -->
  <circle cx="380" cy="80" r="20" fill="#66bb6a" stroke="#fff" stroke-width="2"/>
  <text x="380" y="84" font-size="9" fill="#fff" text-anchor="middle">Sensor</text>

  <circle cx="480" cy="80" r="20" fill="#66bb6a" stroke="#fff" stroke-width="2"/>
  <text x="480" y="84" font-size="9" fill="#fff" text-anchor="middle">Therm</text>

  <circle cx="350" cy="170" r="20" fill="#66bb6a" stroke="#fff" stroke-width="2"/>
  <text x="350" y="174" font-size="9" fill="#fff" text-anchor="middle">Lock 1</text>

  <circle cx="450" cy="170" r="20" fill="#ef5350" stroke="#fff" stroke-width="2"/>
  <text x="450" y="174" font-size="9" fill="#fff" text-anchor="middle">Lock 2</text>

  <circle cx="550" cy="170" r="20" fill="#66bb6a" stroke="#fff" stroke-width="2"/>
  <text x="550" y="174" font-size="9" fill="#fff" text-anchor="middle">Lock 3</text>

  <circle cx="450" cy="260" r="22" fill="#42a5f5" stroke="#fff" stroke-width="2"/>
  <text x="450" y="264" font-size="9" fill="#fff" text-anchor="middle">Alarm</text>

  <!-- Edges -->
  <line x1="380" y1="100" x2="480" y2="100" stroke="#aaa" stroke-dasharray="3,3"/>
  <text x="430" y="95" font-size="8" fill="#888" text-anchor="middle">reads</text>

  <line x1="350" y1="190" x2="450" y2="240" stroke="#666"/>
  <line x1="450" y1="190" x2="450" y2="238" stroke="#ef5350" stroke-width="2"/>
  <line x1="550" y1="190" x2="450" y2="240" stroke="#666"/>

  <!-- Drift badge -->
  <rect x="465" y="145" width="40" height="16" rx="3" fill="#ffebee" stroke="#ef5350"/>
  <text x="470" y="157" font-size="8" fill="#c62828">DRIFT</text>
</svg>

These are fundamentally different visualisations, not tabs on the same
component. The tree is DOM — collapsible nested `<details>` elements
with aggregate badges at each level. The graph is a canvas with positioned
nodes and directed edges. They share data types but not rendering logic.

## Where the data comes from

The spatial tree comes from `DeviceEntity.location()`. OpenHAB populates
this from `thing.location()` — the convention is path-like segments:
`"HQ/Floor2/Kitchen"`. Home Assistant locations are null today (area
registry integration is pending). The parser splits on `/`, builds tree
nodes for each segment, and groups devices under their leaf location.
Devices with null location appear at the root as "Unassigned."

The operational graph comes from `DesiredStateGraph`. The desiredstate
module (just moved from ops — previous entry) models device dependencies
as directed edges: if `lock-front` must be locked before `alarm-1` can
arm, there's a `Dependency(alarm-config, lock-config)` edge. The graph
view extracts these edges and decorates each node with its drift status
relative to desired state.

This was a key design decision: derive edges from desired state
declarations rather than inventing a new edge declaration format. Strong
precedent from infrastructure-as-code tools — Terraform's dependency
graph, Microsoft Aspire's resource graph, LF-Edge EVE's depgraph all
derive dependencies from state declarations. Most IoT devices are
independent; edges only appear when operational dependencies exist. The
sparse graph is the expected case.

## Drift visualisation

Drift is the interesting layer. A device can be in one of three states
relative to its desired state goal:

<svg viewBox="0 0 500 90" xmlns="http://www.w3.org/2000/svg" style="max-width:500px;font-family:system-ui,sans-serif">
  <rect x="10" y="10" width="140" height="60" rx="6" fill="#e8f5e9" stroke="#66bb6a" stroke-width="2"/>
  <text x="40" y="35" font-size="11" font-weight="600" fill="#2e7d32">CONVERGED</text>
  <text x="22" y="55" font-size="9" fill="#555">actual == desired</text>

  <rect x="180" y="10" width="140" height="60" rx="6" fill="#fff3e0" stroke="#ffa726" stroke-width="2"/>
  <text x="220" y="35" font-size="11" font-weight="600" fill="#e65100">DRIFTED</text>
  <text x="192" y="55" font-size="9" fill="#555">actual != desired</text>

  <rect x="350" y="10" width="140" height="60" rx="6" fill="#fafafa" stroke="#bbb" stroke-width="2"/>
  <text x="380" y="35" font-size="11" font-weight="600" fill="#666">NO GOAL</text>
  <text x="362" y="55" font-size="9" fill="#555">no desired state set</text>
</svg>

`TopologyAssembler` joins `DeviceRegistry` data with the optional
`DesiredStateGraph` and `DriftPolicy`. When the desiredstate module is
present, each device gets a `DriftStatus` — CONVERGED, DRIFTED, or
NO_GOAL. When it's absent, everything is NO_GOAL. The drift computation
is the same normalised comparison that `IoTActualStateAdapter` uses:
`CapabilityNormalizer.normalize()` on both sides, then key-by-key
equality check.

Location aggregates roll up drift to each tree level. A location with
5 converged devices and 1 drifted device shows `5/6` in green-to-amber
as a visual cue that something needs attention without requiring you to
expand every branch.

## The TopologyApi

The backend follows the `@McpDomain` pattern established by the device
and simulation APIs — a REST endpoint with SSE streaming for live
updates. A single `/api/topology` endpoint returns the full topology
response: nodes enriched with drift status, location hierarchy for the
tree view, and dependency edges for the graph view. The desiredstate-api
dependency is `<optional>true</optional>` — the topology endpoint
activates only when the desiredstate module is on the classpath.

## Frontend components

The components live in `src/components/`, not `.casehub-packages/`.
The `.casehub-packages` directory is gitignored — it holds external
packages synced via Maven SNAPSHOT. Project-specific components go in
`src/components/`.

Model logic is extracted into pure-function modules with unit tests:
`topology-tree-model.ts` (10 tests) handles tree building and aggregate
formatting, `topology-graph-model.ts` (7 tests) handles graph view model
construction with drift decorations. Lit decorator tests don't work
reliably in this repo's vitest/jsdom setup — extracting model logic into
pure functions was the practical path to test coverage.

The tree component uses native DOM: collapsible `<details>` elements with
drift badges and aggregate summaries. The graph component positions nodes
and draws edges — drift colours the node fills, dependency directions
show the operational flow.

A new top-level Topology page sits alongside devices, cases, situations
in the webapp sidebar. The devices page answers "what exists and what's
its state." The topology page answers "where are they and how do they
relate." Different questions, different page.

---
layout: post
title: "Bridging the Scenario Engine to the Topology View"
date: 2026-10-06
entry_type: note
subtype: diary
projects: [casehubio/iot]
tags: [cdi, sse, topology, scenario, lit-element]
series: issue-129-scenario-topology-binding
---

# Bridging the Scenario Engine to the Topology View

The scenario engine and the topology dual-view have been working independently since they shipped. You could run a playbook that set every light in the house to nightmode and converged fifteen devices through the desired-state pipeline — but the topology view just sat there, unchanged, showing the same static drift badges. The two systems didn't know about each other.

The design question was more interesting than the implementation. The scenario engine lives in `casehub-pages-scenario-runtime` — a generic playbook executor that doesn't know what a "device" is. The topology view is pure IoT. So the bridge has to live in the IoT repo, connecting a domain-agnostic system to a domain-specific visualisation. And whatever pattern we chose here would set the template for future bindings — work items reacting to scenario steps, case timelines annotating playbook execution, any domain-specific UI that needs to know what a generic step just did.

We went with CDI events. The `DesiredStateDeliveryHandler` already knows which devices it's provisioning — it resolves them from inline config or presets, compiles a graph, and walks the provision loop. We added a sealed interface (`ScenarioBindingEvent`) with typed variants — `StepStart`, `DeviceProvisioned`, `DeviceFailed`, `StepComplete`, `StepFailed`, `Clear` — and fire them synchronously at each lifecycle point. Synchronous matters here: binding events have strict ordering (start before per-device updates before complete), unlike `StateChangeEvent` which uses `fireAsync` because independent device state changes don't need ordering guarantees.

The sealed interface was the right call. CDI observers can pattern-match on specific variants (`@Observes ScenarioBindingEvent.StepStart`) or observe the whole hierarchy. The exhaustive switch in the binder catches every variant at compile time — add a new variant and the compiler tells you everywhere that needs updating.

One subtlety worth noting: the `IoTGoalCompiler` creates two graph nodes per physical device — a physical node and a config node, with a dependency edge between them. Both can appear in the provision plan. Without deduplication, a device with a physical and config node would fire `DeviceProvisioned` twice. The handler tracks outcomes in a `LinkedHashMap<String, String>` keyed by device ID (stripped of the `-config` suffix), firing the binding event only on the first success per device.

On the transport side, we extended the existing topology SSE stream rather than using the push WebSocket. This was a design review catch — the push system (`EventBroadcaster`) persists events via `EventStore`, which means binding events would replay on reconnect. Stale "device provisioning" highlights from a step that finished minutes ago aren't useful. The SSE `BroadcastProcessor` is in-memory only — transient by nature, which is exactly what binding state needs.

The frontend work was the most straightforward part. A pure-function state machine (`applyBindingEvent`) handles all the SSE event processing — testable without any DOM, framework, or timer dependencies. The topology tree gets zone-level badges when multiple devices in the same location are affected ("3 active", "2 provisioned, 1 failed"), computed from the existing `TreeBranch` hierarchy. The graph view gets per-node glow animations and a summary header line.

The command path (`IoTCommandPlugin`) doesn't participate yet. It's a `@Plugin` record — no CDI access, no execution context to correlate with scenario steps. That needs changes to the plugin execution SPI to thread an execution ID through. Filed as #131 for later.

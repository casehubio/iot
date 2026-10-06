## D1: Event architecture for step → device binding

**Choice:** CDI Event Bridge — IoT handlers fire synchronous CDI events (`Event.fire()`) with affected device IDs; a webapp observer bridges to the topology SSE stream.
**Alternatives:**
- Scenario Engine SPI (`StepLifecycleListener`) — clean contract but requires upstream change to casehub-pages-scenario-runtime, only provides step-level before/after hooks with no mid-execution per-device progress, premature abstraction
- Push Protocol Enrichment — enrich result maps with device IDs, add `scenario:step-detail` topic — also upstream change, completion-only, convention-based
**Rationale:** No upstream changes needed. CDI event sourcing pattern matches `StateChangeEvent` → topology SSE in `DefaultIoTTopologyApi` (same producer-side decoupling — CDI event fired, observer translates). Each domain defines its own CDI event and observer independently — reusable template for work items, CBR, etc. Full lifecycle coverage (before, during, after). Synchronous `Event.fire()` guarantees ordering: step-start → device-update* → step-complete. Async would lose this ordering since binding events have strict lifecycle semantics unlike fire-and-forget StateChangeEvents.
**Event firing semantics:** `step-start` fires before the provision loop (announces affected device set). `device-update` fires after each individual device provision (announces result: success/failure/timeout). `step-complete` fires after the provision loop ends (summarises step outcome). For `IoTCommandPlugin`, single `device-update` event wraps the dispatch call.
**Trade-offs:** Two emission points (delivery handler + command dispatcher) instead of one hook — but a lifecycle SPI can only provide step-level hooks, not the per-device progress that issue #129 requires. Synchronous CDI events add serialization + broadcast latency to each device provision iteration.
**Sources:** `DesiredStateDeliveryHandler.java:31`, `IoTCommandPlugin.java:15`, `DefaultIoTTopologyApi.java:55` (StateChangeEvent pattern), `DefaultIoTTopologyApi.java:63` (TopologyStreamEvent record)
**Exploration:** deep-analysis
**Status:** revised — clarified sync/async choice (Event.fire() for ordering), added event firing semantics (R1-04, R1-17), corrected rationale to distinguish CDI event pattern from delivery channel (R1-02)

## D2: Topology SSE binding stream

**Choice:** Extend existing topology SSE stream — add "binding" operation type to `TopologyStreamEvent`, bridged from a CDI observer in `DefaultIoTTopologyApi` (or a dedicated `@ApplicationScoped` bean). Event schema: step-start, device-update, step-complete, clear — each carrying executionId for scenario-scoped binding.
**Alternatives:**
- Push WebSocket topic `iot:scenario:binding` via `EventBroadcaster` — introduces a second real-time channel alongside SSE creating dual-channel timing misalignment, `EventBroadcaster.broadcast()` persists events via `EventStore.append()` causing stale binding replays on reconnect, push infrastructure (`EventBroadcaster`) has zero usages in IoT project Java code
- Separate topics per concern (`iot:scenario:command`, `iot:scenario:convergence`) — pushes merge complexity to every frontend consumer
**Rationale:** Keeps all topology data on one channel — device state and binding state arrive on the same SSE stream, eliminating dual-channel timing misalignment. Existing hostPanel SSE subscription delivers binding events alongside device updates without new frontend infrastructure. No persistence/replay problem — SSE BroadcastProcessor is in-memory only, so transient binding state doesn't accumulate stale events. Zero new dependencies.
**Event schema:**
- `step-start`: executionId, stepName, affectedDeviceIds[]
- `device-update`: executionId, deviceId, status (PROVISIONING | PROVISIONED | FAILED | TIMEOUT)
- `step-complete`: executionId, stepName, outcome (OK | FAILED), summary
- `clear`: executionId — removes highlights for a cancelled step execution (abnormal termination only; normal completion is signalled by step-complete)
**Trade-offs:** TopologyStreamEvent record must evolve to carry polymorphic payload (binding data alongside node lists). The topology SSE stream mixes device-state and scenario-binding concerns — architecturally justified because both target the same view.
**Sources:** `DefaultIoTTopologyApi.java:55-66` (existing SSE stream pattern), `TopologyStreamEvent` record
**Depends on:** D1 (CDI event bridge provides the binding events that this stream carries)
**Exploration:** quick
**Status:** revised — changed transport from push WebSocket to extended topology SSE stream (R1-06), added event schema with executionId (R1-07, R1-16), resolved replay concern (R1-07), resolved dual-channel timing (R1-18)

## D3: Frontend binding model

**Choice:** SSE-delivered binding state — topology components receive binding events through existing SSE channel as TopologyStreamEvent operations. Both tree and graph components gain a `highlightMap: Record<string, HighlightState>` property as a binding overlay separate from nodes.
**Alternatives:**
- Page-level push subscription — invalid: `topologyPage()` is a pure declarative DSL function returning a static data structure, has no lifecycle or state management capability
- Components self-subscribe to push topics — duplicate WebSocket connections, hidden I/O, components become stateful
- Shared service via Lit context — adds framework pattern not currently used in IoT frontend
**Rationale:** With SSE transport (D2 revision), binding events arrive through the existing topology SSE channel. The pages-runtime data pipeline delivers these events to components through the same mechanism as device state updates. Components receive binding state as a `highlightMap` property — an overlay alongside `nodes`, not embedded in `TopologyNode`. Components stay presentational: they render highlights from the map, with no subscription logic. Single SSE connection serves both device state and binding state.
**Interface additions:** `highlightMap: Record<string, HighlightState>` on both `IoTTopologyTree` and `IoTTopologyGraph`. `HighlightState` captures status (provisioning/provisioned/failed) and executionId. `TopologyNode` is NOT modified — binding is an overlay concern.
**Trade-offs:** Components need new properties and rendering logic for highlights. The SSE event handler (in the data pipeline or a mediator component) must split TopologyStreamEvent into node updates and binding updates.
**Sources:** `topology.ts:1-21` (page DSL — pure declaration), `topology-tree.ts:14-17` (current properties: nodes, locationAggregates, selectionTopic — no highlightMap), `topology-graph.ts:15-17` (current properties: nodes, edges, selectionTopic — no highlightMap)
**Depends on:** D2 (SSE stream provides binding events)
**Exploration:** quick
**Status:** revised — changed from push topic subscription to SSE-delivered binding (R1-10 page DSL constraint), specified highlightMap property as separate overlay (R1-11), acknowledged component interface changes needed

## D4: Zone grouping strategy

**Choice:** Tree-view zone aggregation, graph-view individual nodes — tree branch header shows zone-level highlight with badge when ≥2 devices in same branch are affected. Graph view highlights individual nodes only, with a summary line in the graph header showing "N devices in M zones affected" during active scenario execution.
**Alternatives:**
- Zone grouping in both views — graph view is a flat operational diagram where spatial hierarchy is intentionally absent; zone grouping is architecturally inappropriate for a view that deliberately omits hierarchical structure
- Only individual node highlighting — simplest but doesn't satisfy issue requirement when presets affect many devices across rooms
**Rationale:** Leverages existing TreeBranch hierarchy and TopologyAggregate pattern. Zone grouping is visually natural in the spatial tree. The graph view is deliberately flat — it's an operational view where nodes represent individual devices without spatial hierarchy. Adding zone grouping to the graph would introduce hierarchical structure that the graph design intentionally avoids. A summary line in the graph header provides zone context without restructuring the flat grid.
**Trade-offs:** Asymmetric behaviour between tree and graph views — by design, not by compromise. The graph summary line shows aggregate spatial context without adding spatial grouping to the flat layout.
**Sources:** `topology-tree.ts:52-78` (branch rendering with aggregates), `topology-graph.ts:50-77` (flat node grid)
**Depends on:** D3 (components receive highlight state as props)
**Exploration:** quick
**Status:** revised — replaced cost-based rationale with architectural argument (R1-13), added graph header summary line for zone context (R1-14)

## D5: Binder component placement

**Choice:** Dedicated `@ApplicationScoped` CDI observer bean in the webapp module — observes `ScenarioBindingEvent` CDI events from both `DesiredStateDeliveryHandler` and `DeviceCommandDispatcher`, normalises into binding-typed `TopologyStreamEvent` operations, and pushes to the topology `BroadcastProcessor`.
**Alternatives:**
- Inline observers in DefaultIoTTopologyApi — couples the topology API to scenario binding concerns; DefaultIoTTopologyApi already handles StateChangeEvent, adding a second observer blurs its responsibility
- Scenario module placement — the scenario module has no dependency on webapp (where the SSE stream lives); would require a new dependency edge
**Rationale:** A dedicated binder bean in webapp keeps the merging logic explicit and testable. It observes CDI events (decoupled from the handlers that fire them) and writes to the BroadcastProcessor (decoupled from SSE delivery). DefaultIoTTopologyApi stays focused on device state. The binder handles ExecutionId tracking, highlight state normalisation, and clear event generation.
**Trade-offs:** One additional CDI bean. The binder needs access to the BroadcastProcessor, which must be extracted from DefaultIoTTopologyApi into a shared injectable or exposed via a method.
**Sources:** `DefaultIoTTopologyApi.java:26-30` (BroadcastProcessor lifecycle)
**Depends on:** D1 (CDI events are the input), D2 (SSE stream is the output)
**Exploration:** implicit-surfaced
**Status:** captured — surfaced by reviewer as implicit decision (R1-08)

## D6: Multi-scenario concurrency model

**Choice:** Step-scoped binding via handler-generated executionId — each handler invocation generates a UUID as the executionId for its binding events. Highlights are scoped to a step execution, not a scenario execution. `step-complete` implicitly signals the end of that step's bindings. Explicit `clear` is only needed for abnormal termination (scenario cancelled mid-step).
**ExecutionId derivation:** The handler generates `UUID.randomUUID().toString()` at the start of `execute()`. This is self-contained — no upstream runtime support needed. The `DeliveryContext` interface provides `config(key)`, `resolve(template)`, `resolveMap(data)` but NO `executionId()` method. The `ScenarioExecutor` does not handle `GenericStep` yet (zero references to `ScenarioStep.GenericStep` in project+libraries), so depending on a runtime-provided ID means waiting for an upstream integration that hasn't been designed.
**Alternatives:**
- Runtime-provided scenario-scoped executionId via `DeliveryContext.config("executionId")` — requires upstream change to `casehub-pages-scenario-runtime` to generate and thread a scenario-level UUID through `ScenarioExecutor.execute()` → `DeliveryContext`; couples generic scenario engine to IoT-specific binding semantics; the `GenericStep` execution path doesn't exist yet
- Last-write-wins (no scoping) — device Y with conflicting highlights from scenarios A and B shows whichever event arrived last; clear from scenario A removes scenario B's highlights
- Full concurrent display — stack all active scenarios' highlights with visual differentiation (colour-coded per scenario); complex frontend state management for unclear UX benefit
**Rationale:** The handler is the natural ID authority — it fires binding events, so it generates the correlation ID. Step-scoped highlighting is more natural for convergence progress: show which devices are currently being provisioned, not an accumulation of past steps' results. When step 1 completes, its devices are provisioned and no longer "in progress" — clearing its highlights immediately is the correct UX. For a multi-step scenario (step 1: set living room, step 2: set bedroom), each step's highlights appear during that step's execution and clear on step-complete. No cross-step or cross-scenario corruption because each invocation has a unique UUID.
**Trade-offs:** No single "clear all highlights for this scenario" event — each step manages its own highlights via step-complete. For abnormal termination mid-step, the cancellation handler fires `clear(currentExecutionId)`. Previous steps' highlights are already cleared.
**Sources:** `DesiredStateDeliveryHandler.java:69-72` (execute method receives stepName, data, ctx), `DeliveryContext` interface (config/resolve/resolveMap — no executionId method), `ScenarioStep.GenericStep` (delivery + data fields, zero references — execution path not yet wired)
**Depends on:** D2 (event schema carries executionId)
**Exploration:** implicit-surfaced
**Status:** revised — changed from scenario-scoped to step-scoped executionId, handler-generated UUID (R2-02)

---
title: "The concurrency engine was already there"
date: 2026-10-03
author: mdp
entry_type: note
subtype: diary
projects: [casehub-iot, casehub-platform]
tags: [scenario, concurrency, orchestration, virtual-threads, plugins, desiredstate, first-principles]
issues: [117, 121]
---

# The concurrency engine was already there

The issue description reads like a significant engineering project: map
scenario concurrency primitives — parallel branches, join gates,
conditional routing, temporal delays, compensating actions — to
distributed device command orchestration. Fan-out commands to multiple
devices, wait for confirmations, handle timeouts, coordinate dependent
actions. A distributed control problem.

The design started with exploration. Where do the concurrency primitives
live? What does the scenario runtime look like? What does the execution
model look like?

I found the scenario YAML files — `morning-routine.yaml`,
`emergency.yaml` — sequential sections with steps. No parallel branches
anywhere. The TypeScript code in `casehub-pages` had the full set:
`ParallelStep`, `BarrierStep`, `QuorumStep`, `IfElseStep`, `MatchStep`,
`TryCatchFinallyStep`, `SelectStep`. Rich orchestration primitives —
`OrcSemaphore`, `OrcLatch`, `OrcSignal`, `OrcChannel`,
`CorrelationScope`, `BlockingOrcStateMachine`. All in TypeScript. All
executing in the browser.

The server-side Java `ScenarioOrchestrator` dispatches steps sequentially.
No parallel fan-out. No join gates. The conclusion seemed clear: the
concurrency lives in TypeScript, the Java side is behind, and this issue
is about building the Java concurrency engine.

That conclusion was wrong.

## The correction

The TypeScript primitives are emulations. The real implementations are
in Java — in `casehub-platform/yaml-core`, a repo I hadn't searched.
Every TypeScript concurrency type has a Java counterpart, and the Java
versions are the source of truth.

<svg viewBox="0 0 700 420" xmlns="http://www.w3.org/2000/svg" style="max-width:700px;font-family:system-ui,sans-serif">
  <defs>
    <marker id="arr3" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6" fill="none" stroke="#555" stroke-width="1.2"/>
    </marker>
  </defs>

  <text x="350" y="22" font-size="14" font-weight="700" fill="#333" text-anchor="middle">Platform Step Execution Architecture</text>

  <!-- YAML layer -->
  <rect x="30" y="40" width="640" height="55" rx="6" fill="#fff8e1" stroke="#ffc107"/>
  <text x="50" y="62" font-size="12" font-weight="600" fill="#f57f17">YAML Scenario</text>
  <text x="50" y="80" font-size="10" fill="#666">parallel:, barrier:, quorum:, try:/catch:, if:/then:/else:, match:/cases:, select:</text>

  <!-- StepWalker -->
  <rect x="200" y="115" width="300" height="40" rx="6" fill="#e8eaf6" stroke="#5c6bc0"/>
  <text x="260" y="140" font-size="12" font-weight="600" fill="#283593">StepWalker.resolve()</text>
  <line x1="350" y1="95" x2="350" y2="115" stroke="#555" marker-end="url(#arr3)"/>

  <!-- ResolvedStep types -->
  <rect x="50" y="175" width="600" height="45" rx="6" fill="#e3f2fd" stroke="#42a5f5"/>
  <text x="70" y="193" font-size="10" font-weight="600" fill="#1565c0">ResolvedStep sealed hierarchy</text>
  <text x="70" y="208" font-size="9" fill="#555">ParallelStep | BarrierStep | QuorumStep | IfElseStep | MatchStep | TryCatchFinallyStep | SelectStep | PluginStep</text>
  <line x1="350" y1="155" x2="350" y2="175" stroke="#555" marker-end="url(#arr3)"/>

  <!-- StructuralStepEvaluator -->
  <rect x="150" y="240" width="400" height="55" rx="6" fill="#e8f5e9" stroke="#66bb6a" stroke-width="2"/>
  <text x="200" y="262" font-size="12" font-weight="700" fill="#2e7d32">StructuralStepEvaluator</text>
  <text x="200" y="280" font-size="10" fill="#555">Virtual thread fan-out, OrcLatch, QuorumTracker, try/catch, select race</text>
  <line x1="350" y1="220" x2="350" y2="240" stroke="#555" marker-end="url(#arr3)"/>

  <!-- Orchestration primitives -->
  <rect x="30" y="315" width="280" height="90" rx="6" fill="#f3e5f5" stroke="#ab47bc"/>
  <text x="50" y="338" font-size="11" font-weight="600" fill="#6a1b9a">Orchestration Primitives (yaml-core)</text>
  <text x="50" y="356" font-size="9" fill="#555">OrcLatch — countdown join gate</text>
  <text x="50" y="370" font-size="9" fill="#555">OrcSemaphore — mutual exclusion</text>
  <text x="50" y="384" font-size="9" fill="#555">OrcSignal / OrcChannel — pub/sub</text>
  <text x="50" y="398" font-size="9" fill="#555">CorrelationScope — request/response tracking</text>
  <line x1="250" y1="295" x2="170" y2="315" stroke="#ab47bc" stroke-dasharray="4,3"/>

  <!-- Plugin dispatch -->
  <rect x="390" y="315" width="280" height="90" rx="6" fill="#fce4ec" stroke="#ef5350"/>
  <text x="410" y="338" font-size="11" font-weight="600" fill="#c62828">Plugin Dispatch (StepRunner)</text>
  <text x="410" y="358" font-size="10" fill="#555">PluginStep → @Plugin record</text>
  <text x="410" y="374" font-size="10" fill="#555">@Execute method → CDI services</text>
  <text x="410" y="392" font-size="10" fill="#c62828" font-weight="600">iot.command → DeviceProvider.dispatch()</text>
  <line x1="450" y1="295" x2="530" y2="315" stroke="#ef5350" stroke-dasharray="4,3"/>
</svg>

`StructuralStepEvaluator` in `yaml-step-runtime` handles the full set:
`evaluateParallel()` spawns each child step on its own virtual thread
via `Executors.newVirtualThreadPerTaskExecutor()`, then `Future.get()`
to join. `evaluateBarrier()` uses `OrcLatch.await()` with optional
timeout. `evaluateQuorum()` pairs an `OrcLatch` with a `QuorumTracker`
that detects unreachability — when enough steps have failed that the
required count can never be reached, it releases the latch early and
reports the failure. `evaluateSelect()` races signal and channel
branches on virtual threads, cancelling losers when a winner emerges.
`evaluateTryCatchFinally()` provides the compensation path — the catch
block has access to `${result.step-name}` to inspect which steps
succeeded and dispatch compensating actions.

The whole thing is virtual-thread-native. No reactive wrappers, no
`Uni<>`, no event loops. Blocking calls on virtual threads. Exactly what
ADR-0005 prescribes.

The error was looking in `casehub-pages` instead of
`casehub-platform/yaml-core`. The pages backend `ScenarioOrchestrator`
is a demo/walkthrough dispatcher — it sends steps to browser clients for
UI interactions. The actual step execution engine lives in the platform.
The dependency chain was right there: pages depends on platform, the
IoT webapp imports from pages. Following the chain would have found the
Java primitives immediately.

## First-principles: imperative vs declarative

With the engine found, the real design question emerged: how should this
relate to the desiredstate module we'd just built?

Both operate on devices via `DeviceProvider`. Both handle concurrency —
desiredstate's `ParallelTransitionExecutor` fans out provisioning across
topological layers on virtual threads. Both handle failure — desiredstate
has `FaultPolicy` with threshold escalation.

The temptation was integration. An `iot.converge` step that submits a
`DesiredStateGraph` from within a scenario, waits for convergence,
bridges the two models. Elegant in theory.

First principles says otherwise. Every IoT device command is
fire-and-verify. `dispatch()` returning SENT means "the provider accepted
the command," not "the device changed state." A lock command might fail
physically — dead battery, mechanical jam. But this verification problem
has two fundamentally different solutions depending on what you're trying
to do:

<svg viewBox="0 0 700 200" xmlns="http://www.w3.org/2000/svg" style="max-width:700px;font-family:system-ui,sans-serif">
  <!-- Imperative -->
  <rect x="20" y="20" width="310" height="160" rx="8" fill="#e8f5e9" stroke="#66bb6a" stroke-width="1.5"/>
  <text x="100" y="45" font-size="13" font-weight="700" fill="#2e7d32">Imperative Orchestration</text>
  <text x="40" y="70" font-size="10" fill="#555">Scenario step runtime</text>
  <text x="40" y="88" font-size="10" fill="#555">"Lock doors, then arm alarm"</text>
  <text x="40" y="110" font-size="10" fill="#2e7d32">Finite workflow — runs once</text>
  <text x="40" y="128" font-size="10" fill="#2e7d32">Compensation via try/catch</text>
  <text x="40" y="146" font-size="10" fill="#2e7d32">Verification via retry decorator</text>
  <text x="40" y="164" font-size="10" fill="#2e7d32">Conditional routing (if/match)</text>

  <!-- Declarative -->
  <rect x="370" y="20" width="310" height="160" rx="8" fill="#e3f2fd" stroke="#42a5f5" stroke-width="1.5"/>
  <text x="435" y="45" font-size="13" font-weight="700" fill="#1565c0">Declarative Convergence</text>
  <text x="390" y="70" font-size="10" fill="#555">Desiredstate reconciliation loop</text>
  <text x="390" y="88" font-size="10" fill="#555">"Keep all doors locked"</text>
  <text x="390" y="110" font-size="10" fill="#1565c0">Continuous — runs forever</text>
  <text x="390" y="128" font-size="10" fill="#1565c0">Escalation via FaultPolicy</text>
  <text x="390" y="146" font-size="10" fill="#1565c0">Verification via reconciliation cycle</text>
  <text x="390" y="164" font-size="10" fill="#1565c0">No conditional routing (static graph)</text>
</svg>

If you need "lock all doors, wait, then arm alarm" as a one-shot
operation — that's a scenario workflow. If you need "keep all doors
locked at all times and correct drift automatically" — that's desired
state. They're complementary, not competing. Coupling them adds latency
(the reconciliation loop runs on 30-second resync intervals — too slow
for a workflow step) and complexity (the scenario module would depend on
desiredstate-api, pulling the entire graph/node/fault infrastructure
for what is often a simple command dispatch).

The decision: keep them independent. Both use `DeviceProvider` but don't
call each other.

## What got built

The design landed on a thin adapter. The platform has all the concurrency
machinery. The IoT repo needs two things: a way to dispatch device
commands from YAML steps, and a way to read device state in YAML
conditions.

### The plugins

`IoTCommandPlugin` is a `@Plugin`-annotated record — the platform's
annotation processor generates the `Action` wiring at compile time.
The `@Execute` method receives a `DeviceCommandDispatcher` CDI bean
(because `ServiceRegistry.lookup()` takes a raw `Class<?>` — you can't
inject parameterised types like `Instance<DeviceProvider>` through it).

```yaml
- step: lock-front
  iot.command:
    device: lock-front
    action: lock
```

`IoTStatePlugin` reads device state from `DeviceRegistry`:

```yaml
- step: check
  iot.state:
    device: sensor-smoke-1
```

Result accessible as `${result.check.capabilities.smokeDetected}`.

### The variable source

`IoTDeviceVariableSource` implements a new platform SPI —
`DomainVariableSource`. Domains register a prefix and a resolve function.
The IoT implementation registers `"device"` and resolves paths against
`DeviceRegistry`:

```yaml
- if: ${device.lock-front.capabilities.isLocked}
  then:
  - iot.command: { device: alarm-1, action: arm }
```

Live device state in any YAML condition. The `DomainVariableSource`
interface went into `yaml-plugin-api` (Tier 1, no CDI dependency) with
a `DomainVariableResolverEnricher` CDI bean in `yaml-step-runtime` that
discovers all sources and enriches the `VariableResolver`.

### The scenarios

Five orchestration scenarios exercise every concurrency primitive the
platform provides. Each one represents a real IoT use case:

<svg viewBox="0 0 720 290" xmlns="http://www.w3.org/2000/svg" style="max-width:720px;font-family:system-ui,sans-serif">
  <!-- Cards -->
  <rect x="10" y="10" width="210" height="120" rx="6" fill="#ffebee" stroke="#ef5350"/>
  <text x="25" y="35" font-size="11" font-weight="700" fill="#c62828">arm-security</text>
  <text x="25" y="55" font-size="9" fill="#555">Lock 3 doors in parallel</text>
  <text x="25" y="70" font-size="9" fill="#555">Quorum: 2 of 3 must succeed</text>
  <text x="25" y="85" font-size="9" fill="#555">Then arm alarm</text>
  <text x="25" y="100" font-size="9" fill="#555">Catch: unlock on failure</text>
  <rect x="25" y="108" width="55" height="14" rx="3" fill="#ffcdd2"/>
  <text x="30" y="118" font-size="8" fill="#c62828">parallel</text>
  <rect x="85" y="108" width="50" height="14" rx="3" fill="#ffcdd2"/>
  <text x="90" y="118" font-size="8" fill="#c62828">quorum</text>
  <rect x="140" y="108" width="55" height="14" rx="3" fill="#ffcdd2"/>
  <text x="145" y="118" font-size="8" fill="#c62828">try/catch</text>

  <rect x="250" y="10" width="210" height="120" rx="6" fill="#fff3e0" stroke="#ffa726"/>
  <text x="265" y="35" font-size="11" font-weight="700" fill="#e65100">emergency-response</text>
  <text x="265" y="55" font-size="9" fill="#555">Read smoke sensor state</text>
  <text x="265" y="70" font-size="9" fill="#555">If smoke: unlock all + alarm</text>
  <text x="265" y="85" font-size="9" fill="#555">Else if motion: lights on</text>
  <rect x="265" y="108" width="40" height="14" rx="3" fill="#ffe0b2"/>
  <text x="270" y="118" font-size="8" fill="#e65100">if/else</text>
  <rect x="310" y="108" width="55" height="14" rx="3" fill="#ffe0b2"/>
  <text x="315" y="118" font-size="8" fill="#e65100">parallel</text>

  <rect x="490" y="10" width="210" height="120" rx="6" fill="#e8f5e9" stroke="#66bb6a"/>
  <text x="505" y="35" font-size="11" font-weight="700" fill="#2e7d32">nighttime-mode</text>
  <text x="505" y="55" font-size="9" fill="#555">Read occupancy mode</text>
  <text x="505" y="70" font-size="9" fill="#555">Match: home / away / default</text>
  <text x="505" y="85" font-size="9" fill="#555">Adjust lights + thermostat</text>
  <rect x="505" y="108" width="45" height="14" rx="3" fill="#c8e6c9"/>
  <text x="510" y="118" font-size="8" fill="#2e7d32">match</text>
  <rect x="555" y="108" width="55" height="14" rx="3" fill="#c8e6c9"/>
  <text x="560" y="118" font-size="8" fill="#2e7d32">parallel</text>

  <rect x="10" y="150" width="210" height="120" rx="6" fill="#e3f2fd" stroke="#42a5f5"/>
  <text x="25" y="175" font-size="11" font-weight="700" fill="#1565c0">hvac-cascade</text>
  <text x="25" y="195" font-size="9" fill="#555">Select: channel vs signal</text>
  <text x="25" y="210" font-size="9" fill="#555">Warning: adjust thermostat</text>
  <text x="25" y="225" font-size="9" fill="#555">Critical: fan + thermostat</text>
  <text x="25" y="240" font-size="9" fill="#555">Emergency: full shutdown</text>
  <rect x="25" y="248" width="45" height="14" rx="3" fill="#bbdefb"/>
  <text x="30" y="258" font-size="8" fill="#1565c0">select</text>
  <rect x="75" y="248" width="55" height="14" rx="3" fill="#bbdefb"/>
  <text x="80" y="258" font-size="8" fill="#1565c0">channel</text>
  <rect x="135" y="248" width="45" height="14" rx="3" fill="#bbdefb"/>
  <text x="140" y="258" font-size="8" fill="#1565c0">match</text>

  <rect x="250" y="150" width="210" height="120" rx="6" fill="#f3e5f5" stroke="#ab47bc"/>
  <text x="265" y="175" font-size="11" font-weight="700" fill="#6a1b9a">occupancy-driven</text>
  <text x="265" y="195" font-size="9" fill="#555">State machine: home→away→vacation</text>
  <text x="265" y="210" font-size="9" fill="#555">Depart: lights off, alarm on</text>
  <text x="265" y="225" font-size="9" fill="#555">Arrive: alarm off, lights on</text>
  <rect x="265" y="248" width="80" height="14" rx="3" fill="#e1bee7"/>
  <text x="270" y="258" font-size="8" fill="#6a1b9a">state machine</text>
  <rect x="350" y="248" width="65" height="14" rx="3" fill="#e1bee7"/>
  <text x="355" y="258" font-size="8" fill="#6a1b9a">transition</text>
</svg>

`arm-security` is the most interesting. Parallel dispatch to three
locks, a quorum gate requiring 2 of 3 to succeed within 30 seconds,
then arm the alarm — wrapped in try/catch where the catch block checks
`${result.lock-front.result == 'SENT'}` to decide which locks to
compensate (unlock). If the alarm arm fails after 2 locks succeeded,
the catch unlocks only the locks that reported SENT.

## What this means for platform #486

The upstream platform issue asks for a generic command-dispatch SPI —
domains plug in actuator interfaces, the step runtime handles
orchestration. IoT is the first consumer. The pattern is clear: a
`@Plugin` record that takes domain-specific parameters and delegates to
a domain-specific dispatcher CDI bean. When a second domain appears
(services with HTTP/gRPC commands, workflow with human task dispatch),
the platform can extract the generic pattern from what IoT built. For
now, the IoT plugin is thin enough that extraction is mechanical.

The one platform change worth making immediately was
`DomainVariableSource` — every domain that adds scenario steps will need
its state readable in conditions. That SPI went into `yaml-plugin-api`
as a Tier 1 interface. IoT was the first consumer; it won't be the last.

## The lesson

The concurrency engine was already there. The issue that looked like
"build distributed command orchestration" turned out to be "write two
plugin records and five YAML files." The hard part was finding the
engine in the right repo — and the first-principles analysis that kept
the design from coupling two systems (scenarios and desiredstate) that
are better left independent.

Four source files, four test files, 22 tests, five YAML scenarios.
The platform did the heavy lifting years ago.

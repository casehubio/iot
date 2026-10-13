---
title: "The module that was in the wrong tier"
date: 2026-10-02
author: mdp
entry_type: note
subtype: diary
projects: [casehub-iot]
tags: [desiredstate, tier-architecture, module-placement, spi, reconciliation]
issues: [118, 121]
---

# The module that was in the wrong tier

IoT desired state lived in casehub-ops — the Integration tier. It compiled
against ops-api for approval infrastructure, depended on the ops deployment
model, and sat alongside modules that orchestrate clinical trials, AML
compliance, and enterprise case management. A module that talks to light
bulbs and thermostats, sitting next to one that evaluates anti-money
laundering risk.

The tier architecture exists for a reason. Foundation modules provide
domain-agnostic abstractions. Integration-tier modules wire those
abstractions into specific deployment contexts. IoT device operations —
compiling YAML goals into a desired state graph, comparing actual device
state to desired state, dispatching commands to converge — are Foundation
concerns. They don't need ops approval workflows. They don't need
WorkItem generation. They need `DeviceProvider.dispatch()` and
`DeviceRegistry.findById()`.

<svg viewBox="0 0 700 320" xmlns="http://www.w3.org/2000/svg" style="max-width:700px;font-family:system-ui,sans-serif">
  <defs>
    <marker id="arr" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6" fill="none" stroke="#666" stroke-width="1.2"/>
    </marker>
  </defs>
  <!-- Tiers -->
  <rect x="20" y="20" width="660" height="80" rx="8" fill="#f0f4ff" stroke="#9bb3e0" stroke-width="1.5"/>
  <text x="40" y="50" font-size="13" font-weight="600" fill="#4a6fa5">Integration Tier (casehub-ops)</text>
  <rect x="50" y="60" width="120" height="28" rx="4" fill="#fff" stroke="#9bb3e0"/>
  <text x="65" y="79" font-size="11" fill="#333">ops/aml</text>
  <rect x="185" y="60" width="120" height="28" rx="4" fill="#fff" stroke="#9bb3e0"/>
  <text x="200" y="79" font-size="11" fill="#333">ops/clinical</text>
  <rect x="320" y="60" width="120" height="28" rx="4" fill="#ffe0e0" stroke="#d44" stroke-width="2" stroke-dasharray="5,3"/>
  <text x="340" y="79" font-size="11" fill="#c33" font-weight="600">ops/iot ✗</text>
  <rect x="455" y="60" width="120" height="28" rx="4" fill="#fff" stroke="#9bb3e0"/>
  <text x="465" y="79" font-size="11" fill="#333">ops/deployment</text>

  <rect x="20" y="170" width="660" height="130" rx="8" fill="#f0fff4" stroke="#7bc47f" stroke-width="1.5"/>
  <text x="40" y="200" font-size="13" font-weight="600" fill="#2d7a32">Foundation Tier (casehub-iot)</text>
  <rect x="50" y="210" width="90" height="28" rx="4" fill="#fff" stroke="#7bc47f"/>
  <text x="65" y="229" font-size="11" fill="#333">api</text>
  <rect x="155" y="210" width="90" height="28" rx="4" fill="#fff" stroke="#7bc47f"/>
  <text x="160" y="229" font-size="11" fill="#333">homeassist.</text>
  <rect x="260" y="210" width="90" height="28" rx="4" fill="#fff" stroke="#7bc47f"/>
  <text x="272" y="229" font-size="11" fill="#333">openhab</text>
  <rect x="365" y="210" width="90" height="28" rx="4" fill="#fff" stroke="#7bc47f"/>
  <text x="380" y="229" font-size="11" fill="#333">bridge</text>
  <rect x="470" y="210" width="90" height="28" rx="4" fill="#fff" stroke="#7bc47f"/>
  <text x="490" y="229" font-size="11" fill="#333">mcp</text>

  <rect x="50" y="255" width="130" height="28" rx="4" fill="#d4f5d4" stroke="#2d7a32" stroke-width="2"/>
  <text x="58" y="274" font-size="11" fill="#1a5c1f" font-weight="600">desiredstate ✓</text>

  <!-- Arrow -->
  <path d="M380,88 C380,140 110,140 110,252" fill="none" stroke="#d44" stroke-width="2" stroke-dasharray="6,4" marker-end="url(#arr)"/>
  <text x="260" y="145" font-size="11" fill="#d44" font-weight="600">moved</text>
</svg>

## The approval simplification

The ops implementation had around 100 lines of approval re-entry logic —
`handleProvisionReEntry`, `handleDeprovisionReEntry`, wrapping
`ApprovalEvaluator` and `PlanStore`. This was the ops way of gating
physical device operations behind human approval. Reasonable for an
enterprise deployment where changing a production device configuration
should go through a change management workflow.

But the desiredstate runtime already has this. `HumanGating` on
`DesiredNode` controls whether `provision()` can be called. `PendingApproval`
is a return type from the provisioner itself. The entire approval flow
exists natively — the ops layer was wrapping it with a second, heavier
mechanism that added ops-api as a compile dependency for infrastructure
the module didn't need.

Removing the approval wrapper simplified `IoTNodeProvisioner` from a class
with two re-entry handlers and an evaluator to a straightforward
provision-or-fail implementation. The provisioner receives a `DesiredNode`,
looks up the device in the registry, resolves the provider, normalises
capabilities, diffs desired against actual, maps the diff to device
commands, and dispatches. If any command fails, it returns `Failed`. If
all succeed, `Success`. No re-entry. No plan store. No approval evaluator.

## What the module actually does

The desiredstate module bridges the generic `casehub-desiredstate` runtime
to IoT device operations through five SPI implementations:

<svg viewBox="0 0 700 380" xmlns="http://www.w3.org/2000/svg" style="max-width:700px;font-family:system-ui,sans-serif">
  <defs>
    <marker id="arr2" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
      <path d="M0,0 L8,3 L0,6" fill="none" stroke="#555" stroke-width="1.2"/>
    </marker>
  </defs>
  <!-- YAML -->
  <rect x="20" y="20" width="140" height="40" rx="6" fill="#fff3e0" stroke="#e6a23c"/>
  <text x="40" y="45" font-size="12" fill="#8a6a1e" font-weight="600">YAML goals</text>

  <!-- GoalCompiler -->
  <rect x="200" y="15" width="180" height="50" rx="6" fill="#e8f5e9" stroke="#66bb6a"/>
  <text x="220" y="36" font-size="11" fill="#2e7d32" font-weight="600">IoTGoalCompiler</text>
  <text x="220" y="52" font-size="10" fill="#555">physical → 2 nodes + dep</text>
  <line x1="160" y1="40" x2="200" y2="40" stroke="#555" marker-end="url(#arr2)"/>

  <!-- Graph -->
  <rect x="430" y="15" width="170" height="50" rx="6" fill="#e3f2fd" stroke="#42a5f5"/>
  <text x="445" y="36" font-size="11" fill="#1565c0" font-weight="600">DesiredStateGraph</text>
  <text x="445" y="52" font-size="10" fill="#555">nodes + dependency edges</text>
  <line x1="380" y1="40" x2="430" y2="40" stroke="#555" marker-end="url(#arr2)"/>

  <!-- ActualStateAdapter -->
  <rect x="200" y="90" width="180" height="50" rx="6" fill="#e8f5e9" stroke="#66bb6a"/>
  <text x="210" y="111" font-size="11" fill="#2e7d32" font-weight="600">IoTActualStateAdapter</text>
  <text x="210" y="127" font-size="10" fill="#555">DeviceRegistry → PRESENT/DRIFTED</text>

  <!-- NodeProvisioner -->
  <rect x="200" y="165" width="180" height="50" rx="6" fill="#e8f5e9" stroke="#66bb6a"/>
  <text x="215" y="186" font-size="11" fill="#2e7d32" font-weight="600">IoTNodeProvisioner</text>
  <text x="215" y="202" font-size="10" fill="#555">diff → commands → dispatch</text>

  <!-- FaultPolicy -->
  <rect x="200" y="240" width="180" height="50" rx="6" fill="#e8f5e9" stroke="#66bb6a"/>
  <text x="230" y="261" font-size="11" fill="#2e7d32" font-weight="600">IoTFaultPolicy</text>
  <text x="230" y="277" font-size="10" fill="#555">3 failures → review node</text>

  <!-- EventSource -->
  <rect x="200" y="315" width="180" height="50" rx="6" fill="#e8f5e9" stroke="#66bb6a"/>
  <text x="225" y="336" font-size="11" fill="#2e7d32" font-weight="600">IoTEventSource</text>
  <text x="225" y="352" font-size="10" fill="#555">CDI events → StateEvent stream</text>

  <!-- DeviceProvider -->
  <rect x="480" y="165" width="160" height="50" rx="6" fill="#fce4ec" stroke="#ef5350"/>
  <text x="490" y="186" font-size="11" fill="#c62828" font-weight="600">DeviceProvider</text>
  <text x="490" y="202" font-size="10" fill="#555">dispatch(cmd) → Result</text>
  <line x1="380" y1="190" x2="480" y2="190" stroke="#555" marker-end="url(#arr2)"/>

  <!-- DeviceRegistry -->
  <rect x="480" y="90" width="160" height="50" rx="6" fill="#fce4ec" stroke="#ef5350"/>
  <text x="498" y="111" font-size="11" fill="#c62828" font-weight="600">DeviceRegistry</text>
  <text x="498" y="127" font-size="10" fill="#555">findById → DeviceEntity</text>
  <line x1="380" y1="115" x2="480" y2="115" stroke="#555" marker-end="url(#arr2)"/>

  <!-- Reconciliation loop label -->
  <rect x="430" y="310" width="220" height="40" rx="6" fill="#f3e5f5" stroke="#ab47bc"/>
  <text x="445" y="335" font-size="11" fill="#7b1fa2" font-weight="600">Reconciliation Loop (runtime)</text>
  <path d="M540,310 L540,65 L600,65" fill="none" stroke="#ab47bc" stroke-dasharray="4,3"/>
  <path d="M430,330 L290,330 L290,365" fill="none" stroke="#ab47bc" stroke-dasharray="4,3"/>
</svg>

`IoTGoalCompiler` takes a YAML goal set and produces a `DesiredStateGraph`.
Each device goal with `physical: true` produces two nodes — a
`PhysicalDeviceSpec` with `HumanGating.ALL` (requires human approval
before creating a physical device) and a `DeviceConfigSpec` with
`HumanGating.NONE` (configuration changes are automatic), linked by a
dependency edge so config waits for the physical device to be approved.

`IoTActualStateAdapter` reads current device state from `DeviceRegistry`
and classifies each node: ABSENT if the device isn't in the registry,
PRESENT if capabilities match desired state, DRIFTED if any differ.
`CapabilityNormalizer` handles the comparison — temperatures become
`{value, unit}` maps, enums become name strings, numbers become stripped
`BigDecimal`. Without normalisation, `22` versus `22.0` versus
`BigDecimal("22")` produces false drift detections.

`IoTNodeProvisioner` is the execution point. When the reconciliation loop
detects a drifted node, it calls `provision()` with the desired state.
The provisioner resolves the device, resolves the provider, normalises
both sides, diffs, and dispatches commands for each divergent capability
via `CapabilityCommandMapper`. The mapper handles the standard vocabulary:
`isOn` → `turn_on`/`turn_off`, `targetTemperature` → `set_temperature`,
`isLocked` → `lock`/`unlock`, with a `set_<key>` fallback for unknown
capabilities.

`IoTFaultPolicy` uses the desiredstate runtime's `ThresholdFaultPolicy`:
after 3 consecutive `PROVISION_FAILED` faults on a device-config node, it
adds an `IoTReviewSpec` review node to the graph — human escalation.
The review node is a marker; the desiredstate runtime tracks its
completion via external signals.

`IoTEventSource` bridges CDI async events to the desiredstate runtime's
reactive event stream. `StateChangeEvent` (device discovered, went
offline, capabilities changed) maps to PRESENT/DRIFTED/UNKNOWN. Provider
disconnection emits UNKNOWN for all tracked devices.

## The cross-repo cleanup

The ops side was pure deletion — remove the `iot/` module directory,
remove the four spec types from `ops-api/iot/`, remove the
`<module>iot</module>` from the parent pom. No other ops module depended
on ops/iot, so the deletion was clean. One pom.xml conflict from a
whitespace change during rebase — resolved in thirty seconds.

Eleven test classes, eleven source files, all of it moved, adapted, and
verified. The integration test runs the full reconciliation loop against
`MockDeviceProvider` — compiles goals, reads actual state, detects drift,
dispatches commands, verifies convergence. The same test that validated
the code in ops now validates it in iot, without the approval
infrastructure it never needed.

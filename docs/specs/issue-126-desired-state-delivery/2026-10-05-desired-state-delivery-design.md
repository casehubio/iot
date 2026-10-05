# Desired-State Scenario Delivery Mode

**Issue:** casehubio/iot#126
**Module:** casehub-iot-scenario
**Parent:** #121 (IoT desired state epic), Phase 4 item 12

## Overview

A `DeliveryHandler` implementation registered as `"desired-state"` that bridges the casehub-pages scenario engine to IoT desired-state reconciliation. Scenario authors declare desired device state in a step's `data:` block; the handler compiles goals, reads actual state, plans transitions, and provisions devices — returning a `StepOutcome` to the scenario engine.

## Input Modes

### Inline config

Device ID → desired capabilities map. The handler resolves `deviceClass`, `label`, and other metadata from `DeviceRegistry` at runtime. `physical` is always `false` — scenario steps configure existing devices, they don't provision hardware.

```yaml
- name: night-mode
  delivery: desired-state
  data:
    light-1: { on: false }
    thermostat-hallway: { mode: cool, target: 22 }
```

### Preset reference

Named preset resolved via `IoTPresetResolver`. The preset's own `physical` and `dependsOn` declarations are used as-is.

```yaml
- name: night-mode
  delivery: desired-state
  data:
    preset: night-mode
```

Detection: `data.containsKey("preset")` → preset mode, otherwise inline config mode.

## Handler: DesiredStateDeliveryHandler

`@ApplicationScoped` CDI bean implementing `io.casehub.pages.scenario.DeliveryHandler`.

### Injected dependencies

| Dependency | Source | Purpose |
|-----------|--------|---------|
| `DeviceRegistry` | casehub-iot-api | Device lookup for inline config resolution |
| `IoTPresetResolver` | casehub-iot-desiredstate | Preset name → IoTGoals |
| `IoTGoalCompiler` | casehub-iot-desiredstate | IoTGoals → DesiredStateGraph |
| `IoTActualStateAdapter` | casehub-iot-desiredstate | Read current device state |
| `IoTNodeProvisioner` | casehub-iot-desiredstate | Execute device commands |
| `@ConfigProperty("casehub.iot.tenancy-id")` | platform config | Tenant context for reconciliation |

`TransitionPlanner` and `DefaultDesiredStateGraphFactory` are instantiated directly (they have no-arg constructors, no CDI wiring needed) — same pattern as `DefaultIoTPresetApi.applyPreset()`.

### Execution flow

```
name() → "desired-state"

execute(stepName, data, ctx):
  1. Resolve IoTGoals
     ├─ preset mode:  presetResolver.resolve((String) data.get("preset"))
     └─ inline mode:
          for each (deviceId, configMap) in data:
            entity = registry.findById(deviceId)
            if not found → collect to unknownIds
            else → build IoTDeviceGoal(deviceId, entity.deviceClass(),
                     entity.label(), physical=false, configMap, dependsOn=[])
          if unknownIds not empty → return StepOutcome.fail(stepName,
              "Unknown device IDs: " + unknownIds)
          goals = new IoTGoals(tenancyId, deviceGoals)

  2. Compile
     compilationResult = compiler.compile(goals, graphFactory)
     graph = ((CompilationResult.SingleGraph) compilationResult).graph()

  3. Read actual state
     actual = actualStateAdapter.readActual(graph, tenancyId)

  4. Plan transitions
     plan = planner.plan(graph, actual)

  5. Execute provisions
     provisioned = 0, failed = 0, failedIds = []
     for each step in plan.flatAdditions():
       if step.action() == PROVISION:
         result = provisioner.provision(step.node(), ProvisionContext(tenancyId, graph))
         if result instanceof ProvisionResult.Success → provisioned++
         else → failed++, failedIds.add(nodeId + ": " + reason)

  6. Return outcome
     if failed == 0 →
       StepOutcome.ok(stepName, { "provisioned": provisioned, "converged": true })
     else →
       StepOutcome.fail(stepName,
         failed + " of " + (provisioned + failed) + " devices failed: " + failedIds)
```

### Error handling

| Error category | Behaviour |
|---------------|-----------|
| Unknown device ID (inline mode) | Fail step immediately, list all unknown IDs |
| Preset not found | Fail step with preset resolution error |
| Compilation failure (duplicate IDs, cycles) | Fail step with compiler error message |
| Device provision failure | Continue attempting remaining devices, then fail step with per-device failure details |
| Provider not available | `IoTNodeProvisioner` returns `ProvisionResult.Failed` — counted as device failure |

### StepOutcome name collision note

Two unrelated `StepOutcome` types exist in the codebase: `io.casehub.pages.scenario.StepOutcome` (the handler's return type) and `io.casehub.desiredstate.api.StepOutcome` (per-node reconciliation outcome). The handler returns the pages type. Implementations should use explicit imports or fully-qualified names to avoid confusion.

## Module Changes

### casehub-iot-scenario/pom.xml — new dependencies

```xml
<!-- Desired-state delivery handler -->
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-pages-scenario</artifactId>
</dependency>
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-iot-desiredstate</artifactId>
</dependency>
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-desiredstate</artifactId>
</dependency>
```

`casehub-desiredstate-api` is transitive via `casehub-iot-desiredstate`.

### New files

| File | Purpose |
|------|---------|
| `DesiredStateDeliveryHandler.java` | Handler implementation |
| `DesiredStateDeliveryHandlerTest.java` | Unit tests |

## Testing Strategy

### Unit tests (DesiredStateDeliveryHandlerTest)

1. **Inline config — happy path:** Two devices in data block, both in registry, both provision successfully → `StepOutcome.ok` with `provisioned=2`
2. **Preset reference — happy path:** Preset name in data block, resolver returns goals, all provision → `StepOutcome.ok`
3. **Unknown device — fail-fast:** One known, one unknown device in inline config → `StepOutcome.fail` listing unknown ID, no provisions attempted
4. **Partial failure:** Three devices, two provision successfully, one fails → `StepOutcome.fail` with "1 of 3 devices failed" and the failed ID
5. **Already converged:** Device state already matches desired → `plan.flatAdditions()` is empty or returns `AlreadyConverged` → `StepOutcome.ok` with `provisioned=0`
6. **Preset not found:** Invalid preset name → `StepOutcome.fail` with resolution error
7. **Compilation error:** Duplicate device IDs in data → `StepOutcome.fail` with compiler error

Tests use mocked `DeviceRegistry`, `IoTPresetResolver`, `IoTGoalCompiler`, `IoTActualStateAdapter`, and `IoTNodeProvisioner`. The existing `casehub-iot-testing` module provides `MockDeviceProvider` and fixture devices.

## Design Rationale

### One-shot reconciliation, not ReconciliationLoop

The handler uses the same one-shot pattern as `DefaultIoTPresetApi.applyPreset()`: compile → readActual → plan → provision. The ReconciliationLoop is a per-tenant continuous monitoring system (drift detection, fault policies, periodic resync) — irrelevant for point-in-time convergence. A scenario step says "make it look like this now" and reports success or failure. It doesn't monitor for subsequent drift.

### No shared service extraction

The handler and `applyPreset()` share ~8 lines of pipeline code. Two callers with different inputs, outputs, and error handling don't justify an abstraction. If a third caller appears, extract an `IoTConvergenceService` into the desiredstate module at that point.

### Inline config uses physical=false

Scenario steps configure existing devices — they set capabilities, not provision hardware. `physical=false` produces only `DeviceConfigSpec` nodes. Preset references use whatever the preset declares; presets designed for automated convergence should set `physical: false` per device.

## References

- `DefaultIoTPresetApi.applyPreset()` — existing one-shot reconciliation pattern (`webapp/src/main/java/io/casehub/iot/webapp/app/service/DefaultIoTPresetApi.java`)
- `DeliveryHandler` SPI — `pages/backend/scenario/src/main/java/io/casehub/pages/scenario/DeliveryHandler.java`
- `IoTGoalCompiler` — `desiredstate/src/main/java/io/casehub/iot/desiredstate/IoTGoalCompiler.java`
- `IoTNodeProvisioner` — `desiredstate/src/main/java/io/casehub/iot/desiredstate/IoTNodeProvisioner.java`
- `IoTPresetResolver` — `desiredstate/src/main/java/io/casehub/iot/desiredstate/IoTPresetResolver.java`
- `DeviceCommandDispatcher` — `scenario/src/main/java/io/casehub/iot/scenario/DeviceCommandDispatcher.java` (existing scenario pattern)
- Issue #121 — IoT desired state epic
- CLAUDE.md — `casehub.iot.tenancy-id` convention, module structure

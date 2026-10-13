# casehub-iot-desiredstate — Module Design Spec

**Date:** 2026-10-02
**Issue:** casehubio/iot#118 (part of epic #121)
**Branch:** issue-121-iot-desired-state-epic

---

## Overview

Move the existing IoT desired-state implementation from `casehub-ops/iot` (Integration tier) to a new `desiredstate/` module in `casehub-iot` (Foundation tier). The implementation bridges the generic `casehub-desiredstate` runtime to IoT device operations via the existing `DeviceProvider` and `DeviceRegistry` SPIs.

The move corrects a tier mismatch: IoT desired state is Foundation-tier domain logic (device operations), not an Integration-tier deployment concern. The refactoring removes the `casehub-ops-api` approval dependency in favour of the desiredstate runtime's native `HumanGating` + `PendingApproval` mechanism.

---

## Module Structure

**Artifact:** `casehub-iot-desiredstate`
**Package:** `io.casehub.iot.desiredstate`
**Tier:** 2 (CDI, no JPA) — Jandex-indexed library module

```
casehub-iot/
  api/                  ← existing (Tier 1, public API)
  homeassistant/        ← existing
  openhab/              ← existing
  testing/              ← existing
  bridge/               ← existing
  desiredstate/         ← NEW
    src/main/java/io/casehub/iot/desiredstate/
      IoTNodeSpec.java              (sealed interface extends NodeSpec)
      DeviceConfigSpec.java         (record implements IoTNodeSpec)
      PhysicalDeviceSpec.java       (record implements IoTNodeSpec)
      IoTReviewSpec.java            (record implements NodeSpec)
      IoTNodeProvisioner.java       (@ApplicationScoped implements NodeProvisioner)
      IoTActualStateAdapter.java    (@ApplicationScoped implements ActualStateAdapter)
      IoTFaultPolicy.java           (@ApplicationScoped implements FaultPolicy)
      IoTEventSource.java           (@ApplicationScoped implements EventSource)
      IoTGoalCompiler.java          (@ApplicationScoped implements GoalCompiler<IoTGoals>)
      IoTGoals.java                 (record — goal input type)
      IoTDeviceGoal.java            (record — single device goal)
      IoTGoalLoader.java            (@ApplicationScoped — YAML loader)
      CapabilityCommandMapper.java  (package-private utility)
      CapabilityNormalizer.java     (package-private utility)
    src/test/java/io/casehub/iot/desiredstate/
      IoTNodeProvisionerTest.java
      IoTGoalCompilerTest.java
      IoTActualStateAdapterTest.java
      IoTFaultPolicyTest.java
      IoTEventSourceTest.java
      IoTGoalLoaderTest.java
      IoTNodeSpecTest.java
      CapabilityCommandMapperTest.java
      CapabilityNormalizerTest.java
      IoTReconciliationIntegrationTest.java
  ...
```

---

## Dependencies

### Compile

| Dependency | Purpose |
|-----------|---------|
| `casehub-desiredstate-api` | `NodeProvisioner`, `GoalCompiler`, `ActualStateAdapter`, `FaultPolicy`, `EventSource` SPIs |
| `casehub-iot-api` | `DeviceRegistry`, `DeviceProvider`, `DeviceCommand`, `DeviceEntity`, `DeviceClass` |
| `quarkus-arc` | CDI injection (`@ApplicationScoped`, `@Inject`, `@Any Instance<>`) |
| `jackson-dataformat-yaml` (provided) | YAML parsing in `IoTGoalLoader` |
| `jackson-datatype-jsr310` (provided) | Temporal type support for YAML |

### Test

| Dependency | Purpose |
|-----------|---------|
| `casehub-desiredstate` | Runtime reconciliation engine for integration test |
| `casehub-desiredstate-testing` | Test fixtures and utilities |
| `casehub-iot-testing` | `MockDeviceProvider`, fixture devices |
| `quarkus-junit` | Test framework |
| `assertj-core` | Assertions |

### Explicitly NOT depended on

| Excluded | Reason |
|----------|--------|
| `casehub-ops-api` | Approval infrastructure replaced by native `HumanGating` |
| `casehub-work-api` | No WorkItem generation — physical device nodes use `HumanGating.ALL` instead |
| `casehub-platform-api` | No direct usage; transitive via `casehub-iot-api` is sufficient |

---

## Component Design

### Spec Types

`IoTNodeSpec` is a sealed interface extending `NodeSpec` with two common accessors:
- `deviceId()` — the device identifier in `DeviceRegistry`
- `deviceClass()` — the Matter-aligned device class

Two implementations:
- `PhysicalDeviceSpec(deviceId, deviceClass, label)` — declares a physical device should exist. `nodeType() = "physical-device"`.
- `DeviceConfigSpec(deviceId, deviceClass, desiredCapabilities)` — declares desired capability values for an existing device. `nodeType() = "device-config"`. `desiredCapabilities` is `Map<String, Object>` — keys are capability names (e.g. `isOn`, `targetTemperature`), values are the desired state.

`IoTReviewSpec(faultedNode, reason)` extends `NodeSpec` directly (not `IoTNodeSpec`) — it represents a human review task created by the fault policy. `nodeType() = "iot-review"`.

### IoTNodeProvisioner

`@ApplicationScoped implements NodeProvisioner`. Handles types: `physical-device`, `device-config`, `iot-review`.

**Simplified from ops version:** The ops implementation had ~100 lines of approval re-entry logic (`handleProvisionReEntry`, `handleDeprovisionReEntry`) wrapping `ApprovalEvaluator` and `PlanStore`. This is removed entirely. The desiredstate runtime handles approval gating externally — `HumanGating.ALL` on physical device nodes prevents `provision()` from being called until a human approves. The provisioner only receives calls for nodes that have passed their gating.

**Provision flow for `DeviceConfigSpec`:**
1. `DeviceRegistry.findById(spec.deviceId())` — fail if device not found
2. Resolve `DeviceProvider` by `device.providerId()`
3. `CapabilityNormalizer.normalize()` on both actual and desired capabilities
4. For each differing capability: `CapabilityCommandMapper.toCommand()` → `DeviceProvider.dispatch()`
5. Return `Success` if all commands sent, `Failed` if any command fails

**Provision for `IoTReviewSpec`:** Return `Success` (review node is a marker — completion is tracked by the desiredstate runtime via external signals).

**Deprovision:** Return `Success` for all types (device removal is physical, not software-controlled).

**Resync interval:** 30 seconds (IoT state changes frequently).

### IoTActualStateAdapter

`@ApplicationScoped implements ActualStateAdapter`. Handles types: `physical-device`, `device-config`.

Reads actual device state from `DeviceRegistry`:
- `PhysicalDeviceSpec` → ABSENT if device not in registry; PRESENT if `deviceClass` matches; DRIFTED if mismatch
- `DeviceConfigSpec` → ABSENT if device not in registry; normalise capabilities and compare; PRESENT if all desired capabilities match; DRIFTED if any differ

### IoTGoalCompiler

`@ApplicationScoped implements GoalCompiler<IoTGoals>`.

Compiles `IoTGoals` (parsed from YAML) into a `DesiredStateGraph`:
- Each `IoTDeviceGoal` with `physical=true` produces two nodes: `PhysicalDeviceSpec` (with `HumanGating.ALL`) and `DeviceConfigSpec` (with `HumanGating.NONE`), linked by a dependency (config depends on physical)
- Each `IoTDeviceGoal` with `physical=false` produces one `DeviceConfigSpec` node
- Explicit `dependsOn` entries create additional dependency edges

### IoTFaultPolicy

`@ApplicationScoped implements FaultPolicy`.

Uses `ThresholdFaultPolicy` from desiredstate-api:
- Watches `PROVISION_FAILED` faults on `device-config` nodes
- After 3 consecutive failures, creates an `IoTReviewSpec` review node (human escalation)
- Ignores `iot-review` nodes to prevent recursive escalation

### IoTEventSource

`@ApplicationScoped implements EventSource`.

Bridges IoT CDI events to the desiredstate runtime's `Multi<StateEvent>` stream:
- `@ObservesAsync StateChangeEvent` → emits `StateEvent` with:
  - New device discovered → `PRESENT`
  - Device went offline → `DRIFTED`
  - Capabilities changed → `DRIFTED` (on the `-config` node)
- `@ObservesAsync ProviderStatusEvent` → if provider disconnected, emit `UNKNOWN` for all its tracked devices

### IoTGoalLoader

`@ApplicationScoped` YAML loader. Supports:
- Single file loading (classpath or filesystem)
- Directory loading (all `.yaml`/`.yml` files, sorted)
- Merge with duplicate deviceId rejection and tenancyId consistency check

### Utility Classes

- `CapabilityCommandMapper` — maps capability key/value pairs to `DeviceCommand` instances. Handles: `isOn` → turn_on/turn_off, `targetTemperature` → set_temperature, `isLocked` → lock/unlock, `position` → set_position, `volume` → set_volume. Unknown capabilities fall back to `set_<key>`.
- `CapabilityNormalizer` — normalises capability values for comparison. Handles: `Temperature` → `{value, unit}` map, enums → name string, numeric types → stripped `BigDecimal`, nested maps → recursive normalisation.

---

## YAML Goal Format

```yaml
tenancyId: "home-001"
devices:
  - deviceId: "light-1"
    deviceClass: LIGHT
    label: "Living Room Light"
    physical: false
    config:
      isOn: false
      brightness: 128
  - deviceId: "thermostat-hallway"
    deviceClass: THERMOSTAT
    label: "Hallway Thermostat"
    physical: false
    config:
      targetTemperature:
        value: 22
        unit: CELSIUS
      mode: COOL
  - deviceId: "lock-front"
    deviceClass: LOCK
    label: "Front Door Lock"
    physical: true
    config:
      isLocked: true
    dependsOn:
      - "light-1"
```

---

## Data Flow

```
YAML goals file
    │
    ▼
IoTGoalLoader.load()
    │
    ▼
IoTGoals (tenancyId + List<IoTDeviceGoal>)
    │
    ▼
IoTGoalCompiler.compile()
    │  physical=true → PhysicalDeviceSpec (HumanGating.ALL)
    │                 + DeviceConfigSpec (HumanGating.NONE) with dependency
    │  physical=false → DeviceConfigSpec (HumanGating.NONE) only
    ▼
DesiredStateGraph
    │
    ▼
IoTActualStateAdapter.readActual()
    │  DeviceRegistry.findById() per node
    │  Compare actual vs spec → PRESENT / ABSENT / DRIFTED
    ▼
ActualState
    │
    ▼
desiredstate runtime reconciliation
    │  Calls IoTNodeProvisioner for divergent nodes
    ▼
IoTNodeProvisioner.provision()
    │  Normalize, diff, map to DeviceCommand, dispatch
    ▼
ProvisionResult

Reactive drift detection:
StateChangeEvent → IoTEventSource → Multi<StateEvent> → runtime triggers reconciliation
```

---

## Ops Cleanup

In `casehub-ops` (separate commit on ops repo):
1. Delete `ops/iot/` module directory
2. Delete `ops-api/src/main/java/io/casehub/ops/api/iot/` package (4 files: `IoTNodeSpec`, `DeviceConfigSpec`, `PhysicalDeviceSpec`, `IoTReviewSpec`)
3. Remove `<module>iot</module>` from `ops/pom.xml`
4. Remove any `casehub-ops-iot` dependency entries from other pom files (none expected)

---

## Documentation Updates

1. `CLAUDE.md` — add `desiredstate` to the Module Structure table
2. `docs/guides/consumer-guide.md` — add desiredstate usage section
3. `docs/platform/capability-ownership.md` (in parent repo) — update IoT entry to include desired state capability, remove from ops

---

## References

- `casehub-ops/iot/` — source implementation being moved (11 source files, 11 test files)
- `casehub-ops/api/src/main/java/io/casehub/ops/api/iot/` — spec types being moved
- `casehub-desiredstate/api/` — SPI interfaces: `NodeProvisioner`, `GoalCompiler`, `ActualStateAdapter`, `FaultPolicy`, `EventSource`
- `docs/platform/capability-ownership.md` — tier placement authority
- `docs/platform/overview.md` — tier architecture
- `protocols/universal/spi-adapter-module-placement.md` — adapter placement rule
- `protocols/universal/optional-module-pattern.md` — Jandex library module pattern
- `protocols/universal/module-tier-structure.md` — three-tier module rule
- casehubio/iot#118 — IoT NodeProvisioner issue
- casehubio/iot#121 — Epic: IoT desired state

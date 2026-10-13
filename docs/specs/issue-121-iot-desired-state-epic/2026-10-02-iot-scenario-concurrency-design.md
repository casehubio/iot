# IoT Scenario Concurrency Mapping — Design Spec

**Date:** 2026-10-02
**Issue:** casehubio/iot#117 (part of epic #121)
**Branch:** issue-121-iot-desired-state-epic
**Upstream:** casehubio/platform#486 (platform SPI — scoped to DomainVariableSource for now)

---

## Overview

Map the platform YAML scenario concurrency primitives — parallel fan-out, barrier (join-all), quorum (N-of-M), try/catch/finally (compensation), if/else (conditional routing), match (pattern routing), select (channel/signal race), state machine transitions — to IoT device command orchestration via `DeviceProvider.dispatch()`.

The platform `yaml-core` and `yaml-step-runtime` already provide the full concurrency engine in Java (virtual-thread-based `StructuralStepEvaluator`). This spec adds:

1. **Platform SPI:** `DomainVariableSource` interface in `yaml-plugin-api` — domains expose state to YAML conditions
2. **IoT module:** `casehub-iot-scenario` — `iot.command` and `iot.state` plugins, `IoTDeviceVariableSource`
3. **Orchestration scenarios:** five YAML scenarios exercising all concurrency primitives

---

## Architecture

```
YAML scenario file (arm-security.yaml)
    │
    ▼
StepWalker.resolve()                    ← platform yaml-step-runtime
    │  Parses YAML → ResolvedStep tree
    │  (ParallelStep, BarrierStep, QuorumStep, etc.)
    ▼
StructuralStepEvaluator.evaluate()      ← platform yaml-step-runtime
    │  Dispatches structural steps (parallel, barrier, etc.)
    │  Delegates leaf steps to StepRunner
    ▼
StepRunner → PluginStep                 ← plugin dispatch
    │  Resolves plugin by action name
    │  Injects CDI beans
    ▼
IoTCommandPlugin.run()                  ← casehub-iot-scenario (NEW)
    │  Resolves device from DeviceRegistry
    │  Resolves provider from providerId
    │  Calls DeviceProvider.dispatch()
    ▼
CommandResult (SENT / FAILED / TIMEOUT)
    │
    ▼
Result.of(...) → StepResultStore        ← available as ${result.step-name}
```

### Concurrency model

`DeviceProvider.dispatch()` is blocking (virtual-thread-safe per ADR-0005). `StructuralStepEvaluator.evaluateParallel()` spawns each child step on its own virtual thread via `Executors.newVirtualThreadPerTaskExecutor()`. This means:

- **Parallel fan-out:** each device command blocks its own virtual thread. The parallel step joins all threads via `Future.get()`.
- **Barrier (join-all):** `OrcLatch` counts down as named steps complete. Blocks the barrier step's thread until all are done.
- **Quorum (N-of-M):** `OrcLatch` with `required` count + `QuorumTracker` for unreachability detection (when too many steps fail to reach quorum).
- **Compensation:** try/catch/finally. The catch block has access to `${result.step-name}` to determine which commands succeeded and dispatch undo commands.
- **Conditional routing:** `ConditionEvaluator` + `VariableResolver` with domain variable sources. `if: ${device.lock-front.capabilities.isLocked}` reads live device state.

No new concurrency infrastructure needed. The platform provides everything.

---

## Platform SPI: DomainVariableSource

### Interface

```java
// In casehub-platform-yaml-plugin-api
package io.casehub.yaml.plugin.api;

public interface DomainVariableSource {
    String prefix();
    Object resolve(String path);
}
```

`prefix()` returns the top-level namespace (e.g. `"device"`). `resolve(path)` resolves a dot-separated path within that namespace (e.g. `"lock-front.capabilities.isLocked"`).

### Integration with VariableResolver

`VariableResolver` discovers `DomainVariableSource` instances (via CDI `Instance<>` in the Quarkus host) and registers each as an object scope under its prefix:

```java
// In CdiServiceRegistry or StructuralStepEvaluator setup
for (DomainVariableSource source : domainSources) {
    resolver = resolver.withObjectScope(source.prefix(), source::resolve);
}
```

This enables `${device.lock-front.capabilities.isLocked}` in any YAML `if`, `match`, or `when` expression.

### Path resolution

The path after the prefix is split on `.` and navigated through the resolved object. For IoT:
- `${device.lock-front}` → `DeviceEntity` for device ID "lock-front"
- `${device.lock-front.capabilities}` → capabilities map
- `${device.lock-front.capabilities.isLocked}` → specific capability value
- `${device.lock-front.deviceClass}` → device class enum name
- `${device.lock-front.status}` → provider status

---

## IoT Module: casehub-iot-scenario

### Module structure

**Artifact:** `casehub-iot-scenario`
**Package:** `io.casehub.iot.scenario`
**Tier:** 2 (CDI, no JPA) — Jandex-indexed library module

```
casehub-iot/
  scenario/                          ← NEW
    src/main/java/io/casehub/iot/scenario/
      IoTCommandPlugin.java          (@Plugin "iot.command")
      IoTStatePlugin.java            (@Plugin "iot.state")
      IoTDeviceVariableSource.java   (implements DomainVariableSource)
      DeviceCommandDispatcher.java   (@ApplicationScoped — wraps registry + provider lookup)
    src/test/java/io/casehub/iot/scenario/
      DeviceCommandDispatcherTest.java
      IoTCommandPluginTest.java
      IoTStatePluginTest.java
      IoTDeviceVariableSourceTest.java
      IoTScenarioIntegrationTest.java
    src/test/resources/scenarios/
      test-parallel-commands.yaml
      test-quorum-dispatch.yaml
      test-compensation.yaml
```

### Dependencies

#### Compile

| Dependency | Purpose |
|-----------|---------|
| `casehub-iot-api` | `DeviceRegistry`, `DeviceProvider`, `DeviceCommand`, `CommandResult` |
| `casehub-platform-yaml-plugin-api` | `@Plugin`, `@Required`, `@Optional`, `@Execute`, `Result`, `DomainVariableSource` |
| `quarkus-arc` | CDI injection |

#### Test

| Dependency | Purpose |
|-----------|---------|
| `casehub-iot-testing` | `MockDeviceProvider`, fixture devices |
| `casehub-platform-yaml-step-runtime` | `StructuralStepEvaluator`, `StepWalker` |
| `casehub-platform-yaml-step-testing` | Test utilities |
| `quarkus-junit` | Test framework |

#### Explicitly NOT depended on

| Excluded | Reason |
|----------|--------|
| `casehub-iot-desiredstate` | Independent modules — no coupling |
| `casehub-platform-yaml-step-runtime` | Plugin-api is sufficient; runtime is host's concern |

### DeviceCommandDispatcher

The `PluginScanner` resolves `@Execute` method parameters via `ServiceRegistry.lookup(Class<?>)`, which calls `Arc.container().instance(Class)`. This means parameterized types like `Instance<DeviceProvider>` cannot be injected directly — the raw `Class<?>` loses the type parameter. A dedicated CDI bean wraps registry + provider lookup:

```java
@ApplicationScoped
public class DeviceCommandDispatcher {

    private final DeviceRegistry registry;
    private final Map<String, DeviceProvider> providers;

    @Inject
    public DeviceCommandDispatcher(DeviceRegistry registry,
                                   @Any Instance<DeviceProvider> providerBeans) {
        this.registry = registry;
        this.providers = new HashMap<>();
        providerBeans.forEach(p -> providers.put(p.providerId(), p));
    }

    public CommandResult dispatch(String deviceId, String action,
                                  Map<String, Object> params, String correlationId) {
        var optDevice = registry.findById(deviceId);
        if (optDevice.isEmpty()) {
            throw new IllegalArgumentException("Device not found: " + deviceId);
        }
        var entity = optDevice.get();

        var provider = providers.get(entity.providerId());
        if (provider == null) {
            throw new IllegalArgumentException("No provider for: " + entity.providerId());
        }

        var command = new DeviceCommand(
                deviceId, action, params != null ? params : Map.of(),
                "iot-scenario", correlationId);

        return provider.dispatch(command);
    }

    public Optional<DeviceEntity> findDevice(String deviceId) {
        return registry.findById(deviceId);
    }
}
```

### IoTCommandPlugin

```java
@Plugin(value = "iot.command",
        description = "Dispatches a command to an IoT device via DeviceProvider")
public record IoTCommandPlugin(
        @Required String device,
        @Required String action,
        @Optional Map<String, Object> params,
        @Optional String correlationId) {

    @Execute
    public Result run(DeviceCommandDispatcher dispatcher) {
        String corrId = correlationId != null
                ? correlationId : UUID.randomUUID().toString();

        CommandResult result;
        try {
            result = dispatcher.dispatch(device, action, params, corrId);
        } catch (IllegalArgumentException e) {
            return Result.failed(e.getMessage());
        }

        return switch (result) {
            case SENT -> Result.of(Map.of(
                    "result", "SENT",
                    "device", device,
                    "action", action,
                    "correlationId", corrId));
            case FAILED -> Result.failed(
                    "Command " + action + " failed for device " + device);
            case TIMEOUT -> Result.failed(
                    "Command " + action + " timed out for device " + device);
        };
    }
}
```

Note: `PluginScanner.toKebabCase()` converts record component names to kebab-case. `correlationId` becomes `correlation-id` in YAML.

YAML usage:
```yaml
- iot.command:
    device: lock-front
    action: lock
    params:
      force: true
    correlation-id: my-custom-id
```

### IoTStatePlugin

```java
@Plugin(value = "iot.state",
        description = "Reads current state of an IoT device from DeviceRegistry")
public record IoTStatePlugin(
        @Required String device) {

    @Execute
    public Result run(DeviceRegistry registry) {
        var optDevice = registry.findById(device);
        if (optDevice.isEmpty()) {
            return Result.failed("Device not found: " + device);
        }
        var entity = optDevice.get();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("deviceId", entity.deviceId());
        output.put("deviceClass", entity.deviceClass().name());
        output.put("providerId", entity.providerId());
        output.put("capabilities", entity.capabilities());
        if (entity.location() != null) {
            output.put("location", entity.location());
        }

        return Result.of(output);
    }
}
```

YAML usage:
```yaml
- step: check-lock
  iot.state:
    device: lock-front
```

Result accessible as `${result.check-lock.capabilities.isLocked}`.

### IoTDeviceVariableSource

```java
@ApplicationScoped
public class IoTDeviceVariableSource implements DomainVariableSource {

    private final DeviceRegistry registry;

    @Inject
    public IoTDeviceVariableSource(DeviceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String prefix() {
        return "device";
    }

    @Override
    public Object resolve(String path) {
        int dot = path.indexOf('.');
        String deviceId = dot < 0 ? path : path.substring(0, dot);

        var optDevice = registry.findById(deviceId);
        if (optDevice.isEmpty()) return null;
        var entity = optDevice.get();

        if (dot < 0) {
            return toMap(entity);
        }

        String rest = path.substring(dot + 1);
        return switch (rest) {
            case "capabilities" -> entity.capabilities();
            case "deviceClass" -> entity.deviceClass().name();
            case "providerId" -> entity.providerId();
            case "location" -> entity.location();
            default -> {
                if (rest.startsWith("capabilities.")) {
                    String capKey = rest.substring("capabilities.".length());
                    yield entity.capabilities().get(capKey);
                }
                yield null;
            }
        };
    }

    private Map<String, Object> toMap(DeviceEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("deviceId", entity.deviceId());
        map.put("deviceClass", entity.deviceClass().name());
        map.put("providerId", entity.providerId());
        map.put("capabilities", entity.capabilities());
        if (entity.location() != null) map.put("location", entity.location());
        return map;
    }
}
```

Enables: `if: ${device.lock-front.capabilities.isLocked}` in any YAML condition.

---

## Orchestration Scenarios

### 1. arm-security.yaml

**Primitives:** parallel, quorum, barrier, try/catch (compensation)

```yaml
scenario: arm-security
meta:
  title: Arm Security System
  description: Lock all doors (quorum 2-of-3), then arm alarm. Compensate on failure.
  area: iot
  labels: [orchestration, security]

sections:
  - title: Lock & Arm
    steps:
      - try:
        - parallel:
          - step: lock-front
            iot.command: { device: lock-front, action: lock }
          - step: lock-back
            iot.command: { device: lock-back, action: lock }
          - step: lock-garage
            iot.command: { device: lock-garage, action: lock }
        - quorum:
            required: 2
            of: [lock-front, lock-back, lock-garage]
            timeout: 30s
        - step: arm-alarm
          iot.command: { device: alarm-1, action: arm }
        catch:
        - iot.command:
            device: lock-front
            action: unlock
          if: ${result.lock-front.result == 'SENT'}
        - iot.command:
            device: lock-back
            action: unlock
          if: ${result.lock-back.result == 'SENT'}
        - iot.command:
            device: lock-garage
            action: unlock
          if: ${result.lock-garage.result == 'SENT'}
```

### 2. emergency-response.yaml

**Primitives:** if/else (conditional on sensor state), parallel fan-out

```yaml
scenario: emergency-response
meta:
  title: Emergency Response
  description: Conditional device orchestration based on sensor state.
  area: iot
  labels: [orchestration, emergency]

sections:
  - title: Assess & Respond
    steps:
      - step: assess
        iot.state: { device: sensor-smoke-1 }
      - if: ${device.sensor-smoke-1.capabilities.smokeDetected}
        then:
        - parallel:
          - iot.command: { device: lock-front, action: unlock }
          - iot.command: { device: lock-back, action: unlock }
          - iot.command: { device: light-hallway-1, action: turn_on }
          - iot.command: { device: alarm-1, action: sound_alarm }
        else:
        - if: ${device.presence-front-1.capabilities.presenceDetected}
          then:
          - iot.command: { device: light-front-1, action: turn_on }
```

### 3. nighttime-mode.yaml

**Primitives:** match (pattern routing based on occupancy mode)

```yaml
scenario: nighttime-mode
meta:
  title: Nighttime Mode
  description: Adjust devices based on occupancy pattern.
  area: iot
  labels: [orchestration, comfort]

sections:
  - title: Set Nighttime Mode
    steps:
      - step: check-occupancy
        iot.state: { device: presence-front-1 }
      - match: ${device.presence-front-1.capabilities.occupancyMode}
        cases:
        - pattern: "home"
          steps:
          - parallel:
            - iot.command: { device: light-living-1, action: turn_off }
            - iot.command: { device: light-bedroom-1, action: set_brightness, params: { brightness: 30 } }
            - iot.command: { device: thermostat-1, action: set_temperature, params: { temperature: 18, unit: CELSIUS } }
        - pattern: "away"
          steps:
          - parallel:
            - iot.command: { device: light-living-1, action: turn_off }
            - iot.command: { device: light-bedroom-1, action: turn_off }
            - iot.command: { device: thermostat-1, action: set_temperature, params: { temperature: 15, unit: CELSIUS } }
          - iot.command: { device: alarm-1, action: arm }
        - default:
          - iot.command: { device: light-living-1, action: turn_off }
```

### 4. hvac-cascade.yaml

**Primitives:** select (channel-based, react to temperature events)

```yaml
scenario: hvac-cascade
meta:
  title: HVAC Cascade Response
  description: React to temperature threshold events via channel select.
  area: iot
  labels: [orchestration, hvac]

orchestration:
  channels:
    temp-alerts: { capacity: 10 }
  signals: [emergency-temp]

sections:
  - title: Monitor & Respond
    steps:
      - select:
        - subscribe:
            channel: temp-alerts
          steps:
          - match: ${channel.severity}
            cases:
            - pattern: "warning"
              steps:
              - iot.command:
                  device: thermostat-1
                  action: set_temperature
                  params: { temperature: 20, unit: CELSIUS }
            - pattern: "critical"
              steps:
              - parallel:
                - iot.command: { device: thermostat-1, action: set_temperature, params: { temperature: 18, unit: CELSIUS } }
                - iot.command: { device: fan-1, action: turn_on, params: { speed: high } }
            - default:
              steps: []
        - wait: emergency-temp
          steps:
          - parallel:
            - iot.command: { device: thermostat-1, action: turn_off }
            - iot.command: { device: fan-1, action: turn_on, params: { speed: max } }
            - iot.command: { device: alarm-1, action: sound_alarm }
```

### 5. occupancy-driven.yaml

**Primitives:** state machine transitions (home → away → vacation)

```yaml
scenario: occupancy-driven
meta:
  title: Occupancy-Driven Automation
  description: State machine-driven device orchestration based on occupancy transitions.
  area: iot
  labels: [orchestration, occupancy]

orchestration:
  machines:
    occupancy:
      initial: home
      states: [home, away, vacation]

sections:
  - title: Transition to Away
    steps:
      - step: depart
        iot.state: { device: presence-front-1 }
        transition:
          machine: occupancy
          to: away
        if: ${not device.presence-front-1.capabilities.presenceDetected}
      - parallel:
        - iot.command: { device: light-living-1, action: turn_off }
        - iot.command: { device: light-kitchen-1, action: turn_off }
        - iot.command: { device: thermostat-1, action: set_temperature, params: { temperature: 16, unit: CELSIUS } }
      - iot.command: { device: alarm-1, action: arm }

  - title: Transition to Home
    steps:
      - step: arrive
        iot.state: { device: presence-front-1 }
        transition:
          machine: occupancy
          to: home
        if: ${device.presence-front-1.capabilities.presenceDetected}
      - iot.command: { device: alarm-1, action: disarm }
      - parallel:
        - iot.command: { device: light-hallway-1, action: turn_on }
        - iot.command: { device: thermostat-1, action: set_temperature, params: { temperature: 22, unit: CELSIUS } }
```

---

## Data Flow

```
YAML scenario file
    │
    ▼
StepWalker.resolve()
    │  "iot.command" resolved via PluginScanner → IoTCommandPlugin
    │  "iot.state" resolved via PluginScanner → IoTStatePlugin
    ▼
StructuralStepEvaluator
    │  ParallelStep → virtual thread per child step
    │  BarrierStep → OrcLatch.await()
    │  QuorumStep → OrcLatch + QuorumTracker
    │  TryCatchFinallyStep → catch has access to ${result.step-name}
    │  IfElseStep → ConditionEvaluator + IoTDeviceVariableSource
    ▼
IoTCommandPlugin.run()
    │  DeviceRegistry.findById(deviceId) → DeviceEntity
    │  DeviceProvider.dispatch(command) → CommandResult
    │  Blocks on virtual thread until command completes
    ▼
Result → StepResultStore
    │  Available as ${result.step-name.result}
    │  Available as ${result.step-name.device}
    │  Counts down barrier/quorum latches
```

---

## Testing Strategy

### Unit tests

- `IoTCommandPluginTest` — tests dispatch with SENT/FAILED/TIMEOUT outcomes, missing device, missing provider
- `IoTStatePluginTest` — tests state read, missing device, capability extraction
- `IoTDeviceVariableSourceTest` — tests path resolution: full entity, capabilities, nested capability values, null location

### Integration tests

- `IoTScenarioIntegrationTest` — runs test scenarios against `MockDeviceProvider` via `StructuralStepEvaluator`:
  - Parallel command dispatch verifies concurrent execution
  - Quorum verifies N-of-M with partial failure
  - Try/catch verifies compensation commands execute on failure
  - If/else verifies device state conditions route correctly
  - State machine verifies transition guards

---

## Documentation Updates

1. `CLAUDE.md` — add `scenario` to the Module Structure table
2. `docs/guides/consumer-guide.md` — add scenario orchestration section covering plugin YAML syntax, concurrency primitives, and example scenarios

---

## Cross-Repo Changes

### Platform (casehubio/platform)

1. Add `DomainVariableSource` interface to `yaml-plugin-api`
2. Wire discovery in `CdiServiceRegistry` or `StructuralStepEvaluator` to incorporate domain sources into the VariableResolver

These are the only platform changes. All concurrency primitives, step types, and execution machinery already exist.

### IoT (casehubio/iot)

1. New `scenario/` module with plugins, variable source, and tests
2. New orchestration YAML files in `webapp/src/main/resources/META-INF/scenarios/`
3. Add `casehub-iot-scenario` dependency to webapp pom.xml

---

## Relationship to desiredstate

Independent. Both modules use `DeviceProvider` but serve different purposes:

| Concern | Scenario orchestration | Desiredstate convergence |
|---------|----------------------|-------------------------|
| Model | Imperative — finite workflow | Declarative — continuous convergence |
| Use case | "Lock doors, then arm alarm" | "Keep all doors locked" |
| Failure handling | try/catch/finally with compensation | FaultPolicy + escalation |
| Verification | retry decorator + iot.state query | Continuous reconciliation loop |

A future issue could bridge them with an `iot.converge` step that submits desiredstate goals from within a scenario, but that coupling is not needed for the use cases in scope.

---

## Relationship to platform #486

This issue builds the first consumer of the platform step runtime's concurrency machinery for distributed command dispatch. The IoT plugin is structured as a thin adapter. When a second domain appears (services with HTTP/gRPC commands, workflow with human task commands), the platform can extract a generic `CommandDispatcher` SPI from the IoT pattern. That extraction is platform #486's scope.

The one platform change made now — `DomainVariableSource` — is genuinely cross-domain and worth adding to the platform immediately rather than waiting for a second consumer.

---

## References

- `StructuralStepEvaluator.java` — platform step execution engine (parallel, barrier, quorum, try/catch, if/else, match, select)
- `StepWalker.java` — platform YAML step parser and resolver
- `DecoratorChain.java` — retry, timeout, delay, semaphore decorators
- `yaml-core/orchestration/` — OrcLatch, OrcSemaphore, OrcSignal, OrcChannel, CorrelationScope, ScenarioScope
- `AssertPlugin.java`, `RestCallPlugin.java` — existing plugin pattern
- `DeviceProvider.java:18` — dispatch() SPI (blocking, virtual-thread-safe)
- `CommandResult.java` — SENT/FAILED/TIMEOUT enum
- `IoTNodeProvisioner.java` — existing desiredstate provisioner (independent module)
- casehubio/iot#117 — this issue
- casehubio/platform#486 — upstream platform SPI issue
- D9-D13 in decisions.md — design decisions

# Declarative Ordering Constraints for IoT Desired State

**Date:** 2026-10-04
**Issue:** casehubio/iot#125
**Parent:** casehubio/iot#121 (IoT desired state epic), Phase 3 item 9
**Blocker resolved:** casehubio/casehub-desiredstate#159 (edge handling)

---

## Problem

IoT operators need to declare structural ordering constraints — "lock all doors before turning on lights" — that apply automatically to any desired state or preset touching those device classes. Today, cross-device ordering requires per-instance `dependsOn` declarations in each YAML goal file, which is repetitive and error-prone.

The desiredstate foundation already provides `OrderingConstraint(NodeType before, NodeType after)` and the `TransitionPlanner` resolves these into virtual edges during topological sort. But IoT currently has only two coarse NodeTypes (`"device-config"`, `"physical-device"`), making it impossible to express device-class-level constraints through the foundation mechanism.

## Design

### 1. DeviceClass-aware NodeTypes

Change `IoTNodeSpec` implementations to return composite NodeTypes that encode both the operation kind and the device class:

- `DeviceConfigSpec.nodeType()` → `NodeType.of("device-config/" + deviceClass.name().toLowerCase())`
- `PhysicalDeviceSpec.nodeType()` → `NodeType.of("physical-device/" + deviceClass.name().toLowerCase())`
- `IoTReviewSpec.nodeType()` → unchanged (`"iot-review"`)

Examples: `"device-config/light"`, `"physical-device/lock"`, `"device-config/thermostat"`

A static utility method generates the full set of handled types from `DeviceClass.values()`:

```java
public static Set<NodeType> allIoTNodeTypes() {
    var types = new HashSet<NodeType>();
    for (DeviceClass dc : DeviceClass.values()) {
        String name = dc.name().toLowerCase();
        types.add(NodeType.of("device-config/" + name));
        types.add(NodeType.of("physical-device/" + name));
    }
    types.add(NodeType.of("iot-review"));
    return Set.copyOf(types);
}
```

### 2. YAML Ordering Constraint Format

Constraints use DeviceClass enum names (case-insensitive) and appear in an `ordering:` section:

```yaml
ordering:
  - before: LOCK
    after: LIGHT
  - before: SWITCH
    after: COVER
```

Each entry expands to two `OrderingConstraint` records during compilation — one per prefix (same-prefix only):
- `OrderingConstraint(NodeType.of("device-config/lock"), NodeType.of("device-config/light"))`
- `OrderingConstraint(NodeType.of("physical-device/lock"), NodeType.of("physical-device/light"))`

Cross-prefix constraints (e.g., `physical-device/lock → device-config/light`) are intentionally omitted. The within-device `physical → config` dependency edge already ensures physical provisioning precedes config within each device. Cross-prefix expansion would prevent `physical-device/light` from running in parallel with `device-config/lock` — unnecessary since physical device registration is idempotent and doesn't affect device behaviour. For the rare case where full sequential ordering is needed, explicit `dependsOn` edges serve that purpose.

Invalid DeviceClass names are rejected at load time with a clear error message.

### 3. Two Loading Paths

#### Global constraints

Global constraint files live in a configurable directory:

```properties
casehub.iot.ordering.path=/etc/casehub/ordering
```

Files are YAML with just `ordering:` sections (no `tenancyId`, no `devices`). All files in the directory are loaded and unioned. Global constraints always apply to every compilation.

```yaml
# /etc/casehub/ordering/power-safety.yaml
ordering:
  - before: SWITCH
    after: COVER
  - before: LOCK
    after: LIGHT
```

#### Preset-inline constraints

Presets can include an `ordering:` section alongside `devices:`. When a preset is imported, its ordering constraints are included.

```yaml
# presets/night-mode.yaml
import:
  - locks-armed
tenancyId: test-tenant
ordering:
  - before: LOCK
    after: LIGHT
devices:
  - deviceId: light-living
    deviceClass: LIGHT
    label: Living Room Light
    config:
      isOn: true
      brightness: 30
```

### 4. Composition Semantics

Global and preset constraints compose via **additive union**:

1. Load all global constraint files → `Set<OrderingConstraint> globals`
2. Resolve preset (including imports) → collect `ordering:` from all fragments → `Set<OrderingConstraint> presetConstraints`
3. Effective constraints = `globals ∪ presetConstraints`
4. Pass to `DesiredStateGraphFactory.of(nodes, deps, effectiveConstraints)` or `graph.withOrderingConstraints(effectiveConstraints)`

No override or exclusion mechanism. Cycles are detected early at constraint union time (see §5a), not deferred to the planner.

### 5. IoTGoalCompiler Changes

The compiler's `compile()` method gains a constraint parameter path:

```java
public CompilationResult compile(IoTGoals goals, DesiredStateGraphFactory factory) {
    // ... existing node and dependency construction ...
    Set<OrderingConstraint> constraints = resolveConstraints(goals);
    return CompilationResult.single(factory.of(nodes, deps, constraints));
}
```

The `resolveConstraints()` method:
1. Reads global constraints (injected via a new `IoTOrderingConfig` config mapping)
2. Reads inline constraints from `IoTGoals` (new field)
3. Unions both sets
4. Expands each `(before: DeviceClass, after: DeviceClass)` to the two NodeType-qualified `OrderingConstraint` records

### 5a. Early Cycle Detection

`IoTGoalCompiler.resolveConstraints()` validates the constraint set for cycles before passing to the graph factory. The constraint graph is small (max ~11 DeviceClass nodes), so cycle detection is O(V+E) and cheap. A cycle between global and preset constraints is a configuration error that should fail loudly at compilation time, not silently at first reconciliation in production.

```java
private void validateNoCycles(Set<IoTOrderingEntry> constraints) {
    // build adjacency list from DeviceClass pairs, topological sort, throw on cycle
}
```

### 6. Data Model Changes

#### IoTGoals

Add an optional `ordering` field:

```java
public record IoTGoals(
    String tenancyId,
    List<IoTDeviceGoal> devices,
    List<IoTOrderingEntry> ordering
) {
    public IoTGoals {
        Objects.requireNonNull(tenancyId, "tenancyId required");
        devices = List.copyOf(devices);
        ordering = ordering != null ? List.copyOf(ordering) : List.of();
    }

    // backward-compatible constructor
    public IoTGoals(String tenancyId, List<IoTDeviceGoal> devices) {
        this(tenancyId, devices, List.of());
    }
}
```

#### IoTOrderingEntry

```java
public record IoTOrderingEntry(DeviceClass before, DeviceClass after) {
    public IoTOrderingEntry {
        Objects.requireNonNull(before, "ordering 'before' required");
        Objects.requireNonNull(after, "ordering 'after' required");
        if (before == after) {
            throw new IllegalArgumentException(
                "Self-referencing ordering constraint: " + before);
        }
    }
}
```

### 7. IoTPresetResolver Changes

When merging presets, ordering constraints are unioned (not last-wins like device configs):

```java
public static IoTGoals mergeGoals(IoTGoals... fragments) {
    // ... existing device merge logic ...
    Set<IoTOrderingEntry> allOrdering = new LinkedHashSet<>();
    for (IoTGoals fragment : fragments) {
        allOrdering.addAll(fragment.ordering());
    }
    return new IoTGoals(tenancyId, List.copyOf(merged.values()),
                        List.copyOf(allOrdering));
}
```

### 8. Global Constraint Loader

New `IoTOrderingLoader` class:

```java
@ApplicationScoped
public class IoTOrderingLoader {
    private final String orderingDir;

    public Set<IoTOrderingEntry> loadGlobal() {
        // scan directory for YAML files, parse ordering: sections, union all entries
    }
}
```

Configuration:

```java
@ConfigMapping(prefix = "casehub.iot.ordering")
public interface IoTOrderingConfig {
    Optional<String> path();
}
```

### 9. Affected Files — handledTypes() Updates

**IoTNodeProvisioner:**
```java
@Override
public Set<NodeType> handledTypes() {
    return IoTNodeTypes.all(); // static utility generates from DeviceClass enum
}
```

**IoTActualStateAdapter:**
```java
public Set<NodeType> handledTypes() {
    return IoTNodeTypes.allConfig().union(IoTNodeTypes.allPhysical());
}
```

**IoTFaultPolicy:**
```java
private final ThresholdFaultPolicy delegate = ThresholdFaultPolicy.builder()
    .faultTypes(Set.of(FaultType.PROVISION_FAILED))
    .nodeTypes(IoTNodeTypes.allConfig())  // all device-config/* variants
    .ignoreTypes(Set.of(NodeType.of("iot-review")))
    .tier(ESCALATION_THRESHOLD, ...)
    .build();
```

### 10. Topology Visualization

Ordering constraints are class-level structural metadata, not instance-level dependencies. They are surfaced as a separate section in the topology response rather than mixed into device-to-device edges.

#### New record

```java
public record TopologyOrderingConstraint(String beforeClass, String afterClass) {}
```

#### TopologyResponse change

Add `List<TopologyOrderingConstraint> orderingConstraints` alongside existing `nodes` and `edges`.

#### TopologyAssembler change

Read `orderingConstraints()` from the desired-state graph. For each constraint, extract the DeviceClass from the composite NodeType (e.g., `"device-config/lock"` → `"LOCK"`), deduplicate (since each YAML constraint expands to two OrderingConstraints with the same DeviceClass pair), and emit a `TopologyOrderingConstraint`.

```java
Set<TopologyOrderingConstraint> constraints = new LinkedHashSet<>();
for (OrderingConstraint c : graph.orderingConstraints()) {
    String before = extractDeviceClass(c.before()); // "device-config/lock" → "LOCK"
    String after = extractDeviceClass(c.after());
    constraints.add(new TopologyOrderingConstraint(before, after));
}
```

#### Frontend rendering

The frontend renders ordering constraints in a dedicated section, distinct from device-to-device dependency edges. Text-only rendering: `"LOCK → LIGHT (ordering constraint)"`.

### 11. Test Strategy

| Test | What it verifies |
|------|-----------------|
| `IoTGoalCompilerTest` — new cases | Constraints from goals are passed to graph factory; DeviceClass expansion produces correct NodeType pairs |
| `IoTOrderingEntryTest` | Null rejection; self-reference rejection; DeviceClass validation |
| `IoTGoalLoaderTest` — new cases | YAML with `ordering:` section deserializes correctly; missing ordering defaults to empty |
| `IoTPresetResolverTest` — new cases | Preset imports union ordering entries; global + preset composition |
| `IoTOrderingLoaderTest` | Directory scanning; multi-file union; empty directory; invalid DeviceClass |
| `IoTNodeSpecTest` — updated | New composite NodeType values verified |
| `IoTFaultPolicyTest` — updated | Fault policy still triggers on composite NodeType variants |
| `IoTNodeProvisionerTest` — updated | Provisioner handles composite NodeType variants |
| `IoTNodeTypesConsistencyTest` — new | Exhaustive check: every `DeviceClass` value represented in provisioner, adapter, and fault policy handledTypes() sets |
| `IoTGoalCompilerTest` — cycle detection | Conflicting constraints (A→B, B→A) throw at compile time, not deferred to planner |
| `TopologyAssemblerTest` — new cases | Ordering constraints in separate section; DeviceClass deduplication; missing device classes omitted |

## References

- `io.casehub.desiredstate.api.OrderingConstraint` — foundation ordering constraint record
- `io.casehub.desiredstate.api.DesiredStateGraph.orderingConstraints()` — graph constraint accessor
- `io.casehub.desiredstate.api.DesiredStateGraphFactory.of(nodes, deps, constraints)` — 3-arg factory method
- `io.casehub.desiredstate.runtime.TransitionPlanner.topologicalSort()` — virtual edge resolution for constraints

- `io.casehub.iot.desiredstate.IoTGoalCompiler` — current goal compiler (no constraint support)
- `io.casehub.iot.desiredstate.IoTPresetResolver` — current preset resolver with import composition
- `io.casehub.iot.desiredstate.IoTGoals` — current goal record (no ordering field)
- `io.casehub.iot.webapp.app.service.TopologyAssembler` — topology assembly (dependencies only)
- casehubio/casehub-desiredstate#159 — edge handling foundation (closed, resolved)
- casehubio/iot#121 — parent epic

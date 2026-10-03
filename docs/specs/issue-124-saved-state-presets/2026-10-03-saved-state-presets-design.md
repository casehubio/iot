# Saved State Presets — Design Spec

**Issue:** casehubio/iot#124
**Epic:** casehubio/iot#121 (Phase 3, item 8)
**Date:** 2026-10-03

## Core Concept

A preset is a named desired state. It is an `IoTGoals` YAML file in a known directory, identified by filename. No special type — load, compile, and reconcile use the existing desiredstate pipeline. The additions are name resolution, an `import:` merge directive, and a REST surface.

## Architecture

```
desiredstate module (library, no runtime)
├── IoTGoalLoader          — existing: load/merge IoTGoals from YAML
│   └── mergeGoals(goals…) — NEW: deep-merge with last-wins (unlike merge() which rejects duplicates)
├── IoTGoals               — existing record, unchanged
├── IoTPresetConfig        — NEW: @ConfigMapping for casehub.iot.presets.path
└── IoTPresetResolver      — NEW: name → path resolution, import expansion, merge orchestration

webapp module (Quarkus runtime)
└── DefaultIoTPresetApi    — NEW: REST surface (@McpDomain)
    ├── GET  /iot/presets              — list available presets
    ├── GET  /iot/presets/{name}/diff  — preview what would change
    └── POST /iot/presets/{name}/apply — load → compile → reconcile
```

## YAML Format

A preset file is a standard `IoTGoals` YAML with an optional `import:` header:

```yaml
# presets/away-mode.yaml
import:
  - night-mode
  - locks-armed

tenancyId: ${casehub.iot.tenancy-id}
devices:
  - deviceId: camera-front
    deviceClass: CAMERA
    config:
      recording: true
      motionAlerts: true
```

### Import Resolution

1. `import:` is a list of preset names (filenames without extension)
2. Resolution is one-level — imported presets' own `import:` directives are ignored
3. Resolution order: imports are loaded left-to-right, then the declaring preset is applied last
4. For the example above: load `night-mode`, load `locks-armed`, merge with `away-mode` on top
5. The declaring preset always wins for any device it explicitly targets

### Import Parsing

`import:` is not a field on `IoTGoals` — it is a preset-layer concern. `IoTPresetResolver` pre-parses the raw YAML tree to extract the `import` list, removes it, then deserializes the remainder as `IoTGoals` via `IoTGoalLoader`. This keeps `IoTGoals` clean — it has no knowledge of presets or composition.

### Conflict Resolution (Last-Wins Merge)

When multiple presets target the same `deviceId`:

1. Config maps are deep-merged, not replaced — a preset that sets `{brightness: 50}` does not erase `{isOn: true}` from an earlier preset
2. For the same property, later value wins: `night-mode` sets `brightness: 30`, `away-mode` sets `brightness: 0` → result is `brightness: 0`
3. Non-config fields (`deviceClass`, `label`, `physical`, `dependsOn`) use last-wins at the field level

This requires a new `mergeGoals()` method on `IoTGoalLoader`. The existing `merge()` method (which rejects duplicate `deviceId`s) remains unchanged for non-preset use cases.

## Components

### IoTPresetConfig

```java
@ConfigMapping(prefix = "casehub.iot.presets")
public interface IoTPresetConfig {
    Optional<String> path();
}
```

When `path` is absent, all preset operations return empty/404.

### IoTPresetResolver

Responsible for name resolution and import expansion:

```java
@ApplicationScoped
public class IoTPresetResolver {
    IoTGoals resolve(String name);       // load + expand imports + merge
    List<PresetInfo> listPresets();       // scan directory, parse headers
}
```

`resolve(name)`:
1. Look up `{presetDir}/{name}.yaml` (then `.yml`)
2. Parse the `import:` header (if present)
3. Load each imported preset by name (no recursion — one level)
4. `IoTGoalLoader.mergeGoals(imported1, imported2, ..., thisPreset)`
5. Return the merged `IoTGoals`

`listPresets()`:
1. Scan preset directory for `*.yaml` / `*.yml` files
2. For each file, parse the YAML header to extract: name (filename), `import` list, device count
3. Return list of `PresetInfo` records

### PresetInfo Record

```java
public record PresetInfo(
    String name,
    List<String> imports,
    int deviceCount
) {}
```

### IoTGoalLoader.mergeGoals()

New method alongside existing `merge()`:

```java
public static IoTGoals mergeGoals(IoTGoals... fragments)
```

Behaviour:
- Same `tenancyId` validation as `merge()`
- When two fragments contain the same `deviceId`: deep-merge `config` maps (later wins per key), later fragment wins for non-config fields
- When `deviceId` is unique: include as-is (same as `merge()`)

### PresetDiff Record

```java
public record PresetDiff(
    List<DeviceChange> changes
) {
    public record DeviceChange(
        String deviceId,
        String deviceClass,
        List<PropertyChange> properties
    ) {}

    public record PropertyChange(
        String property,
        Object current,
        Object desired
    ) {}
}
```

### DefaultIoTPresetApi

REST surface in webapp, following the `@McpDomain` pattern:

```java
@McpDomain("iot/presets")
@ApplicationScoped
@Blocking
public class DefaultIoTPresetApi {

    @PlatformQuery(path = "/iot/presets", description = "List available presets")
    List<PresetInfo> listPresets(@ContextParam("tenancyId") String tenancyId);

    @PlatformQuery(path = "/iot/presets/{name}/diff", description = "Preview changes without applying")
    PresetDiff diffPreset(
        @ContextParam("tenancyId") String tenancyId,
        @PathParam("name") String name);

    @PlatformMutation(path = "/iot/presets/{name}/apply", description = "Apply a preset")
    ConvergenceResult applyPreset(
        @ContextParam("tenancyId") String tenancyId,
        @PathParam("name") String name);
}
```

**List:** delegates to `IoTPresetResolver.listPresets()`.

**Diff:** `resolve(name)` → `IoTGoalCompiler.compile()` → `IoTActualStateAdapter.readActual()` → compare per-device properties → return `PresetDiff`.

**Apply:** `resolve(name)` → `IoTGoalCompiler.compile()` → reconcile via desiredstate runtime → return `ConvergenceResult`. Synchronous — blocks until convergence completes.

## Data Flow

```
Operator → POST /iot/presets/away-mode/apply
  → DefaultIoTPresetApi.applyPreset("away-mode")
    → IoTPresetResolver.resolve("away-mode")
      → IoTGoalLoader.load("night-mode.yaml")       // import 1
      → IoTGoalLoader.load("locks-armed.yaml")       // import 2
      → IoTGoalLoader.load("away-mode.yaml")          // self
      → IoTGoalLoader.mergeGoals(nightMode, locksArmed, awayMode)
      → merged IoTGoals
    → IoTGoalCompiler.compile(mergedGoals)
      → DesiredStateGraph
    → desiredstate runtime reconcile
      → ConvergenceResult
  ← 200 OK + ConvergenceResult JSON
```

## Testing

1. **IoTGoalLoader.mergeGoals()** — unit tests:
   - Non-overlapping devices → same as merge()
   - Same deviceId, different properties → deep-merged config
   - Same deviceId, same property → last wins
   - Different tenancyId → fails

2. **IoTPresetResolver** — unit tests:
   - Name resolution: `"night-mode"` → `{dir}/night-mode.yaml`
   - Import expansion: loads imports, merges in order
   - One-level: imported preset's imports are ignored
   - Missing import → clear error
   - Missing preset → clear error

3. **DefaultIoTPresetApi** — integration tests:
   - List returns all presets in directory
   - Diff shows per-device property changes
   - Apply triggers convergence and returns result
   - Empty preset directory → empty list / 404

4. **PresetDiff** — unit tests:
   - Device with no changes → excluded from diff
   - Device not in actual state → all properties shown as `current: null`

## Scope Boundary

**In scope:**
- Preset directory config, name resolution, import with one-level merge
- `mergeGoals()` with last-wins deep-merge
- REST list/diff/apply endpoints
- Unit and integration tests

**Out of scope:**
- Transitive imports (future if needed)
- Preset editing/CRUD via REST (operators edit YAML files directly)
- Preset versioning or history
- File-watching / hot-reload (restart picks up changes)
- UI for preset management (future, depends on casehub-pages)

## References

- `IoTGoalLoader.java` (desiredstate module) — existing load/merge
- `IoTGoalCompiler.java` (desiredstate module) — goal → graph compilation
- `IoTActualStateAdapter.java` (desiredstate module) — reads current device state
- `DefaultIoTDeviceApi.java` (webapp) — REST pattern reference
- casehubio/iot#121 — parent epic
- casehubio/casehub-desiredstate#153 — "presets are just DesiredStateGraphs" (foundation position)
- Protocol: `harness-rest-resource-blocking-applicationscoped`
- Protocol: `configmapping-prefix-ownership`

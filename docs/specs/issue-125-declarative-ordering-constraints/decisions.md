## D1: NodeType granularity — DeviceClass-aware

**Choice:** Make IoTNodeSpec return DeviceClass-aware NodeTypes (e.g., `"device-config/light"`, `"physical-device/thermostat"`)
**Alternatives:**
- Resolve to Dependency edges in compiler — simpler change but loses structural metadata and duplicates planner logic
- IoT-specific constraint resolver SPI — adds indirection for a problem with a simpler solution
**Rationale:** Foundation planner already resolves `OrderingConstraint(NodeType, NodeType)` into virtual edges during topological sort. Making NodeType device-class-aware lets IoT constraints work through the existing mechanism with zero custom resolution logic.
**Trade-offs:** `handledTypes()` on IoTNodeProvisioner and IoTActualStateAdapter must enumerate all DeviceClass variants. IoTFaultPolicy's ThresholdFaultPolicy filter needs updating.
**Sources:** `io.casehub.desiredstate.api.OrderingConstraint`, `io.casehub.desiredstate.runtime.TransitionPlanner.topologicalSort()`, `io.casehub.iot.desiredstate.DeviceConfigSpec`, `io.casehub.iot.desiredstate.PhysicalDeviceSpec`
**Exploration:** quick
**Status:** captured

## D2: YAML format — global + preset-inline

**Choice:** Support both global constraints (separate directory, always applied) and preset-inline constraints (travel with the preset via `ordering:` section)
**Alternatives:**
- Inline only — constraints travel with presets but no structural baseline
- Separate files only — clean separation but constraints and presets can drift out of sync
**Rationale:** Global constraints encode structural invariants ("circuit breaker before equipment") that always apply. Preset-level constraints encode context-specific ordering ("lock before light in night mode"). Both are valid use cases.
**Trade-offs:** Merge complexity — must define composition semantics (see D3).
**Sources:** `io.casehub.iot.desiredstate.IoTPresetResolver`, `io.casehub.desiredstate.api.OrderingConstraint`
**Exploration:** quick
**Status:** captured

## D3: Composition — additive union

**Choice:** Global and preset constraints compose via additive union. No override or exclusion mechanism.
**Alternatives:**
- Additive with preset exclusions — presets can suppress globals, more flexible but more complex
**Rationale:** Simplest mental model — constraints only accumulate, never subtract. The planner detects cycles if constraints conflict (throws `IllegalStateException`), which is the right failure mode.
**Trade-offs:** Can't suppress a global constraint for a specific preset. Concrete scenario: a factory-reset preset needs to deprovision all devices simultaneously, but global constraints force ordered teardown. Mitigation: (a) ordered deprovision is still functionally correct (slower but safe), and (b) an operator can remove the global constraint file as a manual escape hatch. If preset-level suppression becomes a real need beyond these mitigations, exclusions can be added later as a backward-compatible extension.
**Sources:** `io.casehub.desiredstate.runtime.TransitionPlanner` cycle detection
**Exploration:** quick
**Status:** captured

## D4: Constraint addressing — DeviceClass only

**Choice:** Constraints reference DeviceClass enum values only (e.g., `LOCK`, `LIGHT`). Instance-level ordering uses the existing `dependsOn` mechanism.
**Alternatives:**
- DeviceClass + instance-level — more powerful but blurs the line with `dependsOn`
- DeviceClass + wildcard patterns — most flexible but most complex to implement
**Rationale:** Clean separation: `ordering:` for type-level structural constraints, `dependsOn:` for instance-level dependencies. Maps directly to the foundation's `OrderingConstraint(NodeType, NodeType)` vs `Dependency(NodeId, NodeId)` distinction.
**Trade-offs:** Cannot express "this specific breaker before that specific HVAC" via ordering — must use `dependsOn` for that.
**Sources:** `io.casehub.desiredstate.api.OrderingConstraint`, `io.casehub.iot.desiredstate.IoTDeviceGoal.dependsOn()`
**Exploration:** quick
**Status:** captured

## D5: Topology visualization — separate constraint section

**Choice:** Add a `List<TopologyOrderingConstraint>` field to `TopologyResponse`, modelled as class-level metadata (DeviceClass pairs) rather than instance-level edges. TopologyAssembler reads `orderingConstraints()` from the graph and maps them to `TopologyOrderingConstraint(DeviceClass before, DeviceClass after)` records. Frontend renders these in a dedicated section, distinct from device-to-device dependency edges.
**Alternatives:**
- Representative device pairs — resolve type-level constraints to one device pair per constraint. Semantically misleading: shows one lock→light edge when the constraint applies to ALL locks and ALL lights.
- Fan-out all pairs — create edges between ALL devices of the relevant classes. Semantically accurate but creates visual clutter (5 locks × 10 lights = 50 edges).
- Out of scope — constraints invisible in UI, only affect provisioning order
**Rationale:** Ordering constraints are class-level structural metadata, not instance-level dependencies. Mixing them into the device-to-device edge list misrepresents their scope. A separate section preserves the semantic distinction and avoids visual clutter.
**Trade-offs:** New record type (`TopologyOrderingConstraint`) and response field. Frontend needs a new rendering section. Slightly more work than reusing existing edge rendering.
**Sources:** `io.casehub.iot.webapp.app.service.TopologyAssembler`, `io.casehub.iot.webapp.rest.TopologyResponse`
**Exploration:** quick
**Status:** revised

## D6: NodeType naming — composite prefix convention

**Choice:** Approach A: `DeviceConfigSpec.nodeType()` returns `NodeType.of("device-config/" + deviceClass)`, `PhysicalDeviceSpec.nodeType()` returns `NodeType.of("physical-device/" + deviceClass)`. YAML constraint `{before: LOCK, after: LIGHT}` expands to two OrderingConstraints (one per prefix).
**Alternatives:**
- DeviceClass as sole NodeType — simpler mapping but loses physical/config distinction
- Keep coarse NodeTypes, resolve in compiler — rejected in D1
**Rationale:** Preserves the physical/config distinction that IoTNodeProvisioner, IoTActualStateAdapter, and IoTFaultPolicy rely on. Two-constraint (same-prefix) expansion is correct because the within-device `physical → config` dependency edge already ensures physical provisioning precedes config within a device. Cross-prefix constraints (4 total) would prevent `physical-device/light` from running in parallel with `device-config/lock` — unnecessary since physical device registration is idempotent and doesn't affect device behaviour. For the rare safety-critical case where full sequential ordering is needed, explicit `dependsOn` edges already serve that purpose.
**Trade-offs:** `handledTypes()` must enumerate all variants (22 types from 11 DeviceClass values × 2 prefixes, plus "iot-review"). Generated statically from the enum. Silent failure risk: if handledTypes() or ThresholdFaultPolicy nodeTypes are not updated in lockstep with the NodeType change, fault handling silently stops. Mitigated by exhaustive test coverage (see D7).
**Depends on:** D1 (DeviceClass-aware NodeTypes)
**Sources:** `io.casehub.iot.desiredstate.IoTNodeProvisioner.handledTypes()`, `io.casehub.iot.desiredstate.IoTFaultPolicy`
**Exploration:** quick
**Status:** captured

## D7: Early cycle detection — fail-fast at constraint union time

**Choice:** Validate for cycles at constraint union time in `IoTGoalCompiler.resolveConstraints()`, not just at planning time in TransitionPlanner.
**Alternatives:**
- Defer to planner — cycles detected as `IllegalStateException` during first reconciliation attempt, potentially in production
- Startup validation — validate global constraints at application boot, but preset-level conflicts still deferred
**Rationale:** The constraint graph is small (max ~11 DeviceClass nodes). Cycle detection is O(V+E) and cheap. A cycle between global and preset constraints means the structural invariants are contradictory — this is a configuration error that should fail loudly at compilation time, not silently at first reconciliation.
**Trade-offs:** Small utility method for graph cycle detection on the constraint set. Duplicates the planner's cycle detection but at a different lifecycle stage.
**Sources:** `io.casehub.desiredstate.runtime.TransitionPlanner.topologicalSort()` cycle detection
**Exploration:** quick
**Status:** captured

## D8: Silent failure guard — exhaustive handledTypes() test

**Choice:** Add test that exhaustively verifies every `DeviceClass` value is represented in `IoTNodeProvisioner.handledTypes()`, `IoTActualStateAdapter.handledTypes()`, and `IoTFaultPolicy`'s ThresholdFaultPolicy nodeTypes set.
**Alternatives:**
- Runtime startup validation — cross-check handledTypes() sets at boot. More defensive but adds startup cost.
**Rationale:** The NodeType change from 2 coarse values to 22+ fine-grained values is a breaking change with a silent failure mode: `contains()` checks on the old values silently return false. A test that iterates `DeviceClass.values()` and asserts each variant is handled catches this at build time.
**Trade-offs:** None — pure test coverage.
**Depends on:** D6 (composite NodeType naming)
**Sources:** `io.casehub.iot.desiredstate.IoTNodeProvisioner.handledTypes()`, `io.casehub.iot.desiredstate.IoTFaultPolicy`
**Exploration:** quick
**Status:** captured

## D9: IoTReviewSpec — excluded from constraint system

**Choice:** `IoTReviewSpec` implements `NodeSpec` directly, not `IoTNodeSpec`. It has no `DeviceClass` and cannot participate in ordering constraints.
**Alternatives:**
- Include review nodes in constraint hierarchy — add IoTReviewSpec to the sealed `IoTNodeSpec` permits list with a synthetic DeviceClass. Allows ordering constraints to affect review node placement.
**Rationale:** Review nodes are fault-handling artifacts created by `ThresholdFaultPolicy`, not operational devices declared by operators. They should not be subject to device-class ordering constraints because: (1) they are created dynamically in response to faults, not declared in YAML; (2) they have no meaningful "before/after" relationship with device classes — a review node for a failed lock is not a lock, it's an escalation artifact; (3) `IoTFaultPolicy` already places review nodes via the `addReviewNode()` mutation with explicit dependency on the faulted node, so their position in the graph is deterministic.
**Trade-offs:** If a future requirement needs ordering between review nodes and device classes, the `IoTNodeSpec` sealed hierarchy must be extended. This is a deliberate architectural boundary, not an oversight.
**Sources:** `io.casehub.iot.desiredstate.IoTReviewSpec`, `io.casehub.iot.desiredstate.IoTNodeSpec`, `io.casehub.desiredstate.api.FaultPolicy.addReviewNode()`
**Exploration:** quick
**Status:** captured

## D10: Unused constraints — silent ignore (permissive composition)

**Choice:** When a YAML constraint references a DeviceClass with no devices in the current graph, the constraint silently becomes a no-op. No warning, no error.
**Alternatives:**
- Warn on unused constraints — log a warning when a constraint references a DeviceClass absent from the current compilation. Provides operational hygiene.
- Error on unused constraints — reject compilations with dangling constraint references. Strictest validation.
**Rationale:** Global constraints are structural invariants declared independently of any particular goal set. A `LOCK → LIGHT` constraint is valid even when a specific preset contains no locks — the constraint simply doesn't apply to that compilation. Warning on every such case creates noise, especially when global constraints cover a broad range of device classes but individual presets are narrow. The `TransitionPlanner.nodesOfType()` method already handles this correctly: empty `beforeNodes` or `afterNodes` means zero virtual edges, zero in-degree adjustments — a clean no-op.
**Trade-offs:** A typo in a DeviceClass name (e.g., `LGHT` instead of `LIGHT`) would be caught at YAML load time by `IoTOrderingEntry` validation (DeviceClass enum parse failure), so the silent-ignore only applies to correctly-spelled but currently-absent device classes.
**Sources:** `io.casehub.desiredstate.runtime.TransitionPlanner.nodesOfType()`, `io.casehub.iot.desiredstate.IoTOrderingEntry`
**Exploration:** quick
**Status:** captured

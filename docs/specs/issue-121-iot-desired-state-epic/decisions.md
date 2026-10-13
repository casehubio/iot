## D1: Approval mechanism — native desiredstate, not ops

**Choice:** Use the desiredstate runtime's native `HumanGating` + `PendingApproval` mechanism
**Alternatives:**
- Extract ops `ApprovalEvaluator`/`PlanStore` to shared module — more infrastructure than needed; risk classification can be added later
- Optional ops-api dependency — creates tier-violating coupling for a feature that works natively
**Rationale:** The desiredstate API already provides approval flow via `HumanGating` on `DesiredNode` and `PendingApproval` return from `provision()`. The ops layer adds risk classification on top, which IoT doesn't need yet. Simpler, no cross-tier dependency.
**Trade-offs:** No risk classification at the provisioner level. Can be added as an IoT-specific concern later if needed.
**Sources:** `casehub-desiredstate-api/ProvisionResult.java`, `casehub-desiredstate-api/HumanGating.java`, `casehub-ops/iot/IoTNodeProvisioner.java`
**Exploration:** quick
**Status:** captured

## D2: Module placement — move ops/iot to casehub-iot/desiredstate

**Choice:** Move existing ops/iot code into a new `desiredstate/` module in casehub-iot. Delete ops/iot and ops-api/iot afterward.
**Alternatives:**
- Rewrite from scratch — risks regression, reproduces same code; no design benefit over the existing proven implementation
- Keep in ops — violates tier architecture (Foundation apps shouldn't depend on Integration-tier ops)
**Rationale:** The existing code is well-tested (11 test files) and structurally correct. The only coupling to remove is the ops approval infrastructure. No other ops module depends on ops/iot, so deletion is clean. IoT desired state is Foundation-tier domain logic, not a CaseHub deployment concern.
**Trade-offs:** Cross-repo change (iot + ops). But the ops side is pure deletion with no impact on other modules.
**Sources:** `docs/platform/capability-ownership.md`, `docs/platform/overview.md` (tier architecture), `protocols/universal/spi-adapter-module-placement.md`, `casehub-ops/iot/pom.xml` (no reverse deps)
**Exploration:** quick
**Status:** captured

## D3: Spec type placement — single module

**Choice:** Keep spec types (`IoTNodeSpec`, `DeviceConfigSpec`, `PhysicalDeviceSpec`, `IoTReviewSpec`) in the `desiredstate/` module alongside CDI beans
**Alternatives:**
- Put in iot-api — pollutes the public API surface with desiredstate-api transitive dependency; no consumer needs spec types without the provisioner
- New iot-desiredstate-api Tier 1 module — maximum flexibility but no current consumer justifies the overhead
**Rationale:** Spec types implement `NodeSpec` from desiredstate-api. They're only meaningful to consumers using the desiredstate runtime. Single-module structure is simpler and follows the optional-module-pattern protocol.
**Trade-offs:** Consumers who want just the spec types (without CDI beans) must still depend on the full module. No known consumer has this need.
**Sources:** `protocols/universal/optional-module-pattern.md`, `protocols/universal/module-tier-structure.md`
**Exploration:** quick
**Status:** captured

---

# Issue #119 — Device Topology Dual-View

## D4: Location hierarchy source — parse location strings

**Choice:** Parse `DeviceEntity.location()` as path-like segments (e.g. "HQ/Floor2/Kitchen") to derive the spatial tree. No new SPI needed.
**Alternatives:**
- New `DeviceLocationProvider` SPI — more correct for structured hierarchy but larger scope; no provider returns structured data today
- Flat by default, hierarchy via provider — loses the spatial tree value; grouping by deviceClass is just a filtered list, not a topology
**Rationale:** OpenHAB populates `location` from `thing.location()`, which follows a path convention. HA has null locations today (area registry pending). Parsing paths works with existing data and degrades gracefully (null locations appear at tree root as "Unassigned").
**Trade-offs:** Non-path location strings (e.g. "Kitchen") produce a flat single-level tree. Acceptable for v1 — deeper hierarchy comes when providers support it.
**Sources:** `DeviceEntity.java:28` (location field), `DeviceEntity.java:56` (location accessor), issue #119
**Exploration:** quick
**Status:** captured

## D5: Operational graph edges — derive from DesiredStateGraph

**Choice:** Derive ordering edges from the `DesiredStateGraph` node dependencies. Devices without desired state goals appear as independent nodes (sparse graph).
**Alternatives:**
- New edge declarations in YAML goals — adds a new concept to the goal format; no existing data to populate
- No edges in v1 — makes the physical view redundant (just a flat grid with drift overlays, same as logical view in different layout)
**Rationale:** Strong consensus from infrastructure-as-code tools (Terraform, Aspire, LF-Edge EVE): dependencies come from desired state declarations. Our `DesiredStateGraph` already models node dependencies as directed edges. Most IoT devices are independent — the sparse graph is the expected case.
**Trade-offs:** Devices without desired state goals show no edges. This is intentional — the physical view adds value precisely when operational dependencies exist.
**Sources:** Terraform dependency graph (HashiCorp), Microsoft Aspire resource graph, LF-Edge EVE depgraph, `DesiredStateGraph` API, issue #119
**Exploration:** deep-analysis
**Status:** captured

## D6: Frontend rendering — new IoT-specific Lit components

**Choice:** Create `blocks-topology-tree` (spatial tree) and `blocks-topology-graph` (operational graph) as new Lit web components following the existing blocks-ui pattern. Tree uses native DOM; graph uses `graph-core`/`graph-renderer`.
**Alternatives:**
- Single generic topology component with mode prop — simpler integration but the two views share very little rendering logic; false cohesion
- Extend existing devices page with tabs — topology is a fleet view, not a single-device view; wrong abstraction level
**Rationale:** Follows the established blocks-ui pattern (see `blocks-decomposition-tree`, `blocks-dag-viewer`). Each component has one clear purpose. Tree and graph rendering are fundamentally different (DOM tree vs. canvas/SVG graph).
**Trade-offs:** Two components to maintain instead of one. Worth it for clarity — the components share data types but not rendering.
**Sources:** `blocks-decomposition-tree.ts`, `blocks-dag-viewer.ts`, `graph-core`, `graph-renderer`
**Exploration:** quick
**Status:** captured

## D7: Page placement — new top-level Topology page

**Choice:** Standalone "Topology" page alongside devices, cases, situations, etc.
**Alternatives:**
- New tab on existing devices page — keeps device views together but overloads the page with two different navigation paradigms (list vs. spatial)
**Rationale:** Topology is a fleet-wide operational view with different intent from the device list/detail page. The devices page answers "what devices exist and what's their state." The topology page answers "where are devices and how do they relate."
**Trade-offs:** One more top-level navigation item. The IoT webapp already has 7 pages — an 8th is acceptable.
**Sources:** `devices.ts` (existing page structure), issue #119
**Exploration:** quick
**Status:** captured

## D8: Drift API surface — single /api/topology endpoint via @McpDomain

**Choice:** A single `/api/topology` endpoint that joins `DeviceRegistry` data with `DesiredStateGraph`/`ActualState` to return devices enriched with drift status, location hierarchy, and dependency edges. Uses `@McpDomain` pattern for SSE live updates.
**Alternatives:**
- Separate /api/drift endpoint + client-side join — cleaner API separation but two round trips and client-side data assembly
- Extend existing /api/devices with drift fields — couples the general device list API to the optional desiredstate module
**Rationale:** The topology view needs a pre-assembled view model. Server-side join avoids N+1 on the client and keeps the desiredstate module as an optional dependency (topology endpoint only activates when desiredstate is present). `@McpDomain` is the established pattern for domain APIs with SSE streaming in the webapp.
**Trade-offs:** New endpoint couples to both DeviceRegistry and desiredstate APIs. This is the point of the topology view — it's the integration surface.
**Depends on:** D5 (edges from DesiredStateGraph)
**Sources:** `DefaultIoTDeviceApi.java` (existing device API pattern), `@McpDomain` annotation pattern, issue #119
**Exploration:** quick
**Status:** captured

---

# Issue #117 — Map scenario concurrency to distributed device command orchestration

## D9: Plugin naming — separate iot.command and iot.state plugins

**Choice:** Two distinct plugins — `iot.command` for device dispatch, `iot.state` for state reads — each a `@Plugin`-annotated record in the new casehub-iot-scenario module
**Alternatives:**
- Single `iot` plugin with a `type` field — more compact but type validation defers to runtime; parse-time errors are better
**Rationale:** Follows existing plugin naming (assert, process, rest-call). Separate plugins give parse-time validation, distinct parameter sets, and clear intent in YAML. The `iot.` prefix acts as a namespace.
**Trade-offs:** Two plugin classes instead of one. Worth it for type safety and clarity.
**Sources:** `AssertPlugin.java`, `RestCallPlugin.java`, `StepWalker.java` (plugin resolution), yaml-plugin-api `@Plugin` annotation
**Exploration:** quick
**Status:** captured

## D10: DomainVariableSource SPI — in yaml-plugin-api

**Choice:** New `DomainVariableSource` interface in `casehub-platform-yaml-plugin-api` (Tier 1). Domains implement it to expose state to the VariableResolver for condition evaluation.
**Alternatives:**
- In yaml-step-runtime — simpler CDI wiring but restricts the SPI to Quarkus/CDI hosts; plugin-api is the lightweight Tier 1 surface
**Rationale:** Follows the existing Plugin/Parameter/Result pattern in yaml-plugin-api. The interface has no CDI dependency — discovery is the host's responsibility. Enables `${device.lock-front.capabilities.isLocked}` in any YAML condition.
**Trade-offs:** Host must wire discovery (CDI, Spring, etc.). This is the standard pattern for yaml-plugin-api — the SPI defines the contract, the runtime wires it.
**Sources:** `yaml-plugin-api` (existing Plugin, Parameter, Result interfaces), `ConfigVariableSource.java`, `VariableResolver.java`
**Exploration:** quick
**Status:** captured

## D11: Module placement — new casehub-iot-scenario module

**Choice:** New `scenario/` module in casehub-iot. Contains the iot.command and iot.state plugins, IoTDeviceVariableSource, and test scenarios. Production YAML scenarios stay in webapp's META-INF/scenarios/.
**Alternatives:**
- In webapp — fewer modules but couples the plugin code to the webapp; the scenario module is a library that any IoT-hosting app should be able to use
**Rationale:** Follows the existing module pattern (bridge, mcp, desiredstate are separate library modules). The scenario module depends on iot-api + yaml-plugin-api. Any Quarkus app with IoT providers can add it.
**Trade-offs:** One more module to maintain. Consistent with the existing module architecture.
**Sources:** Module structure in CLAUDE.md, `spi-adapter-module-placement.md` protocol
**Exploration:** quick
**Status:** captured

## D12: Scenario coverage — full suite exercising all concurrency primitives

**Choice:** Five orchestration scenarios covering all major concurrency primitives: arm-security (parallel, quorum, barrier, try/catch), emergency-response (if/else, parallel), nighttime-mode (match), hvac-cascade (select/channel), occupancy-driven (state machine).
**Alternatives:**
- Scene activation only — single pattern, too narrow to validate the design
- Security arm + emergency only — covers most primitives but misses match, select, and state machine
**Rationale:** Each primitive needs at least one scenario that exercises it. Five scenarios cover the full set without artificial combinations. They double as integration tests and documentation.
**Trade-offs:** More scenarios to write and maintain. Each one validates a distinct primitive, so none is redundant.
**Sources:** `StructuralStepEvaluator.java` (all step types), existing scenarios in META-INF/scenarios/
**Exploration:** quick
**Status:** captured

## D13: Relationship to desiredstate — independent, complementary

**Choice:** Scenario orchestration and desiredstate convergence are independent modules. Both use DeviceProvider but don't call each other. No `iot.converge` step.
**Alternatives:**
- Scenarios trigger desiredstate goals — an iot.converge step submits a DesiredStateGraph and awaits convergence. More reliable for continuous state maintenance but adds coupling and latency (reconciliation loop interval)
- Scenario YAML compiles to DesiredStateGraph — impedance mismatch between imperative workflow and declarative graph
**Rationale:** First-principles analysis: device commands are fire-and-verify. For one-shot orchestration, CommandResult=SENT is sufficient, with retry decorators for verification. Continuous convergence is a separate concern — submit desiredstate goals outside the scenario if needed. Coupling the two adds latency (30s resync interval) and complexity for most use cases.
**Trade-offs:** No continuous convergence within scenarios. If a device drifts during a scenario, the scenario doesn't self-correct — it relies on retry decorators per step. This is acceptable because scenarios are finite workflows, not monitoring systems.
**Sources:** `IoTNodeProvisioner.java` (desiredstate provisioner), `StructuralStepEvaluator.java` (step runtime), first-principles analysis of fire-and-verify pattern
**Exploration:** deep-analysis
**Status:** captured

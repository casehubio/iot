## D1: Input format — inline config with registry resolution, plus preset references

**Choice:** Config-only shorthand as the primary inline format (deviceId → capabilities map), with metadata resolved from DeviceRegistry at runtime. Also support preset references (`preset: <name>`) via IoTPresetResolver. Detection: if `data.containsKey("preset")` → preset mode, otherwise inline config mode.
**Alternatives:**
- Full IoTGoals format — wrong abstraction level for scenarios; forces authors to redeclare what the system already knows (deviceClass, label, physical). IoTGoalLoader could parse it with zero new code, but the ergonomic cost is too high for scenario YAML.
- Both formats with detection — YAGNI; the full format has no scenario use case
**Rationale:** Scenarios express intent, not system implementation details. The author knows device IDs and desired state; the system knows device metadata. Presets provide an even higher abstraction level for named configurations.
**Trade-offs:** Inline config requires a live DeviceRegistry at step execution time. Typos in device IDs fail at runtime, not at parse time.
**Sources:** IoTDeviceGoal.java, IoTPresetResolver.java, issue #126 example YAML
**Exploration:** quick
**Status:** captured

## D2: Reconciliation execution model — one-shot, following applyPreset() pattern

**Choice:** One-shot reconciliation: compile → readActual → plan → provision. Same pattern as `DefaultIoTPresetApi.applyPreset()`.
**Alternatives:**
- ReconciliationLoop integration — rejected. The loop is a per-tenant continuous monitoring system. It manages lifecycle (start/stop), drift detection, fault policies, and periodic resync — all irrelevant for point-in-time convergence. Additionally, ReconciliationLoop lives in `casehub-desiredstate-runtime-core`, which is not a compile dependency of the IoT desiredstate module, and has no dynamic listener registration API.
**Rationale:** `applyPreset()` already solves the identical problem — synchronous desired-state convergence — using one-shot. Drift/fault policies are continuous monitoring concerns: "should we re-reconcile when state drifts later?" A scenario step is a point-in-time action; once it converges (or fails), it's done. One-shot is inherently synchronous — no async-to-sync bridge needed.
**Trade-offs:** No automatic retry on transient failures (the scenario engine handles retries at the step level). No CloudEvent emission (can be added independently if needed).
**Sources:** DefaultIoTPresetApi.applyPreset() (webapp/app/service), TransitionPlanner, IoTNodeProvisioner
**Exploration:** quick
**Status:** revised (was: loop integration — reversed after decision review found applyPreset() precedent and API incompatibility)

## D3: No async-to-sync bridge needed

**Choice:** Dropped. One-shot reconciliation is inherently synchronous.
**Rationale:** D3 was solving a problem (async ReconciliationLoop → sync DeliveryHandler) that only exists with loop integration. With one-shot, the handler calls compile/plan/provision directly and returns.
**Depends on:** D2 (one-shot reconciliation)
**Exploration:** quick
**Status:** revised (was: ReconciliationListener + CountDownLatch — moot after D2 reversal)

## D4: Module placement — scenario module

**Choice:** Add desiredstate dependencies to casehub-iot-scenario. DesiredStateDeliveryHandler lives alongside IoTCommandPlugin and IoTStatePlugin.
**Alternatives:**
- Desiredstate module — mixes scenario concern into desiredstate; would require adding pages-scenario dependency
- New bridge module — a module for one class; scenario module is already the integration point
- Webapp — couples a reusable handler to the app tier
**Rationale:** The scenario module is the IoT integration point for all scenario delivery. The handler implements a different SPI (DeliveryHandler from pages) than the plugins (@Plugin from platform), but the module's purpose — "IoT scenario integration" — covers both.
**Trade-offs:** Scenario module gains 3 new compile dependencies: `casehub-pages-scenario` (DeliveryHandler SPI), `casehub-iot-desiredstate` (compiler, adapter, provisioner), `casehub-desiredstate` runtime (TransitionPlanner, DefaultDesiredStateGraphFactory). This broadens the module from 2 deps to 5. Acceptable — all are within the casehub ecosystem and the module is IoT-specific.
**Sources:** scenario/pom.xml, desiredstate/pom.xml, DefaultIoTPresetApi imports
**Exploration:** quick
**Status:** captured (revised rationale — acknowledges dependency cost per review R1-05)

## D5: Handler structure — single handler with format detection

**Choice:** Single DesiredStateDeliveryHandler class with internal `if (data.containsKey("preset"))` branching. No strategy pattern.
**Alternatives:**
- Strategy pattern (GoalResolver interface) — adds abstraction for two branches of an if/else
**Rationale:** Two input modes don't justify an interface. The detection is trivial and the handler body is small.
**Trade-offs:** If more input modes are added later, this could grow — but YAGNI.
**Sources:** DeliveryHandler SPI (pages/backend/scenario)
**Exploration:** quick
**Status:** captured

## D6: Error handling — fail-fast on unknown devices

**Choice:** If any device ID in the inline config doesn't exist in DeviceRegistry, fail the step immediately with an error listing the unknown IDs.
**Alternatives:**
- Skip unknown devices — more lenient but masks typos and misconfiguration
**Rationale:** Scenarios should fail clearly on misconfiguration. Silent skips create hard-to-debug partial-application bugs.
**Trade-offs:** A scenario referencing a device that hasn't been provisioned yet will fail rather than partially converge.
**Sources:** DeviceRegistry.findById(), io.casehub.pages.scenario.StepOutcome (pages StepOutcome, not the desiredstate StepOutcome — different types with same name)
**Exploration:** quick
**Status:** captured

## D7: Tenancy resolution — from platform config property

**Choice:** Inject `casehub.iot.tenancy-id` config property via CDI. Same source as everywhere else in the IoT stack.
**Alternatives:**
- `DeliveryContext.config("tenancyId")` — convention-based, not typed
- Embedded in the data block — per-step tenancy override
**Rationale:** Platform convention: "Single tenancy property: `casehub.iot.tenancy-id` — never per-module `tenancyId()` in `@ConfigMapping`." No multi-tenancy requirement in the issue.
**Trade-offs:** No per-step tenancy override. If needed later, add `data.tenancyId` as an optional override.
**Sources:** CLAUDE.md key rules, DefaultIoTPresetApi @ContextParam pattern
**Exploration:** quick
**Status:** captured

## D8: Partial success semantics — attempt all, any failure = step failure

**Choice:** Attempt all device provisions. If any fail, return `StepOutcome` with `success=false`. Result map includes `provisioned` count, `failed` count, and list of failed device IDs with reasons.
**Alternatives:**
- Stop at first failure — simpler but leaves remaining devices unconverged
- Any success = step success — misleading; the step didn't achieve its goal
**Rationale:** Maximize convergence progress (attempt everything), but report accurately (step failed if any device failed). The result map gives the scenario author enough information for error handling or retry decisions.
**Trade-offs:** A step with 4/5 devices converged still reports failure. The scenario engine's retry semantics handle retries — the handler doesn't need to.
**Depends on:** D2 (one-shot — each provision is synchronous)
**Sources:** DefaultIoTPresetApi.applyPreset() (counts provisioned/skipped), io.casehub.pages.scenario.StepOutcome contract
**Exploration:** quick
**Status:** captured

## D9: Inline config uses physical=false

**Choice:** When building IoTDeviceGoal from inline config, always set `physical=false`. For preset references, use whatever the preset declares.
**Alternatives:**
- Always physical=true — would create PhysicalDeviceSpec nodes with HumanGating.ALL, which blocks automated execution
- Override preset physical to false — surprising behavior; presets should declare their own semantics
**Rationale:** Scenario steps configure existing devices — they set capabilities, they don't provision new hardware. `physical=false` produces only DeviceConfigSpec nodes, which is correct for "set this device's state." Presets used in scenarios should be authored with `physical: false` for automated convergence; if they have `physical: true`, the PhysicalDeviceSpec nodes will provision without gating in one-shot mode (no plan approval gate outside the loop).
**Trade-offs:** None meaningful — physical=false matches the use case.
**Sources:** IoTGoalCompiler.compile() physical branching, IoTDeviceGoal defaults
**Exploration:** quick
**Status:** captured

## D10: No shared service extraction — YAGNI

**Choice:** Inline the compile → readActual → plan → provision pipeline in the handler. Do not extract a shared service with applyPreset().
**Alternatives:**
- Extract IoTConvergenceService into desiredstate module — clean reuse, but requires adding casehub-desiredstate runtime as a compile dep to the desiredstate module (currently test-only)
**Rationale:** Two callers with ~8 shared lines. The handler and applyPreset() have different input sources, different output types, and different error handling. Extracting would change the desiredstate module's dependency character (api-only → api+runtime). If a third caller appears, extract then.
**Trade-offs:** Small code duplication between handler and applyPreset(). Acceptable — the duplicated part is a straightforward pipeline with no branching logic.
**Depends on:** D2 (one-shot), D4 (module placement)
**Sources:** DefaultIoTPresetApi.applyPreset()
**Exploration:** quick
**Status:** captured

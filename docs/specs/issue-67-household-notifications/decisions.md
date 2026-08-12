# Decisions — Issue #67: Household Notifications via Platform Subscription Engine

## D1: Event scope

**Choice:** Situation activations only
**Alternatives:**
- All three categories (situations + device state changes + case lifecycle) — rejected after review: device state changes are high-frequency telemetry that would flood the subscription engine; case lifecycle events are a platform concern, not IoT-specific
- Situations + device state changes — device state changes already have CDI `@ObservesAsync StateChangeEvent` for in-JVM consumers
**Rationale:** Situations are the curated interpretation of raw device telemetry — the correct abstraction for user-facing notifications. Device state changes keep their existing CDI mechanism. Case lifecycle subscriptions belong in casehub-engine as a platform-level DataSource.
**Trade-offs:** Users who want device-level subscriptions need a separate future design with volume engineering
**Exploration:** quick
**Revised by:** R1-01, R1-02 (decision review round 1)
**Status:** revised

## D2: Notification delivery mechanism

**Choice:** Eliminate the notification worker. Fire IoTSituationEvent as a SubscribableEvent at situation detection time. Remove HouseholdNotificationWorkerFunction and the household-notification worker from case descriptors.
**Alternatives:**
- Worker fires into DataSource — creates redundant notification path; subscription engine delivery is asynchronous so ordering guarantee is false
- Remove worker, observe case events — wrong event level; case lifecycle is platform concern
**Rationale:** The subscription engine IS the notification system — it handles channels, timing, suppression, digest. The case flow should model only ordered execution steps: safety commands → human ack. Notification is an independent subscription concern fired at situation detection.
**Trade-offs:** Case flow no longer has explicit notification step; notification delivery timing is subscription-engine-controlled
**Exploration:** quick
**Revised by:** R1-03 (decision review round 1)
**Status:** revised

## D3: Module placement

**Choice:** IoTSituationEvent record in iot-api (L1/L2 — shared domain vocabulary). CDI observer that fires events into the DataSource in webapp. Delete HouseholdNotificationWorkerFunction from webapp-api.
**Alternatives:**
- Event types in webapp-api — layer inversion for casehub-life which depends on iot-api, not webapp-api
- New iot-notifications module — premature
**Rationale:** iot-api is the shared vocabulary module. Event types are domain concepts, not orchestration logic. Downstream consumers (casehub-life) already depend on iot-api.
**Trade-offs:** iot-api gains a dependency on casehub-platform-api for SubscribableEvent (verify pom.xml)
**Exploration:** quick
**Revised by:** R1-05 (decision review round 1)
**Status:** revised

## D4: DataSource topology and tenancy

**Choice:** Platform-global DataSource (path: /iot/situations, tenancyId: PLATFORM_TENANT_ID). Tenant isolation enforced by the subscription engine via SubscribableEvent.tenancyId() on each event. Registered at @Startup in webapp.
**Alternatives:**
- Tenant-scoped DataSource — requires dynamic registration per tenant onboarding; single bridge deployment serves one tenant but webapp may serve multiple
- Single DataSource with no tenant model — ignores DataSourceRegistry's tenant-scoped design
**Rationale:** Platform-global DataSources are visible to all tenants (DataSourceRegistry.resolve priority lookup). Each event carries its own tenancyId() for filtering. No per-tenant registration lifecycle needed.
**Trade-offs:** All tenants' situation events flow through one DataSource — subscription engine must enforce tenant boundaries at event evaluation time
**Exploration:** quick
**Addresses:** R1-04 (tenant isolation), R1-06 (registration lifecycle)
**Status:** captured

## D5: Event type hierarchy

**Choice:** Single record IoTSituationEvent implements SubscribableEvent in iot-api. No sealed interface — string-based type() discrimination is sufficient for the subscription engine.
**Alternatives:**
- Sealed interface with multiple records — over-constrains; subscription engine uses string discrimination, not pattern matching
- Generic event with Map payload — loses type safety
**Rationale:** One event type for now (situations). If device-level or provider-level events are added later, they're independent records implementing SubscribableEvent directly. Additive, no refactoring needed.
**Trade-offs:** No compile-time exhaustiveness check across event types — acceptable since the subscription engine routes by string
**Exploration:** quick
**Revised by:** R1-05 (decision review round 1)
**Status:** revised

## D6: Failure semantics

**Choice:** Notification is best-effort. The CDI observer that fires situation events into the DataSource catches and logs failures. Case flow is unaffected — it no longer has a notification step.
**Alternatives:**
- Required delivery — subscription engine unavailability stalls nothing because notification is decoupled from the case flow
**Rationale:** Safety-critical case flows must not stall because the subscription engine is temporarily unavailable. With the worker eliminated, the case flow is immune. The observer is fire-and-forget.
**Trade-offs:** A missed notification is silently logged, not retried. Platform digest/retry mechanisms handle recovery.
**Exploration:** quick
**Addresses:** R1-07 (failure semantics)
**Status:** captured

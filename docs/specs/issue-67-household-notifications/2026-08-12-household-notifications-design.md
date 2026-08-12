# Household Notifications via Platform Subscription Engine

**Issue:** casehubio/iot#67
**Date:** 2026-08-12
**Status:** Draft

---

## 1. Purpose

Wire IoT situation activations into the platform subscription engine so
household members can subscribe to alerts (temperature breach, motion
detection, unexpected unlock, etc.) and receive notifications via their
preferred channels (Slack, SMS, push, email) — without IoT building
custom notification routing.

Replaces the stub `HouseholdNotificationWorkerFunction` (mock success)
with a real integration that leverages the platform's subscription,
dispatch, and delivery infrastructure.

---

## 2. Scope

**In scope:**
- `IoTSituationEvent` implementing `SubscribableEvent` in iot-api
- CDI observer in webapp that fires situation events into the subscription engine
- Platform-global DataSource registration at `iot/situations`
- Removal of `HouseholdNotificationWorkerFunction` and the `household-notification`
  worker from all case descriptors
- Both `TRIGGERED` and `RESOLVED` situation change types
- Unit and integration tests

**Out of scope:**
- Device state change subscriptions — high-frequency telemetry, existing CDI
  `@ObservesAsync StateChangeEvent` mechanism is sufficient for in-JVM consumers.
  Device-level subscriptions need separate design with volume engineering.
- Case lifecycle subscriptions — platform concern, not IoT-specific.
  `WorkItemLifecycleEvent` already implements `SubscribableEvent` at
  casehub-work level. Case lifecycle DataSource belongs in casehub-engine.
- Subscription UI — users manage subscriptions via the platform REST API
  (`SubscriptionResource`). No IoT-specific UI in this issue.
- Connector bridge integration (casehubio/connectors#86) — delivery channel
  setup is a deployment concern, not an IoT code change.

---

## 3. Architecture

### 3.1 Components

| Component | Module | Purpose |
|-----------|--------|---------|
| `IoTSituationEvent` | iot-api | Class implementing `SubscribableEvent` with `@JsonIgnore` on `tenancyId()`. Domain vocabulary — shared with all IoT consumers. |
| `IoTSituationEventObserver` | webapp | CDI `@ApplicationScoped` bean. `@ObservesAsync SituationChangeEvent` (from `casehub-ras-api`), handles `ChangeType.TRIGGERED` and `ChangeType.RESOLVED`, constructs `IoTSituationEvent` from event fields, pushes into DataSource. Best-effort: catches and logs failures. |
| `IoTNotificationDataSourceRegistrar` | webapp | `@Observes StartupEvent` — registers a platform-global DataSource at path `iot/situations` with `PLATFORM_TENANT_ID`. |
| Case descriptor cleanup | webapp-api | Remove `HouseholdNotificationWorkerFunction`, remove `household-notification` worker from `SafetyAlertCaseDescriptor`, `SecurityAlertCaseDescriptor`, and `HvacAnomalyCaseDescriptor`. |

### 3.2 Data Flow

```
Ganglion detects situation
  → RAS fires SituationChangeEvent (CDI async event, from casehub-ras-api)
    → IoTSituationEventObserver receives it (handles TRIGGERED and RESOLVED)
      → Constructs IoTSituationEvent from correlationKey, situationId, tenancyId, metadata
        → dataSource.add(event)
          → Subscription engine evaluates against active subscriptions
            → Matched subscriptions → notification-dispatch → delivery channels
```

The case flow is independent and decoupled:

```
Ganglion detects situation
  → Case created via case descriptor
    → SafetyAlert: device-command-dispatch → human-acknowledgement
    → SecurityAlert: device-command-dispatch → camera-activation → human-decision
    → HvacAnomaly: device-command-dispatch → human-review
```

Notification fires at detection time. Case response proceeds independently.
Neither blocks the other.

### 3.3 Tenant Isolation

The DataSource is registered as platform-global (`tenancyId = PLATFORM_TENANT_ID`),
making it visible to all tenants via `DataSourceRegistry.resolve()` priority
lookup. Each `IoTSituationEvent` carries its own `tenancyId()` from the
device/situation context. The subscription engine enforces tenant boundaries
at event evaluation time — subscriptions only match events where
`event.tenancyId()` matches the subscriber's tenant.

---

## 4. IoTSituationEvent

Class in `iot-api` (not a record — `@JsonIgnore` on `tenancyId()` matches
the `WorkItemLifecycleEvent` pattern to prevent tenant ID leaking into
serialized payloads):

```java
public class IoTSituationEvent implements SubscribableEvent {

    private final String situationId;
    private final String changeType;     // "triggered" or "resolved"
    private final String deviceId;
    private final String tenancyId;
    private final Map<String, Object> metadata;
    private final Instant occurredAt;

    // constructor, getters

    @Override
    public String type() {
        return "io.casehub.iot.situation." + changeType + "." + situationId;
    }

    @Override
    @JsonIgnore
    public String tenancyId() {
        return tenancyId;
    }
}
```

### 4.1 Field Extraction from SituationChangeEvent

| IoTSituationEvent field | Source |
|------------------------|--------|
| `situationId` | `SituationChangeEvent.situationId()` |
| `changeType` | `SituationChangeEvent.changeType()` — `TRIGGERED` → `"triggered"`, `RESOLVED` → `"resolved"` |
| `deviceId` | `SituationChangeEvent.correlationKey()` — extract after `"device/"` prefix |
| `tenancyId` | `SituationChangeEvent.tenancyId()` |
| `metadata` | `SituationChangeEvent.metadata()` — the situation's metadata map |
| `occurredAt` | `SituationChangeEvent.context().lastTriggered()` — the time the situation entered TRIGGERED state |

### 4.2 Event Type Naming

Convention: reverse-DNS with dot segments per `SubscribableEvent` docs.
The `changeType` and `situationId` form the final segments.

**Triggered examples:**

| Ganglion | Event type |
|----------|------------|
| TemperatureThresholdGanglion | `io.casehub.iot.situation.triggered.temperature-threshold` |
| DeviceUnavailableGanglion | `io.casehub.iot.situation.triggered.device-unavailable` |
| MotionAtTimeGanglion | `io.casehub.iot.situation.triggered.motion-at-time` |
| LockStateGanglion | `io.casehub.iot.situation.triggered.lock-state` |
| PowerAnomalyGanglion | `io.casehub.iot.situation.triggered.power-anomaly` |
| SustainedTemperatureRiseGanglion | `io.casehub.iot.situation.triggered.sustained-temperature-rise` |
| MultiRoomMotionGanglion | `io.casehub.iot.situation.triggered.multi-room-motion` |

**Resolved examples:** Same pattern with `resolved` — e.g.,
`io.casehub.iot.situation.resolved.temperature-threshold`.

Users subscribe to `io.casehub.iot.situation.triggered.*` for alerts,
`io.casehub.iot.situation.resolved.*` for all-clears, or specific
situation types for targeted notifications.

New ganglia automatically get subscribable event types — no registration
change or new code needed beyond the ganglion itself.

---

## 5. IoTSituationEventObserver

CDI observer in webapp:

```java
@ApplicationScoped
public class IoTSituationEventObserver {

    private final DataSourceRegistry dataSourceRegistry;
    private static final io.casehub.platform.api.path.Path IOT_SITUATIONS_PATH =
        io.casehub.platform.api.path.Path.of("iot", "situations");

    // @ObservesAsync SituationChangeEvent (from casehub-ras-api)
    // Handles ChangeType.TRIGGERED and ChangeType.RESOLVED
    // Ignores SUPPRESSED, DISCARDED, DISMISSED
    // Extracts deviceId from correlationKey ("device/<deviceId>" → "<deviceId>")
    // Constructs IoTSituationEvent(situationId, changeType, deviceId, tenancyId, metadata, occurredAt)
    // Resolves DataSource via dataSourceRegistry.resolveSource(IOT_SITUATIONS_PATH, PLATFORM_TENANT_ID)
    // Pushes via dataSource.add(event)
    // Best-effort: catches and logs failures — never throws
}
```

**Failure semantics:** Best-effort fire-and-forget. The observer catches
all exceptions from `DataSource.add()` and logs at WARN level. Safety-critical
case flows are unaffected — notification is decoupled from case execution.
Platform digest/retry mechanisms handle delivery recovery.

---

## 6. DataSource Registration

```java
@ApplicationScoped
public class IoTNotificationDataSourceRegistrar {

    // @Observes StartupEvent
    // Registers DataSource via DataSourceDescriptor:
    //   path: Path.of("iot", "situations")
    //   tenancyId: PLATFORM_TENANT_ID
    //   objectType: ObjectType<IoTSituationEvent> implementation
    //     - matches(obj): obj instanceof IoTSituationEvent
    //     - getTypeKey(): "io.casehub.iot.situation"
    //   endpointPath: Path.of("iot", "situations") (same as path)
    //   acceptedEventTypes: Set.of("io.casehub.iot.situation.triggered",
    //                              "io.casehub.iot.situation.resolved")
    //     — prefix-based; subscription engine matches event type() strings
    //       that start with these prefixes
    //   properties: Map.of()
    //   marshallerKeys: Map.of()
    // Idempotent — re-registration returns existing instance
}
```

`acceptedEventTypes` uses the two top-level prefixes (`triggered`, `resolved`).
The subscription engine's `EventTypeObjectType` handles prefix matching —
study the existing `WorkItemLifecycleEvent` registration for the established
pattern at implementation time.

Registration uses `PLATFORM_TENANT_ID` for visibility to all tenants.
`DataSourceRegistry.register()` is idempotent — safe across restarts.

---

## 7. Case Descriptor Cleanup

### 7.1 Delete

- `HouseholdNotificationWorkerFunction.java` (webapp-api)
- `HouseholdNotificationWorkerFunctionTest.java` (webapp-api)

### 7.2 Modify

Remove the `household-notification` worker from all three descriptors:

| Descriptor | Before | After |
|-----------|--------|-------|
| `SafetyAlertCaseDescriptor` | `[device-command-dispatch, household-notification, human-acknowledgement]` | `[device-command-dispatch, human-acknowledgement]` |
| `SecurityAlertCaseDescriptor` | `[device-command-dispatch, camera-activation, household-notification, human-decision]` | `[device-command-dispatch, camera-activation, human-decision]` |
| `HvacAnomalyCaseDescriptor` | `[device-command-dispatch, household-notification, human-review]` | `[device-command-dispatch, human-review]` |

---

## 8. Dependencies

iot-api needs `SubscribableEvent` from `casehub-platform-api`. Verify at
implementation time whether this is already available transitively (iot-api
depends on casehub-platform-api for `CurrentPrincipal`, `IoTRoles`). If
not, add explicit dependency.

webapp needs `DataSourceRegistry`, `DataSource`, `DataSourceDescriptor`,
`ObjectType`, and `Path` from `casehub-platform-api` — likely already
available through existing platform dependencies. Verify at implementation.

---

## 9. Testing

### 9.1 Unit Tests

| Test | What it verifies |
|------|-----------------|
| `IoTSituationEventTest` | `type()` returns correct format for triggered/resolved, `tenancyId()` returns constructor value, `@JsonIgnore` on tenancyId |
| `IoTSituationEventObserverTest` | Mock DataSource, verify event pushed with correct fields for TRIGGERED; verify RESOLVED handled; verify SUPPRESSED/DISCARDED/DISMISSED ignored; verify failure caught and logged (no rethrow) |
| `SafetyAlertCaseDescriptorTest` | Workers list is `[device-command-dispatch, human-acknowledgement]` — no household-notification |
| `SecurityAlertCaseDescriptorTest` | Workers list is `[device-command-dispatch, camera-activation, human-decision]` — no household-notification |
| `HvacAnomalyCaseDescriptorTest` | Workers list is `[device-command-dispatch, human-review]` — no household-notification |

### 9.2 Integration Tests

| Test | What it verifies |
|------|-----------------|
| `IoTNotificationIntegrationTest` (`@QuarkusTest`) | Fire `SituationChangeEvent` with `TRIGGERED` → verify `IoTSituationEvent` arrives in DataSource with correct `type()`, `tenancyId()`, `situationId`, `deviceId`. Fire `RESOLVED` → verify resolved event arrives. |
| DataSource registration | Verify DataSource is registered at startup, resolvable at `Path.of("iot", "situations")` |

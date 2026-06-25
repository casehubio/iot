# Design: CameraDevice, Bridge Health, Bridge Error Handling

**Branch:** `issue-030-device-command-dispatch`
**Covers:** #28, #29, #31
**Date:** 2026-06-25

---

## #28 — CameraDevice (XS)

Add `CAMERA` to the device type system following the established leaf device pattern.

### Design rationale — Matter alignment

The Matter Device Type Library (1.2+) defines Camera (0x0101) and Occupancy Sensor (0x0107) as separate device types. A physical camera with a built-in motion sensor is two logical devices. Provider discovery should surface both independently — `CameraDevice` for streaming state, `PresenceSensor` for motion detection. This avoids importing HA's physical-device-centric conflation into the normalized type system.

### API changes (`casehub-iot-api`)

**DeviceClass enum** — add `CAMERA` value.

**CameraDevice** — new concrete `DeviceEntity` subclass:
- `isStreaming` (boolean) — whether the camera is actively streaming or recording
- Capabilities map: `available`, `isStreaming`
- Concrete `Builder` (leaf type — no vendor supplements yet, not extensible via `AbstractBuilder`)
- Static `builder()` factory
- `@JsonDeserialize(builder = CameraDevice.Builder.class)` — required for Jackson deserialization

**DeviceTypeIdResolver** — register `CameraDevice` in both `REGISTRY` and `FALLBACK` maps.

### Testing changes (`casehub-iot-testing`)

- `CameraHandler implements DeviceTypeHandler` — `typeName()` returns `"camera"`, parses `streaming` (boolean) from YAML
- Register in `META-INF/services/io.casehub.iot.testing.DeviceTypeHandler`
- `Fixtures.securityCamera()` — fixture device with `isStreaming=false`
- Add to `Fixtures.standardHome()` list (grows from 10 to 11 devices)
- Add `security_camera` entry to `standard-home.yaml` with `type: camera`
- Update `EnumTest` assertion from `hasSize(10)` to `hasSize(11)`

### HomeAssistant provider changes

**HomeAssistantEntityMapper** — add `"camera"` case to the domain switch:
- `state == "streaming"` or `state == "recording"` → `isStreaming=true`
- All other states (`"idle"`, etc.) → `isStreaming=false`
- `state == "unavailable"` or `"unknown"` → handled by existing availability logic (same pattern as other domains)

### OpenHAB provider changes

**ResolvedDeviceFields** — add `Boolean streaming` field. Builder gets `streaming(Boolean v)` setter. `withAvailable()` copy method updated to include the new field.

**OpenHabEntityMapper.resolveDeviceClass()** — add `if ("Camera".equals(tag)) return DeviceClass.CAMERA;` (OpenHAB 3.x+ Equipment semantic tag).

**OpenHabEntityMapper.resolveFromEquipment()** — add `case CAMERA -> {}` to the `deviceClass` switch (line 104). Without this, camera Equipment resolved by `resolveDeviceClass()` hits `default → return null` and is silently dropped before reaching `OpenHabDeviceBuilder`. No-op body is correct — no standard OH channels map to streaming state; the builder's `streaming` field stays null and `buildCamera()` defaults it to false.

**OpenHabThingResolver.mapCategory()** — add `case "camera" -> DeviceClass.CAMERA` to the category switch. Without this, camera Things hit `default → null` and are dropped. `populateFields()` does not need a CAMERA branch — it dispatches by channel `itemType`, not `deviceClass`, so camera channels iterate without matching camera-specific fields.

**OpenHabDeviceBuilder.build()** — add `case CAMERA -> buildCamera(f)`:
- `isStreaming = f.streaming() != null ? f.streaming() : false`
- Returns `CameraDevice` (no OpenHAB-specific supplement fields)

---

## #29 — Bridge Health Endpoint (S)

Add observability to `bridge-server` via Quarkus SmallRye Health.

### Dependency

Add `quarkus-smallrye-health` to `bridge-server/pom.xml`. This enables the standard `/q/health`, `/q/health/ready`, and `/q/health/live` endpoints.

### BridgeReadinessCheck

`@Readiness @ApplicationScoped` bean implementing `HealthCheck`.

**Why @Readiness only, not @Liveness:** Zero connected agents is a valid operational state (scheduled connections, maintenance windows), not a process failure. @Liveness failures cause orchestrators to restart the pod — restarting won't make agents appear and would cause restart loops. Liveness relies on Quarkus's built-in check (process is running).

- Injects `BridgeConnectionRegistry` and `BridgeDeviceProvider`
- **Status:** `UP` when `registry.hasAnyConnection()` is true; `DOWN` otherwise
- **Data fields:**
  - `connectedAgents` — count from `registry.connectedTenancies().size()`
  - `knownAgents` — count from `registry.knownTenancies().size()` (requires exposing this method)
Connection data (`connectedAgents`, `knownAgents`) is sufficient for readiness determination. Operational metrics (`totalDevices`, `pendingCommands`) belong in a metrics/monitoring endpoint, not a health check — mixing health with load data overloads the readiness signal.

### BridgeConnectionRegistry additions

- `knownTenancies()` — public accessor for the private `knownTenancies` set (currently `private final Set<String>`, no accessor exists)

### Test

`BridgeReadinessCheckTest` — verify UP/DOWN transitions based on connection state, verify data fields are populated correctly.

---

## #31 — Bridge Error Handling Fix (S)

Fix `BridgeCloudClient.handleCommand()` so provider exceptions return `FAILED` instead of silently timing out.

### Current behaviour (bug)

```java
private void handleCommand(BridgeMessage.Command cmd, WebSocketClientConnection connection) {
    commandDispatcher.dispatch(cmd.command())
            .subscribe().with(
                    result -> {
                        // ... serialize and send CommandResponse
                    },
                    failure -> LOG.errorf(failure, "Command dispatch failed for correlation %s",
                            cmd.correlationId()));  // ← logs only, no response sent
}
```

When `dispatch()` fails, no `CommandResponse` is sent. Cloud waits 30s → `TIMEOUT`.

### Fix

```java
private void handleCommand(BridgeMessage.Command cmd, WebSocketClientConnection connection) {
    Uni<CommandResult> result;
    try {
        result = commandDispatcher.dispatch(cmd.command());
    } catch (Exception e) {
        LOG.errorf(e, "Command dispatch threw synchronously for correlation %s",
                cmd.correlationId());
        sendResponse(cmd, CommandResult.FAILED, connection);
        return;
    }
    result.subscribe().with(
                    r -> sendResponse(cmd, r, connection),
                    failure -> {
                        LOG.errorf(failure, "Command dispatch failed for correlation %s",
                                cmd.correlationId());
                        sendResponse(cmd, CommandResult.FAILED, connection);
                    });
}

private void sendResponse(BridgeMessage.Command cmd, CommandResult result,
                           WebSocketClientConnection connection) {
    var response = new BridgeMessage.CommandResponse(
            cmd.tenancyId(), Instant.now(), cmd.correlationId(), result);
    try {
        connection.sendTextAndAwait(mapper.writeValueAsString(response));
    } catch (JsonProcessingException e) {
        LOG.errorf("Failed to serialize command response: %s", e.getMessage());
    }
}
```

Three changes:
1. Extract `sendResponse()` helper — deduplicates success/failure response paths
2. Failure callback sends `CommandResponse(FAILED)` instead of log-only
3. Defensive try-catch around `dispatch()` call — WebSocket handler is a system boundary; the SPI contract does not guarantee non-throwing dispatch, and unhandled exceptions propagate to the framework with no response sent

### Tests

- Verify that when `provider.dispatch()` fails with exception, a `CommandResponse(FAILED)` is sent back over the WebSocket
- Verify that a synchronous exception from the dispatcher also sends `CommandResponse(FAILED)`
- Verify serialization failure case is handled gracefully (log-only — correct since nothing more can be sent)

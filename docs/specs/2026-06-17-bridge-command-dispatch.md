# Bridge Command Dispatch — Design Spec

**Date:** 2026-06-17
**Issue:** casehubio/iot#22
**Parent spec:** `docs/superpowers/specs/2026-06-16-bridge-runtime-design.md` (§ BridgeDeviceProvider Behaviour, line 310)

---

## Overview

Wire the stubbed `BridgeDeviceProvider.dispatch()` to send commands over WebSocket to the correct bridge agent and correlate responses. The agent side is already complete — this is server-side only, plus one agent-side cleanup (redundant prefix strip removal).

---

## Design

### Dispatch Flow

1. `dispatch(DeviceCommand command)` extracts `tenancyId` from `command.targetDeviceId()` via `DeviceIdUtils.extractTenancyId()`
2. Looks up `WebSocketConnection` from `BridgeConnectionRegistry.getSession(tenancyId)` — returns `FAILED` if absent
3. Resolves `correlationId`: uses `command.correlationId()` if non-null, otherwise generates `UUID.randomUUID().toString()`
4. Strips tenancy prefix from `targetDeviceId` to get the local device ID via `DeviceIdUtils.stripPrefix()`
5. Creates a new `DeviceCommand` with the local ID and the resolved `correlationId` (preserving action, parameters, dispatchedBy)
6. Wraps in `BridgeMessage.Command(tenancyId, Instant.now(), correlationId, localCommand)`
7. Serializes via `ObjectMapper` — if serialization fails, returns `Uni.createFrom().item(CommandResult.FAILED)` immediately
8. Inside `Uni.createFrom().emitter()`: registers composite key `tenancyId/correlationId → UniEmitter` in the pending map, then sends via `connection.sendText(json)` (non-blocking `Uni<Void>` variant). On send failure, removes the pending entry and completes the emitter with `CommandResult.FAILED`
9. Returns the `Uni<CommandResult>` with timeout and cleanup chained

### Non-Blocking Send

The Quarkus `Sender` API provides two methods:
- `sendText(String)` → `Uni<Void>` — non-blocking, safe on any thread
- `sendTextAndAwait(String)` → `void` — blocks, "should never be called on an event loop thread" (Quarkus javadoc)

`dispatch()` callers may be on a Vert.x event loop. The spec requires `sendText()` (the `Uni<Void>` variant), chained inside the emitter callback:

```java
String compositeKey = tenancyId + "/" + correlationId;

return Uni.createFrom().<CommandResult>emitter(emitter -> {
    pendingCommands.put(compositeKey, emitter);
    connection.sendText(json).subscribe().with(
        success -> { /* waiting for agent response */ },
        failure -> {
            pendingCommands.remove(compositeKey);
            emitter.complete(CommandResult.FAILED);
        }
    );
})
.ifNoItem().after(Duration.ofSeconds(config.commandTimeoutSeconds()))
.recoverWithItem(CommandResult.TIMEOUT)
.onTermination().invoke(() -> pendingCommands.remove(compositeKey));
```

Serialization (`ObjectMapper.writeValueAsString()`) happens eagerly before the emitter — a `JsonProcessingException` returns `FAILED` immediately without entering the pending map.

### Response Correlation

`BridgeDeviceProvider.completeCommand(String tenancyId, String correlationId, CommandResult result)`:
- Looks up composite key `tenancyId/correlationId` in the pending map
- Removes the entry and fires the `UniEmitter` with the result
- No-op if key is not found (response arrived after timeout cleanup, or tenancy mismatch — composite key prevents cross-tenancy completion structurally)

`BridgeWebSocketEndpoint.onMessage()` `CommandResponse` case calls `provider.completeCommand(tenancyId, cr.correlationId(), cr.result())` — passing the connection's header-derived tenancy ID. The endpoint already injects the concrete `BridgeDeviceProvider` type (not the `DeviceProvider` SPI), so no injection change is needed.

### Pending Command Map

`ConcurrentHashMap<String, UniEmitter<? super CommandResult>>` in `BridgeDeviceProvider`, keyed by composite `tenancyId/correlationId`. Thread-safe: emitters are registered before send (inside the emitter callback), removed on completion, send failure, or timeout via `onTermination()`. The `onTermination()` remove is idempotent — `ConcurrentHashMap.remove()` is a no-op if the entry was already removed by `completeCommand()` or send failure.

The composite key makes cross-tenancy completion structurally impossible — a response from agent A cannot complete a command dispatched to agent B. No runtime validation or wrapper record needed.

### Timeout

`Uni.ifNoItem().after(Duration.ofSeconds(timeout)).recoverWithItem(CommandResult.TIMEOUT)` with cleanup via `onTermination().invoke(() -> pendingCommands.remove(compositeKey))`. The `onTermination()` handler fires on completion (response received), timeout, or cancellation — covers all exit paths.

### Configuration

`BridgeServerConfig` already exists at `bridge-server/src/main/java/io/casehub/iot/bridge/server/BridgeServerConfig.java` with `commandTimeoutSeconds()` (default 30s). No new class needed.

### Agent-Side Cleanup: Remove Redundant Prefix Strip

The wire contract (parent spec §Namespacing) says outbound commands carry local IDs: `{tenancyId}/light.kitchen → light.kitchen` (stripped server-side before sending). The agent's `BridgeCommandDispatcher.dispatch()` also calls `DeviceIdUtils.stripPrefix()` — this is redundant and obscures the wire contract.

Remove the `stripPrefix()` call from `BridgeCommandDispatcher.dispatch()`. The wire contract sends local IDs; the agent should trust it. If a bug sends a namespaced ID, it should fail visibly rather than silently passing through two redundant strips. The `BridgeCommandDispatcher` should dispatch the command as received.

### Components

| File | Change |
|------|--------|
| `BridgeDeviceProvider` | Wire `dispatch()` with non-blocking send, add pending map (composite key), add `completeCommand(tenancyId, correlationId, result)`, inject `ObjectMapper` + `BridgeServerConfig` |
| `BridgeWebSocketEndpoint` | `CommandResponse` case calls `provider.completeCommand(tenancyId, cr.correlationId(), cr.result())` |
| `BridgeCommandDispatcher` | Remove redundant `DeviceIdUtils.stripPrefix()` call — trust wire contract |
| `BridgeDeviceProviderTest` | Full test suite (see below) |
| `BridgeCommandDispatcherTest` | Update: commands arrive with local IDs, no prefix stripping |

### Test Plan

| Test | Assertion |
|------|-----------|
| Dispatch sends Command and correlates response | `completeCommand()` with matching correlationId resolves the Uni with the result |
| Timeout returns TIMEOUT | Uni completes with `TIMEOUT` after configured duration, pending map entry cleaned up |
| No session returns FAILED | No `WebSocketConnection` for tenancy → immediate `FAILED`, no pending entry created |
| Null correlationId generates UUID | Command with null correlationId gets a generated UUID; both envelope and inner command carry it |
| Send failure returns FAILED immediately | WebSocket send throws → `FAILED` immediately, not after 30s timeout |
| Serialization failure returns FAILED immediately | ObjectMapper throws → `FAILED` immediately, no pending entry ever registered |
| Concurrent dispatches resolve correctly | Multiple commands with different correlationIds complete independently with correct results |
| Late response after timeout is no-op | `completeCommand()` called after timeout → no exception, no double-emit, map entry already absent |
| Cross-tenancy response is no-op | `completeCommand()` with wrong tenancyId → composite key mismatch, entry not found, no effect on pending command |

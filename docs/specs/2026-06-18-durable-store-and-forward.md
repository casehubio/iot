# Durable Store-and-Forward — Design Spec

**Date:** 2026-06-18
**Issue:** casehubio/iot#20
**Parent spec:** `docs/superpowers/specs/2026-06-16-bridge-runtime-design.md` (§ No Event Buffer)

---

## Overview

Add a `BridgeEventStore` SPI to buffer events during cloud disconnection and replay them on reconnect. The in-memory default preserves current behavior (events lost on crash). A persistent alternative survives bridge restarts — replayed events transit the wire as audit data for future server-side logging or audit pipelines. Replayed events are a distinct wire protocol type to prevent ghost automations.

---

## Design

### Ghost Automation Mitigation

The parent spec explicitly rejected event replay to avoid ghost automations:

> "Replaying buffered events risks ghost automations: a presence event from 30 seconds ago triggers a security case for a past arrival, while the current state shows no presence."

This spec reintroduces replay through the persistent store. To prevent ghost automations, replayed events use a distinct sealed variant — `BridgeMessage.ReplayedStateChange`. The server skips both the device map update and CDI event firing for replayed messages.

**Why skip the device map too:** If replayed events update the device map, the subsequent snapshot diff shrinks — transitions that would have been detected by the snapshot are silently absorbed. Example: light ON → disconnect → light OFF (stored) → reconnect → replay updates map to OFF → snapshot diffs OFF vs OFF → no CDI event → consumer sees nothing. The ON→OFF transition is lost. Without the store, the snapshot diff ON vs OFF would have fired a CDI event. The store makes the system *less* observable.

Fix: the server ignores replayed events entirely. They transit the wire (audit data) but do not touch the device map or CDI pipeline. The snapshot is the sole authority for device state and CDI events, exactly as the parent spec intended.

### Wire Protocol — ReplayedStateChange Sealed Variant

```java
@JsonSubTypes.Type(value = BridgeMessage.ReplayedStateChange.class, name = "REPLAYED_STATE_CHANGE")

record ReplayedStateChange(String tenancyId, Instant timestamp, StateChangeEvent event)
        implements BridgeMessage {
    public ReplayedStateChange {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(event, "event");
    }
}
```

A distinct sealed variant rather than a boolean flag on `StateChange`:
- Consistent with the sealed interface pattern — behavioral distinctions are types, not flags
- Compiler-enforced exhaustive handling — every `switch` on `BridgeMessage` must handle `ReplayedStateChange`
- `markReplayed()` is a clean type conversion: `StateChange → ReplayedStateChange`
- No backward-compat concerns — old agents send `STATE_CHANGE`, new agents send `REPLAYED_STATE_CHANGE`. Old servers fail at deserialization with a clear error, not silent mishandling

### What Audit Consumers Get

The persistent store's value is: **events survive bridge restarts and transit the wire as audit data.** A WebSocket-level logger or future server-side audit store can capture them. The store does NOT provide:
- Real-time audit via CDI events — replayed events are excluded from the CDI pipeline
- Device map accuracy — the snapshot is the sole authority
- Complete transition history guarantee — at-most-once during replay (see Delivery Guarantee)

If the compliance use case requires cloud-side persistent audit, that's a separate feature (server-side event log). The bridge-side store ensures events reach the wire; what the server does with them is a separate concern.

### SPI — `io.casehub.iot.bridge.agent.BridgeEventStore`

```java
public interface BridgeEventStore {
    void store(BridgeMessage message);
    List<BridgeMessage> drain();
    boolean isEmpty();
}
```

- `store()` — buffer an event. Thread-safe.
- `drain()` — return all stored events in FIFO order and clear the store.
- `isEmpty()` — check if events are pending.

The SPI lives in the **bridge module**, not api. `BridgeEventStore` is an internal bridge concern — no application-tier JARs implement it (unlike `BridgeEventFilter` which filter libraries implement). If a third-party store (Redis, Kafka) is needed later, moving the interface to api is mechanical.

The store accepts `BridgeMessage` (not `BridgeMessage.StateChange` specifically). `ProviderStatusChange` buffering is plausible — a provider going offline during disconnection is a status transition that matters on reconnect.

### InMemoryBridgeEventStore

`@DefaultBean @ApplicationScoped` in the bridge module. Bounded `ArrayDeque` — configurable via `casehub.iot.bridge.event-store.max-size` (default 10,000). When full, oldest events are dropped (FIFO eviction with `pollFirst()`). `synchronized` on all public methods.

### PersistentBridgeEventStore

`@Alternative @Priority(1) @ApplicationScoped` in the bridge module. Activated by adding `quarkus.arc.selected-alternatives=io.casehub.iot.bridge.agent.PersistentBridgeEventStore` to config.

Append-only file log in a configurable directory (`casehub.iot.bridge.event-store.directory`, default `data/bridge-events`). Each event is one JSON line (NDJSON). `store()` appends with flush. `drain()` reads all lines, deletes the file. No size bound — disk is cheap. `synchronized` on all public methods.

Corrupt lines (e.g., partial writes from a crash) are skipped with a warning log. Valid events before and after the corrupt line are returned normally.

### Delivery Guarantee — At-Most-Once During Replay

`drain()` deletes the file (or clears the queue), then replay sends each message. If the connection drops mid-replay, `send()` silently drops remaining events — they are not re-stored. The snapshot after replay provides current truth regardless.

This is explicitly **at-most-once** delivery for replayed events. The snapshot reconciles device state; only transition history has a potential gap.

### Event Ordering — Best-Effort During Replay

After the WebSocket opens, `isConnected()` returns true. New events during the replay drain take the live send path — they can arrive at the cloud before all drained events have been replayed. The window is small (sequential send loop), but strict ordering is not guaranteed.

### Integration — BridgeEventObserver

Store events when disconnected instead of dropping. Filter chain runs regardless of connection state.

```java
void onStateChange(@ObservesAsync StateChangeEvent event) {
    FilterContext ctx = new FilterContext(
            config.tenancyId(),
            connectionManager.isConnected() ? ConnectionState.CONNECTED : ConnectionState.DISCONNECTED,
            event.providerId());
    FilterAction action = filterChain.execute(event, ctx).await().indefinitely();

    switch (action) {
        case FilterAction.Forward f -> {
            var msg = new BridgeMessage.StateChange(
                    config.tenancyId(), Instant.now(), event);
            if (connectionManager.isConnected()) {
                connectionManager.send(msg);
            } else {
                eventStore.store(msg);
            }
        }
        case FilterAction.Suppress s ->
                LOG.debugf("Event suppressed: %s", s.reason());
    }
}
```

**`onProviderStatus()` is unchanged.** Provider status transitions during disconnection are operational noise — the snapshot on reconnect gives current status. This is deliberate, not an oversight.

### Integration — BridgeConnectionManager

On reconnect, after WebSocket opens and before `sendSnapshot()`:

```java
if (!eventStore.isEmpty()) {
    List<BridgeMessage> buffered = eventStore.drain();
    LOG.infof("Replaying %d buffered events", buffered.size());
    for (BridgeMessage msg : buffered) {
        send(markReplayed(msg));
    }
}
sendSnapshot();
```

`markReplayed()` is a private method on `BridgeConnectionManager`:

```java
private BridgeMessage markReplayed(BridgeMessage msg) {
    if (msg instanceof BridgeMessage.StateChange sc) {
        return new BridgeMessage.ReplayedStateChange(sc.tenancyId(), sc.timestamp(), sc.event());
    }
    return msg;
}
```

Converts `StateChange → ReplayedStateChange`. Non-StateChange messages pass through unchanged.

### Integration — BridgeWebSocketEndpoint (Server-Side)

```java
case BridgeMessage.StateChange sc -> {
    StateChangeEvent namespacedEvent = provider.onStateChange(sc.event(), tenancyId);
    stateEvents.fireAsync(namespacedEvent);
}
case BridgeMessage.ReplayedStateChange rsc -> {
    LOG.debugf("Replayed event received [tenancyId=%s, device=%s]",
            tenancyId, rsc.event().after().deviceId());
}
```

Replayed events are logged but do not touch the device map or fire CDI events. The snapshot is the sole authority.

### Integration — BridgeCloudClient (Agent-Side)

Add to the `onMessage()` switch:

```java
case BridgeMessage.ReplayedStateChange rsc ->
        LOG.warnf("Received ReplayedStateChange from cloud — replays flow agent-to-server");
```

Same pattern as the existing `StateChange` guard — replayed events flow agent-to-server only.

### Configuration

Add nested config group to `BridgeAgentConfig`:

```java
EventStore eventStore();

interface EventStore {
    @WithDefault("10000")
    int maxSize();

    @WithDefault("data/bridge-events")
    String directory();
}
```

### Components

| File | Change |
|------|--------|
| `BridgeMessage` (api) | Add `ReplayedStateChange` sealed variant + `@JsonSubTypes` entry |
| `BridgeEventStore` (new, bridge module) | SPI interface |
| `InMemoryBridgeEventStore` (new, bridge module) | `@DefaultBean` bounded queue, synchronized |
| `PersistentBridgeEventStore` (new, bridge module) | `@Alternative @Priority(1)` NDJSON, synchronized |
| `BridgeEventObserver` | Store when disconnected; filter chain runs always; `onProviderStatus()` unchanged |
| `BridgeConnectionManager` | Drain + `markReplayed()` before snapshot |
| `BridgeWebSocketEndpoint` (bridge-server) | Log replayed events, no device map or CDI |
| `BridgeCloudClient` (bridge) | Handle `ReplayedStateChange` in switch (guard) |
| `BridgeAgentConfig` | Nested `EventStore` config group |

### Test Plan

| Test | Assertion |
|------|-----------|
| InMemory: store and drain in FIFO order | Events returned in insertion order |
| InMemory: bounded eviction drops oldest | Store 3 with maxSize=2 → drain returns last 2 |
| InMemory: drain clears store | After drain, isEmpty() returns true |
| InMemory: empty drain returns empty list | No events → empty list |
| InMemory: concurrent store is thread-safe | Multiple threads storing → no lost events, no exceptions |
| Persistent: store and drain in FIFO order | Events survive write → read cycle |
| Persistent: drain deletes file | After drain, file gone, isEmpty() true |
| Persistent: startup with existing file replays | Pre-existing NDJSON file → drain returns events |
| Persistent: empty drain with no file | No file → empty list, no exception |
| Persistent: concurrent store and drain | Synchronized — no corruption |
| Persistent: corrupt line skipped with warning | NDJSON file with one malformed line → valid events returned, corrupt line skipped |
| Observer: stores event when disconnected | Disconnected + Forward → eventStore.store() called |
| Observer: sends event when connected | Connected + Forward → connectionManager.send() called |
| Observer: ProviderStatusEvent dropped when disconnected | Disconnected → not stored, not sent |
| ConnectionManager: drains and replays before snapshot | Reconnect → drain(), events sent as ReplayedStateChange, then snapshot |
| markReplayed: converts StateChange to ReplayedStateChange | StateChange input → ReplayedStateChange output with same fields |
| markReplayed: passes non-StateChange through unchanged | Heartbeat input → Heartbeat output |
| Endpoint: ReplayedStateChange does not update map or fire CDI | ReplayedStateChange → no onStateChange(), no fireAsync() |
| Endpoint: live StateChange updates map and fires CDI | StateChange → onStateChange() + fireAsync() |
| Serialization: ReplayedStateChange round-trips via Jackson | Serialize → deserialize → correct type and fields |

# BridgeAuditStore SPI — Design Spec

**Issue:** #35
**Date:** 2026-06-28
**Deferred:** #37 (reactive Uni<> variant), #38 (JPA implementation)
**Branch:** `issue-36-arc42-and-audit-store`
**Depends on:** #34 (BridgeAuditEvent CDI events — delivered)

---

## Overview

Add a `BridgeAuditStore` SPI for structured query and retrieval of bridge audit events. Follows the Store SPI pattern from the module-tier-structure protocol. The SPI is the query/retrieval complement to the observation path delivered in #34 — CDI events handle real-time observation, the store handles historical query.

### What this is NOT

- Not a replacement for `LoggingBridgeAuditObserver` — operational logging via structured JSON remains the primary audit trail, queryable via log aggregation (ELK, Loki)
- Not a compliance ledger — the future `casehub-iot-bridge-ledger` module (designed for in #34) handles tamper-evident audit via `LedgerEntry`
- Not a reactive SPI — blocking is sufficient for current consumers (#37 tracks the reactive variant)

### SPI tier contrast with BridgeEventStore

`BridgeEventStore` (in `bridge/`) is an internal concern of the bridge agent — a store-and-drain buffer for reliable delivery during disconnection. It lives in the bridge module (Tier 3) because only the bridge agent uses it. `BridgeAuditStore` is a public SPI consumed by external apps for audit query — it belongs in `api/` (Tier 1). Different visibility, different tier.

---

## SPI Interface

### BridgeAuditStore

Location: `api/src/main/java/io/casehub/iot/api/bridge/BridgeAuditStore.java`

```java
public interface BridgeAuditStore {

    void save(BridgeAuditEvent event);

    /**
     * Returns events matching the query criteria, ordered by
     * {@code receivedAt} descending (newest first). Implementations
     * MUST honour this ordering contract.
     */
    List<BridgeAuditEvent> query(BridgeAuditQuery query);
}
```

Pure Java, Tier 1. No CDI annotations on the interface. Blocking — `save()` is called from `@ObservesAsync` handlers (CDI thread pool); `query()` serves admin/debugging endpoints.

Placed in `api/bridge/` (not `api/spi/`) because its parameter types (`BridgeAuditEvent`, `BridgeAuditEventType`) live in `api/bridge/`. Consistent with `BridgeEventFilter` which is also in `api/bridge/`.

**Naming — `save()` not `store()`:** `BridgeEventStore` uses `store()` + `drain()` — a transactional buffer pattern (enqueue, dequeue-all). `BridgeAuditStore` uses `save()` + `query()` — a persistence + retrieval pattern. The verb difference reflects the semantic difference: `store()` implies temporary buffering with bulk retrieval; `save()` implies durable persistence with selective query.

### BridgeAuditQuery

Location: `api/src/main/java/io/casehub/iot/api/bridge/BridgeAuditQuery.java`

```java
public record BridgeAuditQuery(
    @Nullable String tenancyId,
    @Nullable Instant from,
    @Nullable Instant to,
    @Nullable BridgeAuditEventType eventType,
    @Nullable String deviceId,
    @Nullable String correlationId,
    int limit
) {
    public static final int DEFAULT_LIMIT = 100;

    public BridgeAuditQuery {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String tenancyId;
        private Instant from;
        private Instant to;
        private BridgeAuditEventType eventType;
        private String deviceId;
        private String correlationId;
        private int limit = DEFAULT_LIMIT;

        public Builder tenancyId(String tenancyId) { this.tenancyId = tenancyId; return this; }
        public Builder from(Instant from) { this.from = from; return this; }
        public Builder to(Instant to) { this.to = to; return this; }
        public Builder eventType(BridgeAuditEventType eventType) { this.eventType = eventType; return this; }
        public Builder deviceId(String deviceId) { this.deviceId = deviceId; return this; }
        public Builder correlationId(String correlationId) { this.correlationId = correlationId; return this; }
        public Builder limit(int limit) { this.limit = limit; return this; }

        public BridgeAuditQuery build() {
            return new BridgeAuditQuery(tenancyId, from, to, eventType, deviceId, correlationId, limit);
        }
    }
}
```

All criteria nullable — null means "no filter on this field". Results ordered by `receivedAt` descending (newest first). `limit` defaults to 100, must be positive.

**Time range validation:** `from` and `to` are not validated against each other. If `from` is after `to`, the query produces empty results — consistent with the null-means-no-filter contract. Each field is an independent filter predicate; contradictory predicates simply match nothing.

**correlationId:** Links `COMMAND_SENT` to its `COMMAND_RESPONSE` — the primary audit investigation use case ("what happened after we sent this command?"). Without it, tracing a command round-trip requires fetching all events by deviceId and filtering client-side.

Usage:
```java
var query = BridgeAuditQuery.builder()
    .tenancyId("tenant-1")
    .eventType(BridgeAuditEventType.COMMAND_SENT)
    .from(Instant.now().minus(1, ChronoUnit.HOURS))
    .limit(50)
    .build();

List<BridgeAuditEvent> events = auditStore.query(query);
```

### Why query object, not individual methods

The issue specified `findByTenancyId`, `findByTimeRange`, `findByEventType`, `findByDeviceId`. But these criteria naturally compose — "COMMAND_SENT events for device X in tenant Y during the last hour" requires all four combined. Individual methods create combinatorial explosion. A single `query(BridgeAuditQuery)` gives composable filtering: add criteria without changing the SPI signature.

---

## In-Memory Implementation

### InMemoryBridgeAuditStore

Location: `bridge-server/src/main/java/io/casehub/iot/bridge/server/audit/InMemoryBridgeAuditStore.java`

```java
@DefaultBean
@ApplicationScoped
public class InMemoryBridgeAuditStore implements BridgeAuditStore {

    private final int maxSize;
    private final ArrayDeque<BridgeAuditEvent> events;

    @Inject
    public InMemoryBridgeAuditStore(BridgeServerConfig config) {
        this.maxSize = config.auditStore().maxSize();
        this.events = new ArrayDeque<>(Math.min(maxSize, 1024));
    }

    InMemoryBridgeAuditStore(int maxSize) {
        this.maxSize = maxSize;
        this.events = new ArrayDeque<>(Math.min(maxSize, 1024));
    }

    @Override
    public synchronized void save(BridgeAuditEvent event) {
        if (events.size() >= maxSize) {
            events.removeLast();
        }
        events.addFirst(event);
    }

    @Override
    public List<BridgeAuditEvent> query(BridgeAuditQuery query) {
        List<BridgeAuditEvent> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(events);
        }
        return snapshot.stream()
            .filter(e -> matches(e, query))
            .limit(query.limit())
            .toList();
    }

    private boolean matches(BridgeAuditEvent event, BridgeAuditQuery query) {
        if (query.tenancyId() != null && !query.tenancyId().equals(event.tenancyId())) return false;
        if (query.eventType() != null && query.eventType() != event.eventType()) return false;
        if (query.deviceId() != null && !query.deviceId().equals(event.deviceId())) return false;
        if (query.correlationId() != null && !query.correlationId().equals(event.correlationId())) return false;
        if (query.from() != null && event.receivedAt().isBefore(query.from())) return false;
        if (query.to() != null && event.receivedAt().isAfter(query.to())) return false;
        return true;
    }
}
```

**BridgeServerConfig change:** Add `auditStore()` nested interface:

```java
@ConfigMapping(prefix = "casehub.iot.bridge-server")
public interface BridgeServerConfig {

    @WithDefault("30")
    int commandTimeoutSeconds();

    AuditStore auditStore();

    interface AuditStore {
        @WithDefault("10000")
        int maxSize();
    }
}
```

Config property: `casehub.iot.bridge-server.audit-store.max-size` (env var `CASEHUB_IOT_BRIDGE_SERVER_AUDIT_STORE_MAX_SIZE`).

**Constructor injection:** Follows `InMemoryBridgeEventStore` pattern — CDI constructor takes `BridgeServerConfig`, package-private constructor takes `int maxSize` for unit tests without CDI.

**ArrayDeque:** Matches `InMemoryBridgeEventStore` — same data structure for the same bounded ring buffer pattern. Better cache locality and lower per-element overhead than `LinkedList`.

**CDI tier:** `@DefaultBean` — displaced by any `@ApplicationScoped` implementation from a consuming app. Correct tier: there is no JPA impl in this repo, so the in-memory store IS the default. Consuming apps with JPA provide `@ApplicationScoped JpaBridgeAuditStore` which wins automatically (#38).

**Thread safety:** `synchronized` on `save()` and snapshot copy in `query()`. The lock scope is minimal — `query()` copies the deque under the lock, then filters outside it. IoT event frequency (seconds between events) makes contention negligible.

**Bounded ring buffer:** `addFirst` + evict oldest via `removeLast` when at capacity. Events are stored newest-first, so `query()` iterates in `receivedAt` descending order without sorting — the insertion order IS the sort order.

**Production use case:** Bridge on Raspberry Pi without a database. In-memory audit is volatile (lost on restart) but sufficient for operational debugging alongside the primary structured log trail. This is why the implementation lives in `bridge-server/` (production classpath), not `testing/`.

---

## CDI Observer

### StoringBridgeAuditObserver

Location: `bridge-server/src/main/java/io/casehub/iot/bridge/server/audit/StoringBridgeAuditObserver.java`

```java
@ApplicationScoped
public class StoringBridgeAuditObserver {

    private final BridgeAuditStore store;

    @Inject
    StoringBridgeAuditObserver(BridgeAuditStore store) {
        this.store = store;
    }

    void onAudit(@ObservesAsync BridgeAuditEvent event) {
        store.save(event);
    }
}
```

Constructor injection, consistent with project pattern. Separate from the store because the store is the SPI (persistence + query) and the observer is the wiring (CDI events → store). Replacing the store implementation doesn't require touching the observer.

Coexists with `LoggingBridgeAuditObserver` — both fire independently for every `BridgeAuditEvent`. This is the dual-trail pattern: logging for operational observability, store for programmatic query.

---

## Module Placement Summary

| Artifact | Module | Tier | CDI |
|----------|--------|------|-----|
| `BridgeAuditStore` (SPI) | api | 1 | None |
| `BridgeAuditQuery` (record) | api | 1 | None |
| `InMemoryBridgeAuditStore` | bridge-server | 3 | `@DefaultBean @ApplicationScoped` |
| `StoringBridgeAuditObserver` | bridge-server | 3 | `@ApplicationScoped` |
| `BridgeServerConfig.AuditStore` | bridge-server | 3 | `@ConfigMapping` |

No new Maven modules.

---

## Protocol Deviations

### No persistence-memory/ module

**Protocol rule:** module-tier-structure (PP-20260512-module-tiers) states "persistence-memory/ is mandatory for every module with a Store SPI."

**Deviation:** The in-memory implementation lives in `bridge-server/`, not a separate `persistence-memory/` module.

**Reasoning:** The protocol assumes a repo that OWNS the JPA implementation — put JPA in `runtime/`, put in-memory in `persistence-memory/` to let tests avoid the datasource. casehub-iot does not own the JPA impl; consuming apps provide it (#38). The in-memory store with `@DefaultBean` in `bridge-server/` is the correct mechanism for this inverted ownership pattern: bridge-server IS the deployment target, and consuming apps that provide JPA carry their own `@ApplicationScoped` implementation. Creating a single-class `bridge-persistence-memory/` module adds Maven overhead without architectural benefit when every bridge-server deployment already includes the in-memory store on its production classpath.

The protocol's own decision guide supports this: "could someone reasonably deploy with in-memory persistence in production?" — yes, the Raspberry Pi scenario. The in-memory impl IS a production deployment target, which is why it lives on the production classpath in `bridge-server/`, not in a separate module that would need to be explicitly added.

---

## Testing Strategy

### BridgeAuditQuery tests (api module)

- Builder produces correct record with all fields including correlationId
- Builder defaults: limit = 100, all criteria null
- Compact constructor rejects limit <= 0
- Contradictory time range (from > to): valid construction, produces empty results on query

### InMemoryBridgeAuditStore tests (bridge-server module)

- `save()` stores events retrievable by `query()`
- Bounded eviction: save maxSize+1 events, oldest is evicted
- Query filters: each criterion individually (tenancyId, eventType, deviceId, correlationId, from, to)
- Query filter composition: multiple criteria combined
- Limit: query with limit < stored events returns only limit events
- Ordering: newest first (receivedAt descending)
- Empty store: query returns empty list
- Null criteria: query with all-null criteria returns all events (up to limit)
- Thread safety: concurrent save + query (verify no ConcurrentModificationException)

### StoringBridgeAuditObserver test (bridge-server module)

- Observer calls store.save() when BridgeAuditEvent is fired
- Verify observer coexists with LoggingBridgeAuditObserver (both fire)

---

## Platform Coherence

| Concern | Status |
|---------|--------|
| Module tier structure | SPI in api (Tier 1, pure Java). In-memory in bridge-server (Tier 3, full Quarkus runtime). ✅ |
| Store SPI pattern | Single query method with criteria object. @DefaultBean in-memory. See Protocol Deviations for persistence-memory/ rationale. ✅ |
| Consumer SPI placement | In `api/bridge/` with its parameter types, not `api/spi/` — bridge-specific, not top-level. ✅ |
| Bridge module SPI placement | N/A — BridgeAuditEvent is in api, not bridge-internal. ✅ |
| CDI priority ladder | @DefaultBean (in-memory) < @ApplicationScoped (future JPA from consumer). ✅ |
| Blocking vs reactive | Blocking only. #37 tracks reactive variant. ✅ |
| Constructor injection | Follows InMemoryBridgeEventStore pattern — CDI constructor + package-private test constructor. ✅ |
| Data structure | ArrayDeque — matches InMemoryBridgeEventStore for same bounded ring buffer pattern. ✅ |

---

## Out of Scope (tracked)

- #37 — Reactive `Uni<>` variant for query/save
- #38 — JPA implementation for durable persistence
- Compliance ledger integration (`casehub-iot-bridge-ledger` — designed for in #34)
- REST endpoint for audit queries (consuming app's concern)
- Pagination beyond limit (cursor-based pagination deferred until query volume justifies it)

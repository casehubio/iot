# JPA BridgeAuditStore Implementation

**Issue:** casehubio/iot#38
**Date:** 2026-06-29

## Context

`BridgeAuditStore` SPI (in `api/`) provides structured audit query/retrieval for bridge interactions. The current implementation is `InMemoryBridgeAuditStore` in `bridge-server/` — a bounded ring buffer suitable for zero-database deployments (Raspberry Pi, dev mode). For production cloud deployments, consuming apps need durable audit persistence with structured query across restarts.

The existing `InMemoryBridgeAuditStore` is annotated `@DefaultBean`, which violates the `persistence-backend-cdi-priority` protocol — in-memory stores that actually store and retrieve data must be `@Alternative @Priority(N)`, not `@DefaultBean`. This spec corrects the CDI hierarchy as part of introducing the JPA backend.

**#37 (reactive variant) deferred.** Analysis showed no current reactive consumer — the only call site (`StoringBridgeAuditObserver`) runs on `@ObservesAsync` (worker thread). Future REST endpoints can use `@Blocking` or `@RunOnVirtualThread`. When a genuinely reactive backend (Hibernate Reactive, reactive MongoDB) warrants it, a parallel reactive stack should be built — not a blocking-to-reactive bridge wrapper.

## Modules

### bridge-persistence-jpa/

New submodule: `bridge-persistence-jpa/` (artifact `casehub-iot-bridge-persistence-jpa`).

Separate from `bridge-server/` because adding JPA there would force all consumers — including Raspberry Pi bridge agents with no database — to configure a datasource. Consuming apps opt in by adding this artifact to their classpath.

**Dependencies:**
- `casehub-iot-api` — SPI and domain types
- `quarkus-hibernate-orm` — JPA runtime
- `quarkus-jdbc-postgresql` — PostgreSQL driver (runtime)
- `jackson-databind` — BridgeMessage JSON serialization (transitive from api)
- `jandex-maven-plugin` — CDI bean discovery

### bridge-persistence-memory/

New submodule: `bridge-persistence-memory/` (artifact `casehub-iot-bridge-persistence-memory`).

Moves the existing `InMemoryBridgeAuditStore` from `bridge-server/` with corrected CDI annotations (`@Alternative @Priority(100)` instead of `@DefaultBean`). Serves Raspberry Pi, dev mode, and test isolation — a legitimate production deployment target for zero-database installs (per `module-tier-structure` Store SPI pattern).

**Dependencies:**
- `casehub-iot-api` — SPI and domain types
- `quarkus-arc` — CDI container (provides SmallRye Config transitively for `@ConfigMapping` resolution)
- `jandex-maven-plugin` — CDI bean discovery (required for Quarkus to discover `@Alternative` bean in library JARs)

**Configuration:**

```java
@ConfigMapping(prefix = "casehub.iot.bridge.audit-store.memory")
public interface InMemoryAuditStoreConfig {
    @WithDefault("10000")
    int maxSize();
}
```

Property: `casehub.iot.bridge.audit-store.memory.max-size`. Config prefix is scoped to this module — avoids collision with `BridgeServerConfig` in `bridge-server/`.

### bridge-server/ changes

- Remove `InMemoryBridgeAuditStore` (moved to `bridge-persistence-memory/`)
- Add `NoOpBridgeAuditStore @DefaultBean @ApplicationScoped` — returns empty `List` from `query()`, discards `save()` calls. Active when no persistence module is on the classpath.
- Remove `AuditStore auditStore()` and nested `AuditStore` interface from `BridgeServerConfig` — the only consumer was `InMemoryBridgeAuditStore`, which now has its own `InMemoryAuditStoreConfig` in `bridge-persistence-memory/`.
- Add `casehub-iot-bridge-persistence-memory` as a `<scope>test</scope>` dependency — the `@Alternative @Priority(100)` displaces the `@DefaultBean` during `@QuarkusTest` augmentation, keeping `AuditObserverCoexistenceTest` functional.
- Rewrite `StoringBridgeAuditObserverTest` to use an inline `BridgeAuditStore` test double instead of directly instantiating `InMemoryBridgeAuditStore(100)`. The current test calls a package-private constructor that becomes inaccessible after the move to `bridge-persistence-memory/` (different package). The test's concern is delegation ("does the observer call `store.save()`?"), not store correctness — an anonymous implementation of the SPI interface is sufficient and architecturally correct.
- `StoringBridgeAuditObserver` unchanged — calls `store.save()` on whichever `BridgeAuditStore` CDI resolves.

**Config property rename:** The in-memory store max-size property changes from `casehub.iot.bridge-server.audit-store.max-size` (via `BridgeServerConfig`) to `casehub.iot.bridge.audit-store.memory.max-size` (via `InMemoryAuditStoreConfig`). Existing Raspberry Pi and Docker Compose deployments using the old property must update their configuration. The old property becomes inert after `BridgeServerConfig.AuditStore` is removed — `@WithDefault` in the old config prevented startup failure, but the new config has its own `@WithDefault("10000")` so unconfigured deployments are unaffected.

### CDI priority ladder

Per `persistence-backend-cdi-priority` protocol:

| Tier | Bean | Annotation | Module |
|------|------|-----------|--------|
| 1 — No-op | `NoOpBridgeAuditStore` | `@DefaultBean` | `bridge-server/` |
| 2 — Primary | `JpaBridgeAuditStore` | `@ApplicationScoped` | `bridge-persistence-jpa/` |
| 4 — In-memory | `InMemoryBridgeAuditStore` | `@Alternative @Priority(100)` | `bridge-persistence-memory/` |

Deployment scenarios:
- **Cloud (database):** `bridge-persistence-jpa/` on classpath → JPA store active. Durable, queryable across restarts.
- **Raspberry Pi / dev mode:** `bridge-persistence-memory/` on classpath → in-memory ring buffer active. Ephemeral, bounded.
- **Log-only:** Neither persistence module → no-op store active. `LoggingBridgeAuditObserver` provides the audit trail via structured JSON logging.

**Docker image update:** `iot-bridge/pom.xml` adds `bridge-persistence-memory` as a dependency so the Raspberry Pi bridge retains in-memory audit capability after the `@DefaultBean` removal.

## SPI Change: BridgeAuditQuery offset

`BridgeAuditQuery` gains an `offset` field (default 0) for pagination support. Before any JPA consumer exists, this is the right moment — adding it later is SPI-breaking.

```java
public record BridgeAuditQuery(
    @Nullable String tenancyId,
    @Nullable Instant from,
    @Nullable Instant to,
    @Nullable BridgeAuditEventType eventType,
    @Nullable String deviceId,
    @Nullable String correlationId,
    int offset,
    int limit
) { ... }
```

Builder adds `offset(int)` with default 0. Compact constructor validates `offset >= 0`. Existing call sites using `Builder` are source-compatible (offset defaults to 0).

In-memory store: `.skip(offset)` before `.limit(limit)`. JPA store: `TypedQuery.setFirstResult(offset)`.

## JPA Entity

`BridgeAuditJpaEntity` maps to table `bridge_audit_event`:

| Column | Type | Nullable | Notes |
|--------|------|----------|-------|
| `id` | `UUID` | no | PK, `GenerationType.UUID` |
| `tenancy_id` | `VARCHAR(255)` | no | indexed |
| `received_at` | `TIMESTAMP WITH TIME ZONE` | no | indexed, ordering column |
| `event_type` | `VARCHAR(50)` | no | `EnumType.STRING` |
| `correlation_id` | `VARCHAR(255)` | yes | indexed |
| `device_id` | `VARCHAR(255)` | yes | indexed |
| `message` | `JSONB` | yes | `@JdbcTypeCode(SqlTypes.JSON)` — Hibernate 6 delegates to Jackson |

**Indexes:**
- `(tenancy_id, received_at DESC)` — primary query path
- `(device_id)` — device-specific queries
- `(correlation_id)` — command correlation

**BridgeMessage serialization:** `@JdbcTypeCode(SqlTypes.JSON)` on the `BridgeMessage message` field. Hibernate 6 uses the Quarkus-managed `ObjectMapper`, which respects `@JsonTypeInfo`/`@JsonSubTypes` on the sealed interface. The `@type` discriminator is preserved in the JSON, so deserialization recovers the correct sealed variant. No manual `AttributeConverter` needed.

**Payload size expectations per BridgeMessage variant:**

| Variant | Typical size | Frequency | Notes |
|---------|-------------|-----------|-------|
| `StateChange` | 1–5 KB | High (per device state change) | Before/after `DeviceEntity` + `changedCapabilities` |
| `ReplayedStateChange` | 1–5 KB | Medium (agent reconnection replay) | Same structure as StateChange |
| `StateSnapshot` | 10 KB–2 MB | Low (once per agent connection) | Full `List<DeviceEntity>` — 30–100 devices typical; property management could have thousands |
| `ProviderStatusChange` | < 500 bytes | Low (connection lifecycle) | `ProviderStatusEvent` only |
| `Command` | 1–3 KB | Low (user-initiated) | `DeviceCommand` with parameters |
| `CommandResponse` | < 500 bytes | Low (matches Command) | `CommandResult` enum |

`StateSnapshot` is the only variant with potentially large payloads. PostgreSQL TOAST transparently handles rows > 2 KB. The volume of `StateSnapshot` events is low (once per agent connection), so table bloat from large payloads is not a practical concern. The store persists `BridgeMessage` faithfully — truncation at the persistence layer would be data loss. If payload size limiting is needed, it belongs at the event production layer (WebSocket endpoint), not the store.

## Implementation

`JpaBridgeAuditStore implements BridgeAuditStore`, `@ApplicationScoped`.

**`save(BridgeAuditEvent)`** — `@Transactional`. Maps domain record to entity via `BridgeAuditEventMapper`, persists via `EntityManager.persist()`.

**`query(BridgeAuditQuery)`** — JPA Criteria API with dynamic predicates. Each non-null `BridgeAuditQuery` field adds a predicate:
- `tenancyId` → `CriteriaBuilder.equal`
- `eventType` → `CriteriaBuilder.equal`
- `deviceId` → `CriteriaBuilder.equal`
- `correlationId` → `CriteriaBuilder.equal`
- `from` → `CriteriaBuilder.greaterThanOrEqualTo` on `receivedAt`
- `to` → `CriteriaBuilder.lessThanOrEqualTo` on `receivedAt`

Order by `receivedAt DESC` (newest first — SPI ordering contract). `TypedQuery.setFirstResult(query.offset())`. `TypedQuery.setMaxResults(query.limit())`. Results mapped back to domain records via `BridgeAuditEventMapper`.

**Mapper:** `BridgeAuditEventMapper` — static utility class. `toEntity(BridgeAuditEvent)` and `toDomain(BridgeAuditJpaEntity)`. SPI boundary: callers never see JPA entities.

## Error Handling

`JpaBridgeAuditStore.save()` propagates JPA exceptions to the caller. The caller is `StoringBridgeAuditObserver.onAudit()` — an `@ObservesAsync` CDI handler. In Quarkus, uncaught exceptions from `@ObservesAsync` handlers are logged at ERROR level and swallowed. The audit event is lost from the queryable store.

This is acceptable for operational audit because:
1. `LoggingBridgeAuditObserver` fires independently as a separate CDI observer — the structured log captures the event regardless of store failures.
2. The structured log is the primary audit trail; the store enables programmatic query as a secondary convenience.
3. The dual-trail audit pattern's `LedgerWriteFailedEvent` retry recommendation applies to compliance ledger writes. `BridgeAuditStore` is operational, not compliance — there is no hash chain, no tamper-evidence, no regulatory mandate.

If compliance-grade audit becomes a requirement, the existing architecture supports it: a future `casehub-iot-bridge-ledger` module with `@ObservesAsync BridgeAuditEvent` writing `BridgeLedgerEntry extends LedgerEntry` — independent of this store.

## Flyway Migration

`V1__create_bridge_audit_event.sql` at `classpath:db/iot-bridge/migration/`.

```sql
CREATE TABLE bridge_audit_event (
    id              UUID            NOT NULL PRIMARY KEY,
    tenancy_id      VARCHAR(255)    NOT NULL,
    received_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    event_type      VARCHAR(50)     NOT NULL,
    correlation_id  VARCHAR(255),
    device_id       VARCHAR(255),
    message         JSONB
);

CREATE INDEX idx_bridge_audit_tenancy_time
    ON bridge_audit_event (tenancy_id, received_at DESC);

CREATE INDEX idx_bridge_audit_device
    ON bridge_audit_event (device_id);

CREATE INDEX idx_bridge_audit_correlation
    ON bridge_audit_event (correlation_id);
```

Consuming apps add to their Flyway locations:
```properties
quarkus.flyway.locations=classpath:db/iot-bridge/migration,...
```

**Note on `received_at DESC` index:** PostgreSQL supports `DESC` in `CREATE INDEX` natively. H2 in PostgreSQL mode accepts the syntax. The descending index optimises the primary query path (newest-first ordering).

## Data Retention

The JPA store grows without bound — unlike the in-memory ring buffer, there is no natural retention boundary. At 100 events/hour, this produces ~876K rows/year. With JSONB payloads, the table will reach gigabytes over years.

Retention strategy (configurable TTL, scheduled cleanup, table partitioning) is deferred to a follow-up issue. Retention is deployment-specific — some operators want 90 days, some want 7 years for compliance. The store's first job is correctness and queryability. Retention is additive: a scheduled cleanup job or partitioning scheme can be layered on without schema changes.

**Deferred:** casehubio/iot#40 — BridgeAuditStore data retention strategy.

## Testing

**`BridgeAuditEventMapperTest`** — unit test. Round-trip `BridgeAuditEvent ↔ BridgeAuditJpaEntity` for all 7 `BridgeMessage` variants (StateChange, ReplayedStateChange, StateSnapshot, ProviderStatusChange, Command, CommandResponse, Heartbeat) plus null message. Verifies JSON serialization preserves sealed variant discriminator.

**`JpaBridgeAuditStoreTest`** — `@QuarkusTest` with H2 `MODE=PostgreSQL`. Covers all query criteria matching `InMemoryBridgeAuditStoreTest` coverage:
- Save and retrieve
- Filter by tenancy ID
- Filter by event type
- Filter by device ID
- Filter by correlation ID
- Filter by time range
- Composed multiple criteria
- Limit results
- Offset pagination
- Newest-first ordering
- Empty results
- Null message field handling

H2 in PostgreSQL mode accepts `JSONB` as a type. `@JdbcTypeCode(SqlTypes.JSON)` works with both H2 and PostgreSQL — Hibernate selects the appropriate dialect-specific type.

**`JpaBridgeAuditStorePostgresTest`** — **DEFERRED.** H2 `MODE=PostgreSQL` provides adequate coverage for the initial implementation. A future issue will introduce PostgreSQL Testcontainers testing with `PostgresTestResource implements QuarkusTestResourceLifecycleManager` to validate real-world PostgreSQL dialect behavior (JSONB polymorphism, migration DDL, index creation).

**`InMemoryBridgeAuditStoreTest`** — existing test moved to `bridge-persistence-memory/`. Updated to cover `offset` pagination.

## Files

```
bridge-persistence-jpa/
  pom.xml
  src/main/java/io/casehub/iot/bridge/persistence/jpa/
    BridgeAuditJpaEntity.java
    BridgeAuditEventMapper.java
    JpaBridgeAuditStore.java
  src/main/resources/db/iot-bridge/migration/
    V1__create_bridge_audit_event.sql
  src/test/java/io/casehub/iot/bridge/persistence/jpa/
    BridgeAuditEventMapperTest.java
    JpaBridgeAuditStoreTest.java
    JpaBridgeAuditStorePostgresTest.java
    PostgresTestResource.java
  src/test/resources/
    application.properties

bridge-persistence-memory/
  pom.xml
  src/main/java/io/casehub/iot/bridge/persistence/memory/
    InMemoryAuditStoreConfig.java
    InMemoryBridgeAuditStore.java
  src/test/java/io/casehub/iot/bridge/persistence/memory/
    InMemoryBridgeAuditStoreTest.java
  src/test/resources/
    application.properties

bridge-server/ (modified)
  src/main/java/io/casehub/iot/bridge/server/audit/
    NoOpBridgeAuditStore.java              (new)
    InMemoryBridgeAuditStore.java          (deleted — moved to bridge-persistence-memory/)
  src/test/java/io/casehub/iot/bridge/server/audit/
    InMemoryBridgeAuditStoreTest.java      (deleted — moved to bridge-persistence-memory/)

api/ (modified)
  src/main/java/io/casehub/iot/api/bridge/
    BridgeAuditQuery.java                  (modified — add offset field)
```

Parent `pom.xml` updated: add `<module>bridge-persistence-jpa</module>`, `<module>bridge-persistence-memory</module>`, and both artifacts to `<dependencyManagement>`.

`iot-bridge/pom.xml` updated: add `casehub-iot-bridge-persistence-memory` dependency so the Docker bridge image retains in-memory audit after the `@DefaultBean` removal from `bridge-server/`.

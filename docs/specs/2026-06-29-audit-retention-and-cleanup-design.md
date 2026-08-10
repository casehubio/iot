# Audit Retention and Persistence Cleanup

**Issues:** #40 (data retention strategy), #41 (bridge-persistence minor review findings)
**Date:** 2026-06-29
**Module:** `bridge-persistence-jpa`, `bridge-persistence-memory`

---

## #40 — JPA Audit Data Retention

### Problem

The JPA BridgeAuditStore grows without bound. At 100 events/hour this produces ~876K rows/year with JSONB payloads reaching gigabytes. Retention requirements are deployment-specific — some operators want 90 days, some want 7 years.

### Design

A `@Scheduled` purge job co-located in `bridge-persistence-jpa`. Each store module owns its own retention mechanism — no shared SPI abstraction. A future MongoDB store would use native TTL indexes; the in-memory store already evicts on size.

#### Configuration

`JpaAuditRetentionConfig` — `@ConfigMapping(prefix = "casehub.iot.bridge.audit-store.jpa")`

| Property | Type | Default | Behaviour |
|----------|------|---------|-----------|
| `retention-days` | `Optional<Integer>` | absent | Absent = no purge (disabled by default). When present, must be ≥ 1. |
| `purge-interval` | `Duration` | `24h` | How often the purge job runs (via `@Scheduled` expression fallback) |

**Validation:** `JpaAuditRetentionJob` constructor validates `retention-days` when present: values ≤ 0 throw `IllegalArgumentException` with a clear message. This fails fast at startup — an operator who sets `retention-days=0` (would delete all data every run) or `-1` (semantically meaningless) gets an immediate error rather than silent data destruction.

#### Purge Job

`JpaAuditRetentionJob` — `@ApplicationScoped`

- Constructor-injected: `EntityManager`, `JpaAuditRetentionConfig`
- Constructor validates: if `retentionDays` is present and ≤ 0, throw `IllegalArgumentException`
- `@Scheduled(every = "${casehub.iot.bridge.audit-store.jpa.purge-interval:24h}", concurrentExecution = SKIP)`
- If `retentionDays` is empty: return immediately (no-op)
- Otherwise: `DELETE FROM BridgeAuditJpaEntity e WHERE e.receivedAt < :cutoff` as bulk JPQL
- `@Transactional`
- Logs deleted count at INFO, no-op at DEBUG
- Logs WARN when deleted count exceeds 10,000 rows (signals first-run catch-up or aggressive retention tightening — informational, not an error)

**First-run consideration:** When retention is first enabled on a long-running deployment, the initial purge may delete a large number of rows in a single transaction. At the expected volume (~876K rows/year), PostgreSQL handles this in seconds. Concurrent `store.save()` calls are unaffected — they INSERT new rows while the purge DELETEs old ones, operating on disjoint row sets with no lock contention under MVCC. The WARN log on large deletes provides operational visibility. Batched deletion is not warranted at this scale.

**Scope:** Retention is per-deployment, not per-tenant. The purge job deletes `WHERE received_at < :cutoff` with no tenant filter — a single retention policy applies to all tenants sharing a bridge-server database. Multi-tenant retention (per-tenancy `retention-days`) requires per-tenancy configuration and is deferred.

#### Database Migration

`V2__add_purge_index.sql` — Flyway migration adding a dedicated index for the purge query:

```sql
CREATE INDEX idx_bridge_audit_received_at ON bridge_audit_event (received_at);
```

The existing composite index `(tenancy_id, received_at DESC)` cannot serve a query filtering on `received_at` alone — PostgreSQL requires the leading column in the WHERE clause. Without this index, the purge job performs a full table scan that degrades linearly with table size.

**Partitioning:** PostgreSQL table partitioning by `received_at` month (listed as optional in #40) is deferred. See #42. The current index-based approach is sufficient for the expected volume. Partitioning becomes relevant at multi-year retention with millions of rows, where `DROP PARTITION` replaces row-level DELETEs entirely.

#### Test

`JpaAuditRetentionJobTest` — `@QuarkusTest`. Call purge method directly (not via scheduler).

- **No-op when disabled:** With `retention-days` absent (default config), insert events, call purge, assert all events are preserved and zero rows deleted. This is the most important safety test — a regression here silently enables purging on every deployment that hasn't opted in.
- **Retention active:** With config override `retention-days=1`, insert events with timestamps older and newer than the cutoff, call purge, assert old events deleted and recent events retained.
- **Validation:** Verify `IllegalArgumentException` on `retention-days=0`.

---

## #41 — Bridge-Persistence Minor Review Findings

### Finding 1 — Strengthen `queryRespectsOffset()` assertion

**File:** `InMemoryBridgeAuditStoreTest.queryRespectsOffset()`

Current: asserts only the first result's `deviceId`.
Change to: `assertThat(results).extracting(BridgeAuditEvent::deviceId).containsExactly("d6", "d5", "d4")` — matches the JPA test's full assertion style.

### Finding 2 — Align `quarkus-junit5` to `quarkus-junit`

**Files:** `bridge-persistence-jpa/pom.xml`, `bridge-persistence-memory/pom.xml`

Replace `quarkus-junit5` artifact with `quarkus-junit` to match every other module in the project. No code changes required — `@QuarkusTest` and JUnit 5 APIs work with both artifacts.

### Finding 3 — JPA metamodel for type-safe Criteria API

**File:** `bridge-persistence-jpa/pom.xml`, `JpaBridgeAuditStore.java`

Add `hibernate-jpamodelgen` annotation processor to `bridge-persistence-jpa/pom.xml`:

```xml
<dependency>
    <groupId>org.hibernate.orm</groupId>
    <artifactId>hibernate-jpamodelgen</artifactId>
    <scope>provided</scope>
</dependency>
```

The `provided` scope makes the annotation processor available at compile time (Maven discovers it automatically from the classpath) without including it in the runtime classpath. This is the standard Quarkus 3.x / Hibernate 6+ approach — no `annotationProcessorPaths` configuration needed.

Replace all string-based attribute references in `JpaBridgeAuditStore`:

| Before | After |
|--------|-------|
| `root.get("tenancyId")` | `root.get(BridgeAuditJpaEntity_.tenancyId)` |
| `root.get("receivedAt")` | `root.get(BridgeAuditJpaEntity_.receivedAt)` |
| `root.get("eventType")` | `root.get(BridgeAuditJpaEntity_.eventType)` |
| `root.get("deviceId")` | `root.get(BridgeAuditJpaEntity_.deviceId)` |
| `root.get("correlationId")` | `root.get(BridgeAuditJpaEntity_.correlationId)` |

Compile-time safety — attribute renames caught by the compiler, not at runtime.

### Finding 4 — PostgreSQL Testcontainers test

**File:** new `JpaBridgeAuditStorePostgresTest` in `bridge-persistence-jpa`

`@QuarkusTest` with `@TestProfile` overriding the datasource to a Testcontainers PostgreSQL instance. Runs core assertions (save, query, offset, JSONB round-trip) against real PostgreSQL to validate JSONB column mapping and dialect behaviour that H2's `MODE=PostgreSQL` cannot cover.

**Dependencies** (test scope in `bridge-persistence-jpa/pom.xml`):
- `io.quarkus:quarkus-test-common` (if not already present)
- `org.testcontainers:postgresql`
- `org.testcontainers:junit-jupiter`

---

## Protocol

**Store-owned retention:** Each `BridgeAuditStore` implementation owns its own data retention mechanism. No shared retention SPI — each mechanism matches its storage backend's strengths:

- **JPA:** `retention-days` (time-based TTL via `@Scheduled` purge job)
- **In-memory:** `max-size` (bounded ring buffer eviction by count)
- **MongoDB (future):** native TTL indexes (database-managed expiry)

No cross-store property name consistency is imposed. Each store uses the retention concept natural to its backend.

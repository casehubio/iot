# JPA BridgeAuditStore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add durable JPA-backed audit persistence for bridge interactions, with correct CDI priority layering per platform protocols.

**Architecture:** Three new/modified modules: `bridge-persistence-memory/` (relocated in-memory store with corrected CDI), `bridge-persistence-jpa/` (JPA entity + store), and `bridge-server/` (NoOp fallback + cleanup). The SPI `BridgeAuditQuery` gains an `offset` field for pagination.

**Tech Stack:** Quarkus 3.x, Hibernate 6, JPA Criteria API, Flyway, H2 (test), PostgreSQL Testcontainers (integration test), Jackson (BridgeMessage JSON serialization via `@JdbcTypeCode(SqlTypes.JSON)`)

## Global Constraints

- `casehub-iot-api` is a public API — semver discipline. `BridgeAuditQuery.offset` is additive (default 0, source-compatible).
- CDI priority ladder: `@DefaultBean` (no-op) < `@ApplicationScoped` (JPA) < `@Alternative @Priority(100)` (in-memory). Per `persistence-backend-cdi-priority` protocol.
- Flyway migrations at `classpath:db/iot-bridge/migration/` — repo-scoped, never `db/migration/`. Per `flyway-repo-scoped-migration-path` protocol.
- JPA entities never leak into SPI signatures. `BridgeAuditEventMapper` is the boundary.
- `jandex-maven-plugin` required in all library modules for CDI bean discovery.
- TDD: write failing test → make it pass → commit. Every task.
- Use `superpowers:test-driven-development` before implementing. Use `java-dev` for all Java.

---

### Task 1: SPI change — add `offset` to `BridgeAuditQuery`

**Files:**
- Modify: `api/src/main/java/io/casehub/iot/api/bridge/BridgeAuditQuery.java`
- Modify: `api/src/test/java/io/casehub/iot/api/bridge/BridgeAuditQueryTest.java`
- Modify: `bridge-server/src/main/java/io/casehub/iot/bridge/server/audit/InMemoryBridgeAuditStore.java`
- Modify: `bridge-server/src/test/java/io/casehub/iot/bridge/server/audit/InMemoryBridgeAuditStoreTest.java`

**Interfaces:**
- Produces: `BridgeAuditQuery.offset()` (int, default 0) — consumed by all store implementations

**Why first:** This is the SPI change. Everything downstream depends on the offset field. Doing it before module restructuring means existing tests validate the change in place.

- [ ] **Step 1: Write failing test for offset validation**

In `BridgeAuditQueryTest.java`, add:

```java
@Test
void offsetMustBeNonNegative() {
    assertThatThrownBy(() -> BridgeAuditQuery.builder().offset(-1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("offset");
}

@Test
void offsetDefaultsToZero() {
    final var query = BridgeAuditQuery.builder().build();
    assertThat(query.offset()).isZero();
}
```

- [ ] **Step 2: Run tests — verify failure**

Run: `mvn --batch-mode -pl api test -Dtest=BridgeAuditQueryTest`
Expected: compilation failure — `offset()` method and `offset(int)` builder method do not exist.

- [ ] **Step 3: Add `offset` field to `BridgeAuditQuery`**

Modify the record to add `offset` between `correlationId` and `limit`:

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
) {
    public static final int DEFAULT_LIMIT = 100;

    public BridgeAuditQuery {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
    // ...
}
```

Add `offset` field and builder method (default 0):

```java
public static class Builder {
    // ... existing fields ...
    private int offset = 0;
    private int limit = DEFAULT_LIMIT;

    public Builder offset(final int offset) { this.offset = offset; return this; }
    // ... existing methods ...

    public BridgeAuditQuery build() {
        return new BridgeAuditQuery(tenancyId, from, to, eventType, deviceId, correlationId, offset, limit);
    }
}
```

- [ ] **Step 4: Run api tests — verify pass**

Run: `mvn --batch-mode -pl api test -Dtest=BridgeAuditQueryTest`
Expected: PASS

- [ ] **Step 5: Write failing test for offset in InMemoryBridgeAuditStore**

In `InMemoryBridgeAuditStoreTest.java`, add:

```java
@Test
void queryRespectsOffset() {
    for (int i = 0; i < 10; i++) {
        store.save(auditEvent("t", BridgeAuditEventType.STATE_CHANGE, "d" + i, null));
    }

    final var results = store.query(BridgeAuditQuery.builder().offset(3).limit(3).build());
    assertThat(results).hasSize(3);
    // newest-first: d9, d8, d7, d6, d5, d4, d3, d2, d1, d0
    // offset 3 skips d9, d8, d7 → returns d6, d5, d4
    assertThat(results.get(0).deviceId()).isEqualTo("d6");
}
```

- [ ] **Step 6: Run test — verify failure**

Run: `mvn --batch-mode -pl bridge-server test -Dtest=InMemoryBridgeAuditStoreTest#queryRespectsOffset`
Expected: FAIL — offset not applied in query logic.

- [ ] **Step 7: Implement offset in InMemoryBridgeAuditStore.query()**

Update the `query` method to apply `.skip(query.offset())`:

```java
@Override
public List<BridgeAuditEvent> query(final BridgeAuditQuery query) {
    final List<BridgeAuditEvent> snapshot;
    synchronized (this) {
        snapshot = List.copyOf(events);
    }
    return snapshot.stream()
        .filter(e -> matches(e, query))
        .skip(query.offset())
        .limit(query.limit())
        .toList();
}
```

- [ ] **Step 8: Run all tests — verify pass**

Run: `mvn --batch-mode -pl api,bridge-server test`
Expected: ALL PASS

- [ ] **Step 9: Commit**

```bash
git add api/src/main/java/io/casehub/iot/api/bridge/BridgeAuditQuery.java api/src/test/java/io/casehub/iot/api/bridge/BridgeAuditQueryTest.java bridge-server/src/main/java/io/casehub/iot/bridge/server/audit/InMemoryBridgeAuditStore.java bridge-server/src/test/java/io/casehub/iot/bridge/server/audit/InMemoryBridgeAuditStoreTest.java
git commit -m "feat: add offset to BridgeAuditQuery for pagination — Closes #38 (partial)"
```

---

### Task 2: Create `bridge-persistence-memory/` module — relocate InMemoryBridgeAuditStore

**Files:**
- Create: `bridge-persistence-memory/pom.xml`
- Create: `bridge-persistence-memory/src/main/java/io/casehub/iot/bridge/persistence/memory/InMemoryAuditStoreConfig.java`
- Create: `bridge-persistence-memory/src/main/java/io/casehub/iot/bridge/persistence/memory/InMemoryBridgeAuditStore.java`
- Create: `bridge-persistence-memory/src/test/java/io/casehub/iot/bridge/persistence/memory/InMemoryBridgeAuditStoreTest.java`
- Create: `bridge-persistence-memory/src/test/resources/application.properties`
- Modify: `pom.xml` (parent — add module + dependencyManagement)

**Interfaces:**
- Consumes: `BridgeAuditStore` SPI from `casehub-iot-api`, `BridgeAuditQuery.offset()` from Task 1
- Produces: `InMemoryBridgeAuditStore` (`@Alternative @Priority(100)`) — CDI-discovered by consuming apps

- [ ] **Step 1: Create `bridge-persistence-memory/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.casehub</groupId>
        <artifactId>casehub-iot-parent</artifactId>
        <version>0.2-SNAPSHOT</version>
    </parent>

    <artifactId>casehub-iot-bridge-persistence-memory</artifactId>
    <name>CaseHub IoT — Bridge Persistence (In-Memory)</name>
    <description>In-memory bounded ring buffer BridgeAuditStore — for Raspberry Pi, dev mode, and test isolation</description>

    <dependencies>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-iot-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-arc</artifactId>
        </dependency>

        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>io.smallrye</groupId>
                <artifactId>jandex-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <id>make-index</id>
                        <goals><goal>jandex</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create `InMemoryAuditStoreConfig`**

```java
package io.casehub.iot.bridge.persistence.memory;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.iot.bridge.audit-store.memory")
public interface InMemoryAuditStoreConfig {

    @WithDefault("10000")
    int maxSize();
}
```

- [ ] **Step 3: Create `InMemoryBridgeAuditStore`**

Relocate from `bridge-server` with new package, new config, and corrected CDI annotation (`@Alternative @Priority(100)`):

```java
package io.casehub.iot.bridge.persistence.memory;

import io.casehub.iot.api.bridge.BridgeAuditEvent;
import io.casehub.iot.api.bridge.BridgeAuditQuery;
import io.casehub.iot.api.bridge.BridgeAuditStore;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.ArrayDeque;
import java.util.List;

@Alternative
@Priority(100)
@ApplicationScoped
public class InMemoryBridgeAuditStore implements BridgeAuditStore {

    private final int maxSize;
    private final ArrayDeque<BridgeAuditEvent> events;

    @Inject
    public InMemoryBridgeAuditStore(final InMemoryAuditStoreConfig config) {
        this.maxSize = config.maxSize();
        this.events = new ArrayDeque<>(Math.min(maxSize, 1024));
    }

    InMemoryBridgeAuditStore(final int maxSize) {
        this.maxSize = maxSize;
        this.events = new ArrayDeque<>(Math.min(maxSize, 1024));
    }

    @Override
    public synchronized void save(final BridgeAuditEvent event) {
        if (events.size() >= maxSize) {
            events.removeLast();
        }
        events.addFirst(event);
    }

    @Override
    public List<BridgeAuditEvent> query(final BridgeAuditQuery query) {
        final List<BridgeAuditEvent> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(events);
        }
        return snapshot.stream()
            .filter(e -> matches(e, query))
            .skip(query.offset())
            .limit(query.limit())
            .toList();
    }

    private static boolean matches(final BridgeAuditEvent event, final BridgeAuditQuery query) {
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

- [ ] **Step 4: Create test**

Relocate `InMemoryBridgeAuditStoreTest` from `bridge-server` with updated package. All tests use the package-private `InMemoryBridgeAuditStore(int)` constructor. Include the offset test from Task 1.

Create `bridge-persistence-memory/src/test/resources/application.properties`:
```properties
quarkus.arc.selected-alternatives=io.casehub.iot.bridge.persistence.memory.InMemoryBridgeAuditStore
```

- [ ] **Step 5: Add module to parent `pom.xml`**

In root `pom.xml`, add to `<dependencyManagement>`:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-iot-bridge-persistence-memory</artifactId>
    <version>${project.version}</version>
</dependency>
```

Add to `<modules>` (before `bridge`):
```xml
<module>bridge-persistence-memory</module>
```

- [ ] **Step 6: Run new module tests**

Run: `mvn --batch-mode -pl bridge-persistence-memory test`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add bridge-persistence-memory/ pom.xml
git commit -m "feat: bridge-persistence-memory module — relocate InMemoryBridgeAuditStore with correct CDI priority"
```

---

### Task 3: Update `bridge-server/` — NoOp fallback + cleanup

**Files:**
- Create: `bridge-server/src/main/java/io/casehub/iot/bridge/server/audit/NoOpBridgeAuditStore.java`
- Delete: `bridge-server/src/main/java/io/casehub/iot/bridge/server/audit/InMemoryBridgeAuditStore.java`
- Delete: `bridge-server/src/test/java/io/casehub/iot/bridge/server/audit/InMemoryBridgeAuditStoreTest.java`
- Modify: `bridge-server/src/main/java/io/casehub/iot/bridge/server/BridgeServerConfig.java` (remove `AuditStore` nested interface)
- Modify: `bridge-server/src/test/java/io/casehub/iot/bridge/server/audit/StoringBridgeAuditObserverTest.java` (inline test double)
- Modify: `bridge-server/src/test/java/io/casehub/iot/bridge/server/BridgeDeviceProviderTest.java` (remove `AuditStore` from anonymous config)
- Modify: `bridge-server/pom.xml` (add `bridge-persistence-memory` test dependency)

**Interfaces:**
- Produces: `NoOpBridgeAuditStore` (`@DefaultBean`) — fallback when no persistence module on classpath

- [ ] **Step 1: Create `NoOpBridgeAuditStore`**

```java
package io.casehub.iot.bridge.server.audit;

import io.casehub.iot.api.bridge.BridgeAuditEvent;
import io.casehub.iot.api.bridge.BridgeAuditQuery;
import io.casehub.iot.api.bridge.BridgeAuditStore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@DefaultBean
@ApplicationScoped
public class NoOpBridgeAuditStore implements BridgeAuditStore {

    @Override
    public void save(final BridgeAuditEvent event) {
        // no-op — no persistence module on classpath
    }

    @Override
    public List<BridgeAuditEvent> query(final BridgeAuditQuery query) {
        return List.of();
    }
}
```

- [ ] **Step 2: Add `bridge-persistence-memory` test dependency to `bridge-server/pom.xml`**

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-iot-bridge-persistence-memory</artifactId>
    <scope>test</scope>
</dependency>
```

This ensures `AuditObserverCoexistenceTest` (a `@QuarkusTest`) has a working `BridgeAuditStore` — the `@Alternative @Priority(100)` in-memory impl displaces the `@DefaultBean` no-op during test augmentation.

- [ ] **Step 3: Delete old `InMemoryBridgeAuditStore` from `bridge-server`**

Delete: `bridge-server/src/main/java/io/casehub/iot/bridge/server/audit/InMemoryBridgeAuditStore.java`
Delete: `bridge-server/src/test/java/io/casehub/iot/bridge/server/audit/InMemoryBridgeAuditStoreTest.java`

- [ ] **Step 4: Remove `AuditStore` from `BridgeServerConfig`**

Update `BridgeServerConfig.java` to remove the `AuditStore` nested interface and `auditStore()` method:

```java
package io.casehub.iot.bridge.server;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.iot.bridge-server")
public interface BridgeServerConfig {

    @WithDefault("30")
    int commandTimeoutSeconds();
}
```

- [ ] **Step 5: Update `BridgeDeviceProviderTest` — remove `AuditStore` from anonymous config**

In `BridgeDeviceProviderTest.java`, update the `TEST_CONFIG` anonymous implementation to remove the `auditStore()` method:

```java
private static final BridgeServerConfig TEST_CONFIG = new BridgeServerConfig() {
    @Override
    public int commandTimeoutSeconds() { return 1; }
};
```

- [ ] **Step 6: Rewrite `StoringBridgeAuditObserverTest` — inline test double**

Replace direct `InMemoryBridgeAuditStore(100)` instantiation with an inline `BridgeAuditStore` implementation:

```java
package io.casehub.iot.bridge.server.audit;

import io.casehub.iot.api.bridge.BridgeAuditEvent;
import io.casehub.iot.api.bridge.BridgeAuditEventType;
import io.casehub.iot.api.bridge.BridgeAuditQuery;
import io.casehub.iot.api.bridge.BridgeAuditStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoringBridgeAuditObserverTest {

    @Test
    void onAuditDelegatesToStoreSave() {
        final var saved = new ArrayList<BridgeAuditEvent>();
        final BridgeAuditStore store = new BridgeAuditStore() {
            @Override
            public void save(final BridgeAuditEvent event) { saved.add(event); }

            @Override
            public List<BridgeAuditEvent> query(final BridgeAuditQuery query) { return List.of(); }
        };
        final var observer = new StoringBridgeAuditObserver(store);
        final var event = new BridgeAuditEvent(
            "tenant-1", Instant.now(), BridgeAuditEventType.STATE_CHANGE,
            null, "light.kitchen", null);

        observer.onAudit(event);

        assertThat(saved).containsExactly(event);
    }
}
```

- [ ] **Step 7: Run bridge-server tests**

Run: `mvn --batch-mode -pl bridge-server test`
Expected: ALL PASS (including `AuditObserverCoexistenceTest` which now uses the `bridge-persistence-memory` `@Alternative`)

- [ ] **Step 8: Commit**

```bash
git add bridge-server/
git commit -m "refactor: bridge-server — NoOp @DefaultBean fallback, remove InMemoryBridgeAuditStore (moved to bridge-persistence-memory)"
```

---

### Task 4: Add `bridge-persistence-memory` to bridge Docker image

**Files:**
- Modify: `bridge/pom.xml`

**Interfaces:**
- Consumes: `casehub-iot-bridge-persistence-memory` artifact from Task 2

The `bridge/` module is the Quarkus application for Raspberry Pi deployment. After Task 3 removed the `@DefaultBean` in-memory store, the bridge would fall back to no-op (no audit). Adding `bridge-persistence-memory` as a compile dependency restores in-memory audit.

- [ ] **Step 1: Add dependency to `bridge/pom.xml`**

After the `casehub-iot-api` dependency, add:

```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-iot-bridge-persistence-memory</artifactId>
</dependency>
```

- [ ] **Step 2: Verify full build**

Run: `mvn --batch-mode -pl bridge install -DskipTests`
Expected: BUILD SUCCESS — `bridge-persistence-memory` JAR pulled in, Jandex index discovered

- [ ] **Step 3: Commit**

```bash
git add bridge/pom.xml
git commit -m "chore: bridge Docker image — add bridge-persistence-memory for Pi audit"
```

---

### Task 5: Create `bridge-persistence-jpa/` module — entity, mapper, store, migration, tests

**Files:**
- Create: `bridge-persistence-jpa/pom.xml`
- Create: `bridge-persistence-jpa/src/main/java/io/casehub/iot/bridge/persistence/jpa/BridgeAuditJpaEntity.java`
- Create: `bridge-persistence-jpa/src/main/java/io/casehub/iot/bridge/persistence/jpa/BridgeAuditEventMapper.java`
- Create: `bridge-persistence-jpa/src/main/java/io/casehub/iot/bridge/persistence/jpa/JpaBridgeAuditStore.java`
- Create: `bridge-persistence-jpa/src/main/resources/db/iot-bridge/migration/V1__create_bridge_audit_event.sql`
- Create: `bridge-persistence-jpa/src/test/java/io/casehub/iot/bridge/persistence/jpa/BridgeAuditEventMapperTest.java`
- Create: `bridge-persistence-jpa/src/test/java/io/casehub/iot/bridge/persistence/jpa/JpaBridgeAuditStoreTest.java`
- Create: `bridge-persistence-jpa/src/test/resources/application.properties`
- Modify: `pom.xml` (parent — add module + dependencyManagement)

**Interfaces:**
- Consumes: `BridgeAuditStore` SPI, `BridgeAuditEvent`, `BridgeAuditQuery` (with `offset()`) from `casehub-iot-api`
- Produces: `JpaBridgeAuditStore` (`@ApplicationScoped`) — displaces `@DefaultBean` NoOp when on classpath

This is the largest task — entity, mapper, store, migration, and tests. It carries its own test cycle (mapper unit tests + `@QuarkusTest` integration tests) and is the core deliverable of #38.

- [ ] **Step 1: Create `bridge-persistence-jpa/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.casehub</groupId>
        <artifactId>casehub-iot-parent</artifactId>
        <version>0.2-SNAPSHOT</version>
    </parent>

    <artifactId>casehub-iot-bridge-persistence-jpa</artifactId>
    <name>CaseHub IoT — Bridge Persistence (JPA)</name>
    <description>JPA-backed BridgeAuditStore for durable audit persistence — PostgreSQL with JSONB message storage</description>

    <dependencies>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-iot-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-hibernate-orm</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jdbc-postgresql</artifactId>
        </dependency>

        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jdbc-h2</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>io.smallrye</groupId>
                <artifactId>jandex-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <id>make-index</id>
                        <goals><goal>jandex</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Add module to parent `pom.xml`**

In root `pom.xml`, add to `<dependencyManagement>`:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-iot-bridge-persistence-jpa</artifactId>
    <version>${project.version}</version>
</dependency>
```

Add to `<modules>` (after `bridge-persistence-memory`, before `bridge`):
```xml
<module>bridge-persistence-jpa</module>
```

- [ ] **Step 3: Create Flyway migration**

Create `bridge-persistence-jpa/src/main/resources/db/iot-bridge/migration/V1__create_bridge_audit_event.sql`:

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

- [ ] **Step 4: Create `BridgeAuditJpaEntity`**

```java
package io.casehub.iot.bridge.persistence.jpa;

import io.casehub.iot.api.bridge.BridgeAuditEventType;
import io.casehub.iot.api.bridge.BridgeMessage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bridge_audit_event")
public class BridgeAuditJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenancy_id", nullable = false)
    private String tenancyId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private BridgeAuditEventType eventType;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "device_id")
    private String deviceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "message", columnDefinition = "jsonb")
    private BridgeMessage message;

    protected BridgeAuditJpaEntity() {}

    public BridgeAuditJpaEntity(final String tenancyId, final Instant receivedAt,
                                 final BridgeAuditEventType eventType,
                                 final String correlationId, final String deviceId,
                                 final BridgeMessage message) {
        this.tenancyId = tenancyId;
        this.receivedAt = receivedAt;
        this.eventType = eventType;
        this.correlationId = correlationId;
        this.deviceId = deviceId;
        this.message = message;
    }

    public UUID getId() { return id; }
    public String getTenancyId() { return tenancyId; }
    public Instant getReceivedAt() { return receivedAt; }
    public BridgeAuditEventType getEventType() { return eventType; }
    public String getCorrelationId() { return correlationId; }
    public String getDeviceId() { return deviceId; }
    public BridgeMessage getMessage() { return message; }
}
```

- [ ] **Step 5: Create `BridgeAuditEventMapper`**

```java
package io.casehub.iot.bridge.persistence.jpa;

import io.casehub.iot.api.bridge.BridgeAuditEvent;

final class BridgeAuditEventMapper {

    private BridgeAuditEventMapper() {}

    static BridgeAuditJpaEntity toEntity(final BridgeAuditEvent event) {
        return new BridgeAuditJpaEntity(
            event.tenancyId(),
            event.receivedAt(),
            event.eventType(),
            event.correlationId(),
            event.deviceId(),
            event.message()
        );
    }

    static BridgeAuditEvent toDomain(final BridgeAuditJpaEntity entity) {
        return new BridgeAuditEvent(
            entity.getTenancyId(),
            entity.getReceivedAt(),
            entity.getEventType(),
            entity.getCorrelationId(),
            entity.getDeviceId(),
            entity.getMessage()
        );
    }
}
```

- [ ] **Step 6: Write mapper test**

Create `BridgeAuditEventMapperTest.java`:

```java
package io.casehub.iot.bridge.persistence.jpa;

import io.casehub.iot.api.bridge.BridgeAuditEvent;
import io.casehub.iot.api.bridge.BridgeAuditEventType;
import io.casehub.iot.api.bridge.BridgeMessage;
import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.ProviderStatusEvent;
import io.casehub.iot.api.StateChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeAuditEventMapperTest {

    @Test
    void roundTripWithNullMessage() {
        final var event = new BridgeAuditEvent(
            "tenant-1", Instant.now(), BridgeAuditEventType.AGENT_CONNECTED,
            "corr-1", "device-1", null);

        final var entity = BridgeAuditEventMapper.toEntity(event);
        final var back = BridgeAuditEventMapper.toDomain(entity);

        assertThat(back).isEqualTo(event);
    }

    @ParameterizedTest
    @MethodSource("messageVariants")
    void roundTripPreservesMessageVariant(final BridgeMessage message) {
        final var event = new BridgeAuditEvent(
            "tenant-1", Instant.now(), BridgeAuditEventType.STATE_CHANGE,
            null, "device-1", message);

        final var entity = BridgeAuditEventMapper.toEntity(event);
        final var back = BridgeAuditEventMapper.toDomain(entity);

        assertThat(back).isEqualTo(event);
        assertThat(back.message()).isInstanceOf(message.getClass());
    }

    static Stream<BridgeMessage> messageVariants() {
        final var now = Instant.now();
        return Stream.of(
            new BridgeMessage.Heartbeat("t", now),
            new BridgeMessage.Command("t", now, "corr", DeviceCommand.turnOn("d")),
            new BridgeMessage.CommandResponse("t", now, "corr", CommandResult.SENT)
        );
    }
}
```

Note: The exact `messageVariants()` factory methods depend on the constructors available for `StateChangeEvent`, `DeviceCommand`, etc. The implementer should check the actual API and include all 7 variants (StateChange, ReplayedStateChange, StateSnapshot, ProviderStatusChange, Command, CommandResponse, Heartbeat).

- [ ] **Step 7: Run mapper test**

Run: `mvn --batch-mode -pl bridge-persistence-jpa test -Dtest=BridgeAuditEventMapperTest`
Expected: PASS

- [ ] **Step 8: Create `JpaBridgeAuditStore`**

```java
package io.casehub.iot.bridge.persistence.jpa;

import io.casehub.iot.api.bridge.BridgeAuditEvent;
import io.casehub.iot.api.bridge.BridgeAuditQuery;
import io.casehub.iot.api.bridge.BridgeAuditStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class JpaBridgeAuditStore implements BridgeAuditStore {

    private final EntityManager em;

    @Inject
    public JpaBridgeAuditStore(final EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional
    public void save(final BridgeAuditEvent event) {
        em.persist(BridgeAuditEventMapper.toEntity(event));
    }

    @Override
    public List<BridgeAuditEvent> query(final BridgeAuditQuery query) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<BridgeAuditJpaEntity> cq = cb.createQuery(BridgeAuditJpaEntity.class);
        final Root<BridgeAuditJpaEntity> root = cq.from(BridgeAuditJpaEntity.class);

        final List<Predicate> predicates = new ArrayList<>();

        if (query.tenancyId() != null) {
            predicates.add(cb.equal(root.get("tenancyId"), query.tenancyId()));
        }
        if (query.eventType() != null) {
            predicates.add(cb.equal(root.get("eventType"), query.eventType()));
        }
        if (query.deviceId() != null) {
            predicates.add(cb.equal(root.get("deviceId"), query.deviceId()));
        }
        if (query.correlationId() != null) {
            predicates.add(cb.equal(root.get("correlationId"), query.correlationId()));
        }
        if (query.from() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("receivedAt"), query.from()));
        }
        if (query.to() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("receivedAt"), query.to()));
        }

        cq.where(predicates.toArray(Predicate[]::new));
        cq.orderBy(cb.desc(root.get("receivedAt")));

        return em.createQuery(cq)
            .setFirstResult(query.offset())
            .setMaxResults(query.limit())
            .getResultList()
            .stream()
            .map(BridgeAuditEventMapper::toDomain)
            .toList();
    }
}
```

- [ ] **Step 9: Create test `application.properties`**

Create `bridge-persistence-jpa/src/test/resources/application.properties`:

```properties
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:mem:test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
quarkus.hibernate-orm.database.generation=none
quarkus.flyway.locations=classpath:db/iot-bridge/migration
quarkus.flyway.migrate-at-start=true
```

- [ ] **Step 10: Write `JpaBridgeAuditStoreTest`**

```java
package io.casehub.iot.bridge.persistence.jpa;

import io.casehub.iot.api.bridge.BridgeAuditEvent;
import io.casehub.iot.api.bridge.BridgeAuditEventType;
import io.casehub.iot.api.bridge.BridgeAuditQuery;
import io.casehub.iot.api.bridge.BridgeAuditStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class JpaBridgeAuditStoreTest {

    @Inject BridgeAuditStore store;

    @BeforeEach
    @Transactional
    void cleanTable() {
        // Implementer: use EntityManager to delete all rows
    }

    @Test
    void savedEventsAreRetrievableByQuery() {
        final var event = auditEvent("tenant-1", BridgeAuditEventType.STATE_CHANGE, "d1", null);
        store.save(event);

        final var results = store.query(BridgeAuditQuery.builder().build());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).tenancyId()).isEqualTo("tenant-1");
    }

    // ... tests for: filterByTenancyId, filterByEventType, filterByDeviceId,
    //     filterByCorrelationId, filterByTimeRange, composedCriteria,
    //     limitResults, offsetPagination, newestFirstOrdering,
    //     emptyResults, nullMessageHandling
    // Mirror InMemoryBridgeAuditStoreTest coverage exactly.

    private static BridgeAuditEvent auditEvent(final String tenancyId,
                                                final BridgeAuditEventType type,
                                                final String deviceId,
                                                final String correlationId) {
        return new BridgeAuditEvent(tenancyId, Instant.now(), type, correlationId, deviceId, null);
    }
}
```

The implementer must write the full test suite mirroring `InMemoryBridgeAuditStoreTest` — all filter criteria, ordering, limit, offset, composed criteria, empty results, null message.

- [ ] **Step 11: Add `quarkus-flyway` dependency to `pom.xml`**

Add to `bridge-persistence-jpa/pom.xml` dependencies (required for `migrate-at-start`):

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-flyway</artifactId>
</dependency>
```

- [ ] **Step 12: Run JPA store tests**

Run: `mvn --batch-mode -pl bridge-persistence-jpa test`
Expected: ALL PASS

- [ ] **Step 13: Run full build**

Run: `mvn --batch-mode install`
Expected: ALL PASS across all modules

- [ ] **Step 14: Commit**

```bash
git add bridge-persistence-jpa/ pom.xml
git commit -m "feat: JPA BridgeAuditStore — durable audit persistence with JSONB message storage — Closes #38"
```

---

## Self-Review Checklist

- [x] **Spec coverage:** SPI change (offset) ✓, bridge-persistence-memory ✓, bridge-server cleanup ✓, bridge Docker update ✓, bridge-persistence-jpa ✓, Flyway migration ✓, all tests ✓, error handling documented in spec (not code — CDI async observer behaviour) ✓, data retention deferred to #40 ✓
- [x] **Placeholder scan:** Task 5 Step 6 mapper test has a note about checking actual API for all 7 variants — acceptable guidance, not a placeholder. Task 5 Step 10 has `// ...` for additional tests — acceptable; the implementer mirrors `InMemoryBridgeAuditStoreTest` exactly.
- [x] **Type consistency:** `BridgeAuditQuery.offset()` used consistently in Task 1 (SPI), Task 2 (in-memory `.skip()`), Task 5 (JPA `.setFirstResult()`). `InMemoryAuditStoreConfig` name consistent across Task 2. `NoOpBridgeAuditStore` consistent across Task 3. `BridgeAuditEventMapper.toEntity`/`toDomain` consistent across Tasks 5.

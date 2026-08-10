# Audit Observer Coexistence Test Design

**Issue:** casehubio/iot#39
**Date:** 2026-06-28

## Problem

Unit tests for `StoringBridgeAuditObserver` and `LoggingBridgeAuditObserver` call
`onAudit()` directly — they prove each observer's behaviour in isolation but don't
verify CDI discovers and delivers to both when they coexist in the same container.

## Failure Modes Guarded Against

1. One observer not indexed by Jandex / not discovered by Arc
2. `@ObservesAsync` accidentally removed — bean exists but observer method not registered
3. Duplicate observer registration — Arc or Jandex bug registering an observer twice
4. Observer throws an exception — `fireAsync()` CompletionStage completes exceptionally
   (CDI 4.0 §10.5), surfacing `ExecutionException` before assertions run. All other
   observers still execute, but the test short-circuits on the exception. Distinguishable
   from modes 1–3: those produce assertion failures with `.as()` descriptions; this
   produces an exception with the throwing observer's stacktrace.

## Design

**Test class:** `AuditObserverCoexistenceTest` in
`bridge-server/src/test/java/io/casehub/iot/bridge/server/audit/`

**Annotations:** `@QuarkusTest` — boots full CDI container.

**Injected beans:**
- `Event<BridgeAuditEvent>` — CDI event source
- `BridgeAuditStore` — verify `StoringBridgeAuditObserver` ran

**Log capture:**
- `@BeforeEach`: install a `TestLogHandler` (JUL `Handler`) on logger category
  `io.casehub.iot.bridge.audit`
- Handler captures `LogRecord` objects into a `CopyOnWriteArrayList` — safe for
  cross-thread writes from the CDI async executor
- `@AfterEach`: remove the handler

**Test method — `bothObserversReceiveAsyncEvent()`:**
1. Construct `BridgeAuditEvent` with correlationId `"coexistence-test"` for isolation
2. `auditEvents.fireAsync(event).toCompletableFuture().get(5, SECONDS)`
3. Assert: `store.query(BridgeAuditQuery.builder().correlationId("coexistence-test").build())`
   returns exactly 1 result matching the fired event (`hasSize(1).containsExactly(event)`) —
   `containsExactly` validates record equality, confirming the store holds the exact event
   that was fired. With `InMemoryBridgeAuditStore` this is reference equality (no
   serialization round-trip); the assertion guards against a future store implementation
   that transforms events during save/query.
4. Assert: `logHandler.records` has exactly 1 entry (`hasSize(1)`)

**Assumptions:**
- `CompletionStage` from `Event.fireAsync()` completes only after all `@ObservesAsync`
  handlers finish (CDI 4.0 §10.3.2). This is what makes `get()` a valid synchronization
  point for asserting both observers' side effects — without it, the assertions would be racy.
  The 5-second timeout is a CI safety net, not the interesting part.
- JBoss Log Manager is the JUL `LogManager` in `@QuarkusTest`. `Logger.getLogger(category)`
  returns a `org.jboss.logmanager.Logger` (which extends `java.util.logging.Logger`), so
  `addHandler()` routes records from JBoss Logging → JBoss Log Manager → test handler.
- `InMemoryBridgeAuditStore` (`@DefaultBean @ApplicationScoped`) is the active
  `BridgeAuditStore` implementation — no other `BridgeAuditStore` bean exists on the
  `bridge-server` test classpath. `@DefaultBean` is displaced by any non-default bean
  of the same type, not just `@Alternative` beans.

**Key decisions:**
- Assert exact count (`hasSize(1)`) rather than presence — catches duplicate observer
  registration and guards against future `correlationId` collisions or logger category reuse.
  The log `hasSize(1)` assertion has an implicit coupling: it assumes no other code logs
  to `io.casehub.iot.bridge.audit` during the test. Currently only
  `LoggingBridgeAuditObserver` uses this category. A future category co-tenant would
  break the assertion with a misleading "duplicate registration?" diagnostic — accepted
  because the category is purpose-specific and a new co-tenant warrants review.
- Assert log record **presence**, not format — format is covered by `LoggingBridgeAuditObserverTest`.
  Handler stores `LogRecord` objects (not `getMessage()` strings) because JBoss Logging
  delegates to different backends: in plain JUnit (JDK backend), `infof()` pre-formats the
  message; in `@QuarkusTest` (JBoss Log Manager backend), `getMessage()` returns the
  unformatted `printf` template. Storing `LogRecord` objects and asserting only count
  keeps the assertion backend-agnostic.
- No store cleanup needed — `correlationId` filter isolates from other tests
- `TestLogHandler` duplicated (not shared) — differs from unit test handler in both shape
  (`LogRecord` list vs `String` list) and threading model (async CDI executor vs synchronous
  test thread). Two usages with different constraints doesn't justify extraction.
- Handler captures only events fired during the test method. No `BridgeAuditEvent` fires
  during CDI container bootstrap (audit events originate from `BridgeWebSocketEndpoint`
  WebSocket callbacks and `BridgeDeviceProvider.dispatch()`, neither of which executes
  at startup). If a future `@PostConstruct` or `@Observes StartupEvent` fires audit events,
  the handler would miss them while the store would capture them.
- Separate class from `BridgeIntegrationTest` — different concern (audit wiring vs device provider)

# OpenHAB Basic Auth Support — Design Spec

**Issue:** casehubio/iot#12  
**Date:** 2026-06-12  
**Scope:** `casehub-iot-openhab` module only

---

## Problem

The OpenHAB provider hardcodes Bearer token authentication. OpenHAB installations that use HTTP Basic auth (username/password) cannot be connected. Local installs without auth configured cannot connect at all — the current code requires a token.

## Design

### Config Structure

`OpenHabConfig` gains a nested `Auth` interface with two `Optional<>` groups — one per auth type. The top-level `token()` field is removed.

```java
@ConfigMapping(prefix = "casehub.iot.openhab")
public interface OpenHabConfig {
    String url();
    String tenancyId();
    Auth auth();
    @WithDefault("5")   int reconnectBaseSeconds();
    @WithDefault("300") int reconnectMaxSeconds();
    @WithDefault("50")  int coalesceWindowMs();

    interface Auth {
        Optional<Bearer> bearer();
        Optional<Basic> basic();

        interface Bearer { String token(); }
        interface Basic { String username(); String password(); }
    }
}
```

**Config keys:**

```properties
# Bearer:
casehub.iot.openhab.auth.bearer.token=xxx

# Basic:
casehub.iot.openhab.auth.basic.username=admin
casehub.iot.openhab.auth.basic.password=secret

# Anonymous (no auth.* properties) — no Authorization header sent
```

**Three valid states:**

| State | Behavior |
|-------|----------|
| No `auth.*` properties | Anonymous — no `Authorization` header sent |
| `auth.bearer.token` set | `Authorization: Bearer <token>` |
| `auth.basic.username` + `auth.basic.password` set | `Authorization: Basic <base64(user:pass)>` |

**Invalid state:** Both `bearer` and `basic` configured → fail-fast at startup.

SmallRye `Optional<NestedInterface>` handles presence detection: a group is present if any property under its prefix is configured; absent otherwise. `Auth auth()` is non-Optional — SmallRye constructs it structurally even when no `auth.*` properties exist, with both Optional children empty (anonymous mode).

Partial group configuration (e.g. `auth.basic.username` set without `password`) is caught by SmallRye's own validation — `password()` is a required `String` inside the `Basic` interface, so SmallRye fails at startup. The auth factory does not need to duplicate this check.

**SmallRye `Optional<NestedInterface>` caveat:** This is a less-traveled path in SmallRye Config. The first TDD test must verify that SmallRye 3.x correctly handles the `Optional<Bearer>` / `Optional<Basic>` presence detection for all three valid states and the invalid state. If SmallRye doesn't behave as expected, the fallback is flat `Optional<String>` leaf properties (`auth.bearer.token`, `auth.basic.username`, `auth.basic.password`) with programmatic grouping in the factory — same config keys, same user experience, less structured Java mapping.

### Auth Header Resolution

The duplicated `lookupToken()` default methods on `OpenHabRestClient` and `OpenHabSseRestClient` are replaced by a CDI-managed `ClientHeadersFactory`.

**`OpenHabAuthHeadersFactory`** — `@ApplicationScoped` bean implementing `ClientHeadersFactory`:

```java
@ApplicationScoped
public class OpenHabAuthHeadersFactory implements ClientHeadersFactory {

    @Inject OpenHabConfig config;

    private String authHeader;  // null = anonymous (no header)

    @PostConstruct
    void resolve() {
        var auth = config.auth();
        boolean hasBearer = auth.bearer().isPresent();
        boolean hasBasic = auth.basic().isPresent();

        if (hasBearer && hasBasic) {
            throw new IllegalStateException(
                "Configure either casehub.iot.openhab.auth.bearer or auth.basic, not both");
        }

        if (hasBasic) {
            var basic = auth.basic().get();
            authHeader = "Basic " + Base64.getEncoder().encodeToString(
                (basic.username() + ":" + basic.password()).getBytes(UTF_8));
        } else if (hasBearer) {
            authHeader = "Bearer " + auth.bearer().get().token();
        }
        // else: anonymous — authHeader stays null
    }

    @Override
    public MultivaluedMap<String, String> update(
            MultivaluedMap<String, String> incomingHeaders,
            MultivaluedMap<String, String> clientOutgoingHeaders) {
        MultivaluedMap<String, String> result = new MultivaluedHashMap<>();
        if (authHeader != null) {
            result.putSingle("Authorization", authHeader);
        }
        return result;
    }
}
```

**Design rationale:**

- **Single config read path.** The factory injects `OpenHabConfig` — the same CDI-managed SmallRye ConfigMapping used everywhere else. No raw `ConfigProvider.getConfig()` string-key lookups. If a property is renamed in the config interface, the compiler catches it.
- **Resolve once, not per-request.** Config is immutable at Quarkus runtime. The header is computed in `@PostConstruct` and cached. Zero per-request overhead.
- **Validation co-located with resolution.** The factory owns both validation (at most one auth type) and resolution (build the header string). Auth logic stays out of `OpenHabProvider`.
- **Testable via CDI injection.** No global config manipulation needed in tests — inject the factory with a test `OpenHabConfig`.

**REST client interfaces** — `@ClientHeaderParam` and `lookupToken()` / `lookupAuth()` default methods are removed entirely. Replaced by `@RegisterClientHeaders`:

```java
@RegisterRestClient(configKey = "openhab")
@RegisterClientHeaders(OpenHabAuthHeadersFactory.class)
public interface OpenHabRestClient {
    // Pure endpoint declarations — no auth logic
}

@RegisterRestClient(configKey = "openhab-sse")
@RegisterClientHeaders(OpenHabAuthHeadersFactory.class)
public interface OpenHabSseRestClient {
    // Pure endpoint declarations — no auth logic
}
```

Both interfaces get the same `@ApplicationScoped` factory instance (CDI manages the singleton). MicroProfile REST Client API 4.0 Javadoc confirms CDI injection support in `ClientHeadersFactory` implementations.

### Tests

- **Test config:** `casehub.iot.openhab.token` → `casehub.iot.openhab.auth.bearer.token` in test `application.properties`.
- **SmallRye config mapping integration test** (first TDD test): verify `Optional<Bearer>` / `Optional<Basic>` presence detection for all three valid states (anonymous, bearer, basic) and the invalid state (both present). If SmallRye doesn't cooperate, fall back to flat `Optional<String>` properties before proceeding.
- **`OpenHabAuthHeadersFactoryTest`:** Unit tests for the CDI bean — bearer produces correct header, basic produces correct Base64-encoded header, anonymous produces no header, both-present throws at construction.
- **`OpenHabMockServerResource` — header verification:** The mock server's `Dispatcher` validates the `Authorization` header on incoming requests via `RecordedRequest.getHeader("Authorization")`. Existing `@QuarkusTest` integration tests already exercise the full REST client → MockWebServer path — adding header verification closes the gap between "the factory produces the right string" and "the REST client actually sends it."
- **Existing tests:** No behavioral changes — continue exercising Bearer auth path via the new factory.

### Breaking Changes

- `OpenHabConfig.token()` removed — replaced by `OpenHabConfig.auth().bearer().get().token()`.
- Config key `casehub.iot.openhab.token` → `casehub.iot.openhab.auth.bearer.token`.
- `@ClientHeaderParam` and `lookupToken()` removed from both REST client interfaces.
- No external consumers — this is an internal provider module.

### Security Note

Basic auth sends credentials Base64-encoded (trivially decodable) on every request. For local OpenHAB installations on a trusted network this is typical and acceptable. For remote or internet-facing deployments, Basic auth should only be used over TLS.

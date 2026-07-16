# CBR Refinements Design

Covers #58 (retention/purge), #64 (temporal recency), #65 (situation surfacing).

## Dependencies

| Issue | Repo | Status |
|-------|------|--------|
| neocortex#150 | casehubio/neocortex | Done — `CbrRetentionPolicy`, `purge()` on `CbrCaseMemoryStore` |
| neocortex#151 | casehubio/neocortex | Done — `TemporalDecay` sealed interface, `temporalDecay` on `CbrQuery` |
| engine#733 | casehubio/engine | Pending — `temporalDecayHalfLifeDays` on `CbrConfig` |

## §1 — CBR Retention Job (#58)

**Module:** `webapp`

`CbrRetentionJob` — scheduled purge job using the same Quarkus `@Scheduled` + `SKIP` + skip-if-unconfigured + warn-at-threshold idiom as `StateHistoryRetentionJob`.

Injected dependencies (CDI):
- `CbrCaseMemoryStore` — via existing `IoTCbrRetrievalServiceProducer` producer
- `@ConfigProperty(name = "casehub.iot.tenancy-id") String tenancyId`
- `CbrRetentionConfig` — `@ConfigMapping`

### Config

```
casehub.iot.webapp.cbr.retention.max-age-days        # Optional<Integer> — skip if absent
casehub.iot.webapp.cbr.retention.max-cases-per-type   # Optional<Integer> — skip if absent
casehub.iot.webapp.cbr.retention.purge-interval       # Duration, @WithDefault("PT1H")
```

### Config mapping

`CbrRetentionConfig` — `@ConfigMapping(prefix = "casehub.iot.webapp.cbr.retention")` with `maxAgeDays` and `maxCasesPerType` as `Optional<Integer>`, `purgeInterval` as `Duration` with `@WithDefault("PT1H")`.

### Behaviour

- `@Scheduled(every = "${casehub.iot.webapp.cbr.retention.purge-interval:PT1H}", concurrentExecution = SKIP)`
- Skip silently if neither `max-age-days` nor `max-cases-per-type` is configured
- Build `CbrRetentionPolicy(tenantId, domain, caseType=null, maxAgeDays, maxCasesPerType)`
  - `tenantId` from `casehub.iot.tenancy-id`
  - `domain` = `MemoryDomain.of("iot")`
- Call `store.purge(policy)`, log deleted count
- Warning at 10,000+ deletions (matching existing retention job pattern)

### Global policy rationale

The policy uses `caseType=null` (applies across all case types in the `iot` domain). Per-case-type differentiated retention is deferred: the case base starts empty, there is no empirical data to calibrate per-type thresholds, and `CbrRetentionPolicy` already supports per-type purge — adding it later is additive, not a design change. See §Deferred Issues.

## §2 — Temporal Decay Integration (#64)

**Module:** `webapp-api` (Tier 1)

### Change

In `IoTCbrRetrievalService.retrieve()`, after building the `CbrQuery`, apply temporal decay if configured. Pending engine#733 — the following is pseudocode until `CbrConfig` gains the accessor:

```java
// Pending engine#733: add temporalDecayHalfLifeDays (Integer, nullable) to CbrConfig record,
// with Builder.temporalDecayHalfLifeDays(Integer) and YAML mapping in CaseDefinitionParser.
if (config.temporalDecayHalfLifeDays() != null) {
    query = query.withTemporalDecay(
        new TemporalDecay.HalfLife(Duration.ofDays(config.temporalDecayHalfLifeDays())));
}
```

`CbrQuery.withTemporalDecay(TemporalDecay)` already exists in neocortex-memory-api (neocortex#151). The store applies decay during `retrieveSimilar()`, after feature scoring but before `topK`/`minSimilarity` filtering.

### Why HalfLife only

The `TemporalDecay` sealed interface has three variants: `HalfLife`, `Linear`, `Step`. Only `HalfLife` is exposed via config because it is the natural fit for CBR recency: older cases gradually lose relevance with a smooth exponential curve. `Linear` drops to zero at a hard cutoff — a 91-day case scoring 0 when the cutoff is 90 days is undesirable. `Step` is binary (full weight or fixed fraction) and too coarse for continuous similarity scoring. Full `TemporalDecay` type support via config is deferred — see §Deferred Issues.

### Default

Existing case definitions have `temporalDecayHalfLifeDays = null` (no decay). Operators configure per case type when the case base reaches critical mass.

### Blocked by

engine#733 (`temporalDecayHalfLifeDays` on `CbrConfig`). Once that ships and the engine SNAPSHOT is refreshed, this is a one-line change in `IoTCbrRetrievalService.retrieve()`.

### Test coverage

`IoTCbrRetrievalServiceTest` covers the retrieval path without temporal decay. Add tests for:
- Temporal decay applied: verify `CbrQuery.temporalDecay()` is `HalfLife` with correct duration when `temporalDecayHalfLifeDays` is non-null
- No decay: verify `CbrQuery.temporalDecay()` is null when `temporalDecayHalfLifeDays` is null (existing behaviour preserved)
- Validation: zero or negative values are rejected by `TemporalDecay.HalfLife` constructor (`IllegalArgumentException` for non-positive duration) — the IoT layer relies on neocortex validation, no additional check needed

## §3 — Situation Suggestion Surfacing (#65)

**Module:** `webapp`

Response records (`SituationSuggestionsResponse`, `CaseSuggestions`) live in `webapp-api` (Tier 1) for reusability by programmatic callers. The endpoint implementation lives in `webapp` on `SituationResource`.

### Prerequisites

**situationId propagation:** `IoTCaseInputContributor.contribute()` must propagate `situationId` from `SituationContext` into the case data **unconditionally** — before the device-specific branch. `situationId` is situation metadata, not device metadata, and must be present regardless of whether the correlation key is device-prefixed or the device is found in the registry.

The method must be restructured so that early-exit paths (non-device correlation key, device not found) still return `situationId`:

```java
public Map<String, Object> contribute(CaseTriggerConfig config, SituationContext context) {
    var data = new LinkedHashMap<String, Object>();
    data.put("situationId", context.situationId());

    String ck = context.correlationKey();
    if (ck == null || !ck.startsWith(DEVICE_PREFIX)) {
        return Map.copyOf(data);
    }
    String deviceId = ck.substring(DEVICE_PREFIX.length());
    deviceRegistry.findById(deviceId).ifPresent(device -> {
        data.put("deviceId", deviceId);
        data.put("deviceClass", device.deviceClass().name().toLowerCase());
        if (device.location() != null) {
            data.put("roomType", device.location());
        }
        data.put("eventTimestamp", context.lastSignal());
    });
    return Map.copyOf(data);
}
```

This makes `situationId` available via `caseInstance.getCaseContext().getString("situationId")`. `CaseInstance` has no first-class `situationId` field — it must be extracted from `CaseContext`.

**Technical debt context:** `SituationResource.listActive()` and `CaseResource.list()` are TODO stubs returning `List.of()` (same as noted in the prior CBR spec, 2026-07-14). The suggestions endpoint does NOT depend on these — it takes `situationId` as a path parameter. The `situationId` is known to the caller from:
- Situation detection events (SSE push from RAS runtime)
- Situation definition page (`GET /api/situations/definitions`)
- Case detail page (which includes `situationId` in the response)

Full `listActive()` integration with RAS persistence is independent work.

### New endpoint

`GET /api/situations/{situationId}/suggestions` on `SituationResource`

### Flow

1. Scan `CaseInstanceCache.getAll()` for cases matching `situationId`:
   - Extract: `caseInstance.getCaseContext().getString("situationId")`
   - Filter: active status only — `state` not in `{COMPLETED, FAULTED, CANCELLED}` (terminal states per `CaseInstance.trySetTerminalState()`)
   - Household scale: after both filters, typically 1–5 matching cases per situation. `CaseInstanceCache` is an in-memory cache scoped to the single-tenant IoT deployment — the raw cache is bounded by active household case volume, and the double filter reduces to just the relevant subset.
2. For each matching case with a `CbrConfig` on its `CaseDefinition` (via `CaseDefinitionRegistry.findByName(caseType)`), call `retrievalService.retrieve()`. Cases without `CbrConfig` are included in the response with an empty suggestions list.
3. Return aggregated suggestions grouped by case.

### Error handling

- **Unknown situationId / no matching cases:** Return 200 with `SituationSuggestionsResponse(situationId, List.of())`. The endpoint cannot distinguish "situation doesn't exist" from "no active cases" without RAS persistence — 200 with empty results is correct for both.
- **No CBR-configured cases:** Return 200 with `CaseSuggestions` entries containing empty suggestion lists (consistent with `CaseResource.getSuggestions()` which returns `SuggestionResponse(caseId, caseType, 0, List.of())` for missing config).
- **Retrieval failure for individual cases:** Catch exceptions per case, log the failure, skip that case's suggestions. Return partial results for successful retrievals. Do not fail the entire request for a single case's retrieval error.

### Response

```java
record SituationSuggestionsResponse(
    String situationId,
    List<CaseSuggestions> cases
)

record CaseSuggestions(
    UUID caseId,
    String caseType,
    List<ResolutionSuggestion> suggestions
)
```

### Accept flow

Reuse existing `POST /api/cases/{caseId}/suggestions/{pastCaseId}/accept` — no change needed. The UI navigates from situation to case for acceptance.

### Security

Same `@RolesAllowed("iot-viewer")` as existing suggestion endpoints.

## Deferred Issues

Items deferred from this spec — to be filed as GitHub issues before implementation:

| Item | Context |
|------|---------|
| Per-case-type retention configuration | `CbrRetentionPolicy` supports per-type purge via `caseType` param; add per-type config mapping when empirical data exists to calibrate thresholds |
| Full `TemporalDecay` type support | Expose `Linear` and `Step` variants via `CbrConfig` if use cases emerge beyond `HalfLife` |
| Situation-level suggestion caching | Cache retrieval results per situation if request volume or case count grows beyond household scale |

# CaseHub IoT

## Project Type

type: java

## Repository Role

Foundation IoT device abstraction layer for the CaseHub ecosystem. Provides typed device class hierarchy, provider SPIs, and platform implementations for Home Assistant and OpenHAB. Consumed by application-tier repos (casehub-life, future property management, elder care) — never modified by them.

**Design spec:** `docs/superpowers/specs/2026-06-05-iot-foundation-design.md`  
**Research:** Available in `casehubio/parent` — `docs/superpowers/research/2026-06-05-home-automation-research.md`

## Build Commands

```bash
# Build all modules
mvn --batch-mode install

# Publish to GitHub Packages (CI only — requires GITHUB_TOKEN)
mvn --batch-mode deploy -DskipTests
```

## Module Structure

| Module | Artifact | Purpose |
|--------|----------|---------|
| `api` | `casehub-iot-api` | Core SPIs (reactive `Uni<>`) and typed device class hierarchy — **public API, semver discipline** |
| `homeassistant` | `casehub-iot-homeassistant` | Home Assistant provider (REST + WebSocket) and HA supplement types |
| `openhab` | `casehub-iot-openhab` | OpenHAB provider (REST + SSE, semantic model) and OpenHAB supplement types |
| `testing` | `casehub-iot-testing` | MockDeviceProvider, fixture devices, StateChangeEventPublisher — test scope only |
| `bridge` | `casehub-iot-bridge` | Lightweight bridge runtime for cloud/hybrid deployment mode |

## Key Rules

- `casehub-iot-api` is a **public API surface**. No breaking changes without a major version bump. Community automations in casehub-life and beyond depend on it.
- Vendor supplement types (HA, OpenHAB) extend common types only for fields that have no cross-vendor equivalent. Common interface first, supplement last resort.
- Device class vocabulary is aligned with the Matter Device Type Library.
- `iot-testing` is never a compile or runtime dependency — test scope only.
- The bridge module has no domain logic — pure event forwarding and command relay.

## Cross-Repo Conventions

Protocols shared across all modules live in the **casehub garden** (`../garden/docs/protocols/`). Do not write protocol files in this repo.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `docs/superpowers/specs/` |
| writing-plans (plans) | workspace `plans/` |
| handover | workspace `HANDOFF.md` |
| idea-log | workspace `IDEAS.md` |
| design-snapshot | workspace `snapshots/` |
| adr | `docs/adr/` |
| write-blog | workspace `blog/` |

## Routing

| Artifact   | Destination | Notes |
|------------|-------------|-------|
| adr        | project     | lands in `docs/adr/` |
| blog       | workspace   | staged here; published via publish-blog |
| design     | project     | |
| snapshots  | workspace   | |
| specs      | project     | lands in `docs/superpowers/specs/` |
| plans      | workspace   | |
| handover   | workspace   | |

## Work Tracking

**Issue tracking:** enabled  
**GitHub repo:** casehubio/iot

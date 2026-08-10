# Multi-Provider Command Routing — Design Spec

**Date:** 2026-06-18
**Issue:** casehubio/iot#23
**Prerequisite:** casehubio/iot#22 (bridge command dispatch — completed)

---

## Overview

Route bridge agent commands to the correct local `DeviceProvider` based on device ownership. Currently `BridgeCommandDispatcher` dispatches to the first provider found via CDI — wrong in multi-provider deployments (HA + OH on the same bridge).

---

## Design

### Add `providerId` to `DeviceEntity`

Every device carries the ID of the provider that discovered it. This is a natural property — "who discovered this switch" is intrinsic device metadata.

**DeviceEntity changes:**
- Field: `private final String providerId`
- Constructor: `this.providerId = Objects.requireNonNull(builder.providerId, "providerId")`
- Accessor: `public String providerId()`
- Builder: `String providerId` field + `public B providerId(String v)` setter

The field is **required** (non-null). The generic `Builder<T, B>` pattern means all 10 concrete types inherit the setter — no concrete builder changes needed.

Jackson serialization: the field serializes/deserializes naturally via `@JsonIgnoreProperties(ignoreUnknown = true)` on the builder. No additional annotation required.

### Provider values

| Provider | `providerId` value |
|----------|-------------------|
| Home Assistant | `"homeassistant"` (matches `HomeAssistantProvider.providerId()`) |
| OpenHAB | `"openhab"` (matches `OpenHabProvider.providerId()`) |
| Bridge (cloud-side) | `"bridge"` (matches `BridgeDeviceProvider.providerId()`) |
| Mock (testing) | `"mock"` or test-specific value |
| Fixtures | `"test"` |

### BridgeCommandDispatcher routing

Replace `List<DeviceProvider>` with `Map<String, DeviceProvider>` keyed by `providerId()`. Inject `DeviceRegistry`.

```java
public Uni<CommandResult> dispatch(DeviceCommand command) {
    if (providerMap.isEmpty()) {
        return Uni.createFrom().item(CommandResult.FAILED);
    }

    Optional<DeviceEntity> device = registry.findById(command.targetDeviceId());
    if (device.isEmpty()) {
        return Uni.createFrom().item(CommandResult.FAILED);
    }

    DeviceProvider provider = providerMap.get(device.get().providerId());
    if (provider == null) {
        return Uni.createFrom().item(CommandResult.FAILED);
    }

    return provider.dispatch(command);
}
```

### Migration sites

| Site | Change |
|------|--------|
| `DeviceEntity` | Add `providerId` field, constructor line, accessor, builder field + setter |
| `Fixtures` | 10 factory methods — add `.providerId("test")` |
| `DeviceIdNamespacer` | No change — Jackson tree copy preserves `providerId` naturally |
| HA provider `discover()` | Set `.providerId("homeassistant")` on built devices |
| OH provider `discover()` | Set `.providerId("openhab")` on built devices |
| `BridgeDeviceProvider` | Incoming snapshot devices carry `providerId` from agent — preserved through namespacing |
| `BridgeCommandDispatcher` | `Map<String, DeviceProvider>` + `DeviceRegistry` injection, routing logic |
| `BridgeCommandDispatcherTest` | Test routing by device ownership |
| All tests constructing `DeviceEntity` directly | Add `.providerId(...)` |

### Test plan

| Test | Assertion |
|------|-----------|
| Dispatch routes to correct provider | Device owned by provider B → dispatch goes to B, not A |
| Dispatch FAILED for unknown device | Device not in registry → FAILED |
| Dispatch FAILED for unknown provider | Device's providerId doesn't match any provider → FAILED |
| Dispatch FAILED when no providers | Empty provider map → FAILED |
| DeviceEntity carries providerId through serialization | Serialize → deserialize → providerId preserved |
| DeviceIdNamespacer preserves providerId | Namespaced device retains original providerId |

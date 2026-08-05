# io.casehub.iot.api.bridge.BridgeEventFilter

**Package:** `io.casehub.iot.api.bridge`

**Kind:** `interface`

Filter applied to state-change events before they are forwarded across
the bridge. Filters run in priority order (lower runs first).

## Methods

### `public abstract Uni<io.casehub.iot.api.bridge.FilterAction> filter(io.casehub.iot.api.StateChangeEvent event, io.casehub.iot.api.bridge.FilterContext ctx)`

Evaluate whether an event should be forwarded or suppressed.

#### Parameters

- `event` (`io.casehub.iot.api.StateChangeEvent`)
- `ctx` (`io.casehub.iot.api.bridge.FilterContext`)

### `public abstract int priority()`

Priority of this filter. Lower values run first.

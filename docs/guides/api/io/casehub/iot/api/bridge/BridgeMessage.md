# io.casehub.iot.api.bridge.BridgeMessage

**Package:** `io.casehub.iot.api.bridge`

**Kind:** `interface`

Wire protocol for bridge communication. Each message carries a tenancy ID
and timestamp, with type-specific payload in the sealed variant.

<p>Jackson polymorphic serialization uses `@type` as the discriminator.

## Methods

### `public abstract java.lang.String tenancyId()`

### `public abstract java.time.Instant timestamp()`

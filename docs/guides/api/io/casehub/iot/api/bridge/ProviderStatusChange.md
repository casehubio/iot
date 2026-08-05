# io.casehub.iot.api.bridge.BridgeMessage.ProviderStatusChange

**Package:** `io.casehub.iot.api.bridge`

**Kind:** `record`

## Fields

### `status` (`io.casehub.iot.api.ProviderStatusEvent`)

### `tenancyId` (`java.lang.String`)

### `timestamp` (`java.time.Instant`)

## Record Components

### `status` (`io.casehub.iot.api.ProviderStatusEvent`)

### `tenancyId` (`java.lang.String`)

### `timestamp` (`java.time.Instant`)

## Constructors

### `public ProviderStatusChange(java.lang.String tenancyId, java.time.Instant timestamp, io.casehub.iot.api.ProviderStatusEvent status)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `timestamp` (`java.time.Instant`)
- `status` (`io.casehub.iot.api.ProviderStatusEvent`)

## Methods

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public io.casehub.iot.api.ProviderStatusEvent status()`

### `public java.lang.String tenancyId()`

### `public java.time.Instant timestamp()`

### `public final java.lang.String toString()`

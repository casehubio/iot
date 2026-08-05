# io.casehub.iot.api.StateChangeEvent

**Package:** `io.casehub.iot.api`

**Kind:** `record`

## Fields

### `after` (`io.casehub.iot.api.DeviceEntity`)

### `before` (`io.casehub.iot.api.DeviceEntity`)

### `changedCapabilities` (`java.util.Set<java.lang.String>`)

### `occurredAt` (`java.time.Instant`)

### `providerId` (`java.lang.String`)

## Record Components

### `after` (`io.casehub.iot.api.DeviceEntity`)

### `before` (`io.casehub.iot.api.DeviceEntity`)

### `changedCapabilities` (`java.util.Set<java.lang.String>`)

### `occurredAt` (`java.time.Instant`)

### `providerId` (`java.lang.String`)

## Constructors

### `public StateChangeEvent(io.casehub.iot.api.DeviceEntity before, io.casehub.iot.api.DeviceEntity after, java.util.Set<java.lang.String> changedCapabilities, java.time.Instant occurredAt, java.lang.String providerId)`

#### Parameters

- `before` (`io.casehub.iot.api.DeviceEntity`)
- `after` (`io.casehub.iot.api.DeviceEntity`)
- `changedCapabilities` (`java.util.Set<java.lang.String>`)
- `occurredAt` (`java.time.Instant`)
- `providerId` (`java.lang.String`)

## Methods

### `public io.casehub.iot.api.DeviceEntity after()`

### `public io.casehub.iot.api.DeviceEntity before()`

### `public java.util.Set<java.lang.String> changedCapabilities()`

### `public static java.util.Set<java.lang.String> deriveChangedCapabilities(io.casehub.iot.api.DeviceEntity before, io.casehub.iot.api.DeviceEntity after)`

#### Parameters

- `before` (`io.casehub.iot.api.DeviceEntity`)
- `after` (`io.casehub.iot.api.DeviceEntity`)

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.time.Instant occurredAt()`

### `public java.lang.String providerId()`

### `public final java.lang.String toString()`

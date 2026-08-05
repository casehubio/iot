# io.casehub.iot.api.bridge.BridgeMessage.StateSnapshot

**Package:** `io.casehub.iot.api.bridge`

**Kind:** `record`

## Fields

### `devices` (`java.util.List<io.casehub.iot.api.DeviceEntity>`)

### `tenancyId` (`java.lang.String`)

### `timestamp` (`java.time.Instant`)

## Record Components

### `devices` (`java.util.List<io.casehub.iot.api.DeviceEntity>`)

### `tenancyId` (`java.lang.String`)

### `timestamp` (`java.time.Instant`)

## Constructors

### `public StateSnapshot(java.lang.String tenancyId, java.time.Instant timestamp, java.util.List<io.casehub.iot.api.DeviceEntity> devices)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `timestamp` (`java.time.Instant`)
- `devices` (`java.util.List<io.casehub.iot.api.DeviceEntity>`)

## Methods

### `public java.util.List<io.casehub.iot.api.DeviceEntity> devices()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.lang.String tenancyId()`

### `public java.time.Instant timestamp()`

### `public final java.lang.String toString()`

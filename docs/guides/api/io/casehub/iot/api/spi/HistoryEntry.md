# io.casehub.iot.api.spi.DeviceStateHistoryProvider.HistoryEntry

**Package:** `io.casehub.iot.api.spi`

**Kind:** `record`

## Fields

### `changedCapabilities` (`java.util.List<java.lang.String>`)

### `deviceClass` (`java.lang.String`)

### `deviceId` (`java.lang.String`)

### `occurredAt` (`java.time.Instant`)

### `stateSnapshot` (`io.casehub.iot.api.DeviceEntity`)

## Record Components

### `changedCapabilities` (`java.util.List<java.lang.String>`)

### `deviceClass` (`java.lang.String`)

### `deviceId` (`java.lang.String`)

### `occurredAt` (`java.time.Instant`)

### `stateSnapshot` (`io.casehub.iot.api.DeviceEntity`)

## Constructors

### `public HistoryEntry(java.lang.String deviceId, java.lang.String deviceClass, io.casehub.iot.api.DeviceEntity stateSnapshot, java.util.List<java.lang.String> changedCapabilities, java.time.Instant occurredAt)`

#### Parameters

- `deviceId` (`java.lang.String`)
- `deviceClass` (`java.lang.String`)
- `stateSnapshot` (`io.casehub.iot.api.DeviceEntity`)
- `changedCapabilities` (`java.util.List<java.lang.String>`)
- `occurredAt` (`java.time.Instant`)

## Methods

### `public java.util.List<java.lang.String> changedCapabilities()`

### `public java.lang.String deviceClass()`

### `public java.lang.String deviceId()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.time.Instant occurredAt()`

### `public io.casehub.iot.api.DeviceEntity stateSnapshot()`

### `public final java.lang.String toString()`

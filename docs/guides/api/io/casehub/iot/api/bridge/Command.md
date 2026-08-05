# io.casehub.iot.api.bridge.BridgeMessage.Command

**Package:** `io.casehub.iot.api.bridge`

**Kind:** `record`

## Fields

### `command` (`io.casehub.iot.api.DeviceCommand`)

### `correlationId` (`java.lang.String`)

### `tenancyId` (`java.lang.String`)

### `timestamp` (`java.time.Instant`)

## Record Components

### `command` (`io.casehub.iot.api.DeviceCommand`)

### `correlationId` (`java.lang.String`)

### `tenancyId` (`java.lang.String`)

### `timestamp` (`java.time.Instant`)

## Constructors

### `public Command(java.lang.String tenancyId, java.time.Instant timestamp, java.lang.String correlationId, io.casehub.iot.api.DeviceCommand command)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `timestamp` (`java.time.Instant`)
- `correlationId` (`java.lang.String`)
- `command` (`io.casehub.iot.api.DeviceCommand`)

## Methods

### `public io.casehub.iot.api.DeviceCommand command()`

### `public java.lang.String correlationId()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.lang.String tenancyId()`

### `public java.time.Instant timestamp()`

### `public final java.lang.String toString()`

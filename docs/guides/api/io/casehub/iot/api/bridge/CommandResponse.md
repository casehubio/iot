# io.casehub.iot.api.bridge.BridgeMessage.CommandResponse

**Package:** `io.casehub.iot.api.bridge`

**Kind:** `record`

## Fields

### `correlationId` (`java.lang.String`)

### `result` (`io.casehub.iot.api.CommandResult`)

### `tenancyId` (`java.lang.String`)

### `timestamp` (`java.time.Instant`)

## Record Components

### `correlationId` (`java.lang.String`)

### `result` (`io.casehub.iot.api.CommandResult`)

### `tenancyId` (`java.lang.String`)

### `timestamp` (`java.time.Instant`)

## Constructors

### `public CommandResponse(java.lang.String tenancyId, java.time.Instant timestamp, java.lang.String correlationId, io.casehub.iot.api.CommandResult result)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `timestamp` (`java.time.Instant`)
- `correlationId` (`java.lang.String`)
- `result` (`io.casehub.iot.api.CommandResult`)

## Methods

### `public java.lang.String correlationId()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public io.casehub.iot.api.CommandResult result()`

### `public java.lang.String tenancyId()`

### `public java.time.Instant timestamp()`

### `public final java.lang.String toString()`

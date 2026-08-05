# io.casehub.iot.api.IoTCommandAuditEvent

**Package:** `io.casehub.iot.api`

**Kind:** `record`

## Fields

### `action` (`java.lang.String`)

### `correlationId` (`java.lang.String`)

### `deviceId` (`java.lang.String`)

### `dispatchedBy` (`java.lang.String`)

### `parameters` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `providerId` (`java.lang.String`)

### `result` (`io.casehub.iot.api.CommandResult`)

### `tenancyId` (`java.lang.String`)

### `timestamp` (`java.time.Instant`)

## Record Components

### `action` (`java.lang.String`)

### `correlationId` (`java.lang.String`)

### `deviceId` (`java.lang.String`)

### `dispatchedBy` (`java.lang.String`)

### `parameters` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `providerId` (`java.lang.String`)

### `result` (`io.casehub.iot.api.CommandResult`)

### `tenancyId` (`java.lang.String`)

### `timestamp` (`java.time.Instant`)

## Constructors

### `public IoTCommandAuditEvent(java.lang.String deviceId, java.lang.String action, java.util.Map<java.lang.String,java.lang.Object> parameters, io.casehub.iot.api.CommandResult result, java.lang.String dispatchedBy, java.lang.String correlationId, java.lang.String providerId, java.lang.String tenancyId, java.time.Instant timestamp)`

#### Parameters

- `deviceId` (`java.lang.String`)
- `action` (`java.lang.String`)
- `parameters` (`java.util.Map<java.lang.String,java.lang.Object>`)
- `result` (`io.casehub.iot.api.CommandResult`)
- `dispatchedBy` (`java.lang.String`)
- `correlationId` (`java.lang.String`)
- `providerId` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `timestamp` (`java.time.Instant`)

## Methods

### `public java.lang.String action()`

### `public java.lang.String correlationId()`

### `public java.lang.String deviceId()`

### `public java.lang.String dispatchedBy()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public java.util.Map<java.lang.String,java.lang.Object> parameters()`

### `public java.lang.String providerId()`

### `public io.casehub.iot.api.CommandResult result()`

### `public java.lang.String tenancyId()`

### `public java.time.Instant timestamp()`

### `public final java.lang.String toString()`

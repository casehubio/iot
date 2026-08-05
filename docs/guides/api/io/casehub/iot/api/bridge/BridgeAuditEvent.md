# io.casehub.iot.api.bridge.BridgeAuditEvent

**Package:** `io.casehub.iot.api.bridge`

**Kind:** `record`

## Fields

### `correlationId` (`java.lang.String`)

### `deviceId` (`java.lang.String`)

### `eventType` (`io.casehub.iot.api.bridge.BridgeAuditEventType`)

### `message` (`io.casehub.iot.api.bridge.BridgeMessage`)

### `receivedAt` (`java.time.Instant`)

### `tenancyId` (`java.lang.String`)

## Record Components

### `correlationId` (`java.lang.String`)

### `deviceId` (`java.lang.String`)

### `eventType` (`io.casehub.iot.api.bridge.BridgeAuditEventType`)

### `message` (`io.casehub.iot.api.bridge.BridgeMessage`)

### `receivedAt` (`java.time.Instant`)

### `tenancyId` (`java.lang.String`)

## Constructors

### `public BridgeAuditEvent(java.lang.String tenancyId, java.time.Instant receivedAt, io.casehub.iot.api.bridge.BridgeAuditEventType eventType, java.lang.String correlationId, java.lang.String deviceId, io.casehub.iot.api.bridge.BridgeMessage message)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `receivedAt` (`java.time.Instant`)
- `eventType` (`io.casehub.iot.api.bridge.BridgeAuditEventType`)
- `correlationId` (`java.lang.String`)
- `deviceId` (`java.lang.String`)
- `message` (`io.casehub.iot.api.bridge.BridgeMessage`)

## Methods

### `public java.lang.String correlationId()`

### `public java.lang.String deviceId()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public io.casehub.iot.api.bridge.BridgeAuditEventType eventType()`

### `public final int hashCode()`

### `public io.casehub.iot.api.bridge.BridgeMessage message()`

### `public java.time.Instant receivedAt()`

### `public java.lang.String tenancyId()`

### `public final java.lang.String toString()`

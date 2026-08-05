# io.casehub.iot.api.bridge.BridgeAuditQuery

**Package:** `io.casehub.iot.api.bridge`

**Kind:** `record`

## Fields

### `DEFAULT_LIMIT` (`int`)

### `correlationId` (`java.lang.String`)

### `deviceId` (`java.lang.String`)

### `eventType` (`io.casehub.iot.api.bridge.BridgeAuditEventType`)

### `from` (`java.time.Instant`)

### `limit` (`int`)

### `offset` (`int`)

### `tenancyId` (`java.lang.String`)

### `to` (`java.time.Instant`)

## Record Components

### `correlationId` (`java.lang.String`)

### `deviceId` (`java.lang.String`)

### `eventType` (`io.casehub.iot.api.bridge.BridgeAuditEventType`)

### `from` (`java.time.Instant`)

### `limit` (`int`)

### `offset` (`int`)

### `tenancyId` (`java.lang.String`)

### `to` (`java.time.Instant`)

## Constructors

### `public BridgeAuditQuery(java.lang.String tenancyId, java.time.Instant from, java.time.Instant to, io.casehub.iot.api.bridge.BridgeAuditEventType eventType, java.lang.String deviceId, java.lang.String correlationId, int offset, int limit)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `from` (`java.time.Instant`)
- `to` (`java.time.Instant`)
- `eventType` (`io.casehub.iot.api.bridge.BridgeAuditEventType`)
- `deviceId` (`java.lang.String`)
- `correlationId` (`java.lang.String`)
- `offset` (`int`)
- `limit` (`int`)

## Methods

### `public static io.casehub.iot.api.bridge.BridgeAuditQuery.Builder builder()`

### `public java.lang.String correlationId()`

### `public java.lang.String deviceId()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public io.casehub.iot.api.bridge.BridgeAuditEventType eventType()`

### `public java.time.Instant from()`

### `public final int hashCode()`

### `public int limit()`

### `public int offset()`

### `public java.lang.String tenancyId()`

### `public java.time.Instant to()`

### `public final java.lang.String toString()`

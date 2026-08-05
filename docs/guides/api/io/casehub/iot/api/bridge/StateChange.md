# io.casehub.iot.api.bridge.BridgeMessage.StateChange

**Package:** `io.casehub.iot.api.bridge`

**Kind:** `record`

## Fields

### `event` (`io.casehub.iot.api.StateChangeEvent`)

### `tenancyId` (`java.lang.String`)

### `timestamp` (`java.time.Instant`)

## Record Components

### `event` (`io.casehub.iot.api.StateChangeEvent`)

### `tenancyId` (`java.lang.String`)

### `timestamp` (`java.time.Instant`)

## Constructors

### `public StateChange(java.lang.String tenancyId, java.time.Instant timestamp, io.casehub.iot.api.StateChangeEvent event)`

#### Parameters

- `tenancyId` (`java.lang.String`)
- `timestamp` (`java.time.Instant`)
- `event` (`io.casehub.iot.api.StateChangeEvent`)

## Methods

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public io.casehub.iot.api.StateChangeEvent event()`

### `public final int hashCode()`

### `public java.lang.String tenancyId()`

### `public java.time.Instant timestamp()`

### `public final java.lang.String toString()`

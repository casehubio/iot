# io.casehub.iot.api.DeviceCommand

**Package:** `io.casehub.iot.api`

**Kind:** `record`

## Fields

### `ACTION_LOCK` (`java.lang.String`)

### `ACTION_SET_POSITION` (`java.lang.String`)

### `ACTION_SET_TEMPERATURE` (`java.lang.String`)

### `ACTION_SET_VOLUME` (`java.lang.String`)

### `ACTION_TURN_OFF` (`java.lang.String`)

### `ACTION_TURN_ON` (`java.lang.String`)

### `ACTION_UNLOCK` (`java.lang.String`)

### `action` (`java.lang.String`)

### `correlationId` (`java.lang.String`)

### `dispatchedBy` (`java.lang.String`)

### `parameters` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `targetDeviceId` (`java.lang.String`)

## Record Components

### `action` (`java.lang.String`)

### `correlationId` (`java.lang.String`)

### `dispatchedBy` (`java.lang.String`)

### `parameters` (`java.util.Map<java.lang.String,java.lang.Object>`)

### `targetDeviceId` (`java.lang.String`)

## Constructors

### `public DeviceCommand(java.lang.String targetDeviceId, java.lang.String action, java.util.Map<java.lang.String,java.lang.Object> parameters, java.lang.String dispatchedBy, java.lang.String correlationId)`

#### Parameters

- `targetDeviceId` (`java.lang.String`)
- `action` (`java.lang.String`)
- `parameters` (`java.util.Map<java.lang.String,java.lang.Object>`)
- `dispatchedBy` (`java.lang.String`)
- `correlationId` (`java.lang.String`)

## Methods

### `public java.lang.String action()`

### `public java.lang.String correlationId()`

### `public java.lang.String dispatchedBy()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public static io.casehub.iot.api.DeviceCommand lock(java.lang.String targetDeviceId, java.lang.String dispatchedBy, java.lang.String correlationId)`

#### Parameters

- `targetDeviceId` (`java.lang.String`)
- `dispatchedBy` (`java.lang.String`)
- `correlationId` (`java.lang.String`)

### `public java.util.Map<java.lang.String,java.lang.Object> parameters()`

### `public static io.casehub.iot.api.DeviceCommand setPosition(java.lang.String targetDeviceId, int position, java.lang.String dispatchedBy, java.lang.String correlationId)`

#### Parameters

- `targetDeviceId` (`java.lang.String`)
- `position` (`int`)
- `dispatchedBy` (`java.lang.String`)
- `correlationId` (`java.lang.String`)

### `public static io.casehub.iot.api.DeviceCommand setTemperature(java.lang.String targetDeviceId, io.casehub.iot.api.Temperature target, java.lang.String dispatchedBy, java.lang.String correlationId)`

#### Parameters

- `targetDeviceId` (`java.lang.String`)
- `target` (`io.casehub.iot.api.Temperature`)
- `dispatchedBy` (`java.lang.String`)
- `correlationId` (`java.lang.String`)

### `public static io.casehub.iot.api.DeviceCommand setVolume(java.lang.String targetDeviceId, int volume, java.lang.String dispatchedBy, java.lang.String correlationId)`

#### Parameters

- `targetDeviceId` (`java.lang.String`)
- `volume` (`int`)
- `dispatchedBy` (`java.lang.String`)
- `correlationId` (`java.lang.String`)

### `public java.lang.String targetDeviceId()`

### `public final java.lang.String toString()`

### `public static io.casehub.iot.api.DeviceCommand turnOff(java.lang.String targetDeviceId, java.lang.String dispatchedBy, java.lang.String correlationId)`

#### Parameters

- `targetDeviceId` (`java.lang.String`)
- `dispatchedBy` (`java.lang.String`)
- `correlationId` (`java.lang.String`)

### `public static io.casehub.iot.api.DeviceCommand turnOn(java.lang.String targetDeviceId, java.util.Map<java.lang.String,java.lang.Object> parameters, java.lang.String dispatchedBy, java.lang.String correlationId)`

#### Parameters

- `targetDeviceId` (`java.lang.String`)
- `parameters` (`java.util.Map<java.lang.String,java.lang.Object>`)
- `dispatchedBy` (`java.lang.String`)
- `correlationId` (`java.lang.String`)

### `public static io.casehub.iot.api.DeviceCommand unlock(java.lang.String targetDeviceId, java.lang.String dispatchedBy, java.lang.String correlationId)`

#### Parameters

- `targetDeviceId` (`java.lang.String`)
- `dispatchedBy` (`java.lang.String`)
- `correlationId` (`java.lang.String`)

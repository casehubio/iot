# io.casehub.iot.api.SensorDevice

**Package:** `io.casehub.iot.api`

**Kind:** `class`

## Fields

### `CAP_BINARY_VALUE` (`java.lang.String`)

### `CAP_NUMERIC_VALUE` (`java.lang.String`)

### `binaryValue` (`java.lang.Boolean`)

### `numericValue` (`java.math.BigDecimal`)

### `sensorType` (`io.casehub.iot.api.SensorType`)

### `unit` (`java.lang.String`)

## Constructors

### `private SensorDevice(io.casehub.iot.api.SensorDevice.Builder builder)`

#### Parameters

- `builder` (`io.casehub.iot.api.SensorDevice.Builder`)

## Methods

### `public java.util.Optional<java.lang.Boolean> binaryValue()`

### `public static io.casehub.iot.api.SensorDevice.Builder builder()`

### `public java.util.Map<java.lang.String,java.lang.Object> capabilities()`

### `public java.util.Optional<java.math.BigDecimal> numericValue()`

### `public io.casehub.iot.api.SensorType sensorType()`

### `public io.casehub.iot.api.SensorDevice.Builder toBuilder()`

### `public java.util.Optional<java.lang.String> unit()`

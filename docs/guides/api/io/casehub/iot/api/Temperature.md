# io.casehub.iot.api.Temperature

**Package:** `io.casehub.iot.api`

**Kind:** `record`

## Fields

### `unit` (`io.casehub.iot.api.Temperature.TemperatureUnit`)

### `value` (`java.math.BigDecimal`)

## Record Components

### `unit` (`io.casehub.iot.api.Temperature.TemperatureUnit`)

### `value` (`java.math.BigDecimal`)

## Constructors

### `public Temperature(java.math.BigDecimal value, io.casehub.iot.api.Temperature.TemperatureUnit unit)`

#### Parameters

- `value` (`java.math.BigDecimal`)
- `unit` (`io.casehub.iot.api.Temperature.TemperatureUnit`)

## Methods

### `public boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public int hashCode()`

### `public io.casehub.iot.api.Temperature toCelsius()`

### `public io.casehub.iot.api.Temperature toFahrenheit()`

### `public final java.lang.String toString()`

### `public io.casehub.iot.api.Temperature.TemperatureUnit unit()`

### `public java.math.BigDecimal value()`

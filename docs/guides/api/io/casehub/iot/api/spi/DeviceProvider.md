# io.casehub.iot.api.spi.DeviceProvider

**Package:** `io.casehub.iot.api.spi`

**Kind:** `interface`

## Methods

### `public abstract java.util.List<io.casehub.iot.api.DeviceEntity> discover()`

### `public abstract io.casehub.iot.api.CommandResult dispatch(io.casehub.iot.api.DeviceCommand command)`

#### Parameters

- `command` (`io.casehub.iot.api.DeviceCommand`)

### `public abstract java.lang.String providerId()`

### `public abstract io.casehub.iot.api.ProviderStatus status()`

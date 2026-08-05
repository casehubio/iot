# io.casehub.iot.api.spi.DeviceRegistry

**Package:** `io.casehub.iot.api.spi`

**Kind:** `interface`

## Methods

### `public abstract java.util.List<io.casehub.iot.api.DeviceEntity> findAll()`

### `public abstract java.util.List<T> findByClass(java.lang.Class<T> deviceClass)`

#### Parameters

- `deviceClass` (`java.lang.Class<T>`)

### `public abstract java.util.Optional<io.casehub.iot.api.DeviceEntity> findById(java.lang.String deviceId)`

#### Parameters

- `deviceId` (`java.lang.String`)

### `public default java.util.Optional<io.casehub.iot.api.DeviceEntity> findById(java.lang.String deviceId, java.lang.String tenancyId)`

#### Parameters

- `deviceId` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)

### `public abstract java.util.List<io.casehub.iot.api.DeviceEntity> findByTenancyId(java.lang.String tenancyId)`

#### Parameters

- `tenancyId` (`java.lang.String`)

### `public abstract void refresh()`

### `public abstract void refresh(java.lang.String providerId)`

#### Parameters

- `providerId` (`java.lang.String`)

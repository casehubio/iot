# io.casehub.iot.spi.CdiDeviceRegistry

**Package:** `io.casehub.iot.spi`

**Kind:** `class`

## Fields

### `LOG` (`Logger`)

### `devices` (`java.util.Map<java.lang.String,io.casehub.iot.api.DeviceEntity>`)

### `providers` (`Instance<io.casehub.iot.api.spi.DeviceProvider>`)

## Constructors

### `public CdiDeviceRegistry()`

## Methods

### `public java.util.List<io.casehub.iot.api.DeviceEntity> findAll()`

### `public java.util.List<T> findByClass(java.lang.Class<T> deviceClass)`

#### Parameters

- `deviceClass` (`java.lang.Class<T>`)

### `public java.util.Optional<io.casehub.iot.api.DeviceEntity> findById(java.lang.String deviceId)`

#### Parameters

- `deviceId` (`java.lang.String`)

### `public java.util.List<io.casehub.iot.api.DeviceEntity> findByTenancyId(java.lang.String tenancyId)`

#### Parameters

- `tenancyId` (`java.lang.String`)

### `void onStartup(StartupEvent event)`

#### Parameters

- `event` (`StartupEvent`)

### `void onStateChange(io.casehub.iot.api.StateChangeEvent event)`

#### Parameters

- `event` (`io.casehub.iot.api.StateChangeEvent`)

### `public void refresh()`

### `public void refresh(java.lang.String providerId)`

#### Parameters

- `providerId` (`java.lang.String`)

### `private synchronized void updateDevice(io.casehub.iot.api.DeviceEntity device)`

#### Parameters

- `device` (`io.casehub.iot.api.DeviceEntity`)

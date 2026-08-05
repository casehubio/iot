# io.casehub.iot.api.ProviderStatusEvent

**Package:** `io.casehub.iot.api`

**Kind:** `record`

## Fields

### `currentStatus` (`io.casehub.iot.api.ProviderStatus`)

### `previousStatus` (`io.casehub.iot.api.ProviderStatus`)

### `providerId` (`java.lang.String`)

## Record Components

### `currentStatus` (`io.casehub.iot.api.ProviderStatus`)

### `previousStatus` (`io.casehub.iot.api.ProviderStatus`)

### `providerId` (`java.lang.String`)

## Constructors

### `public ProviderStatusEvent(java.lang.String providerId, io.casehub.iot.api.ProviderStatus previousStatus, io.casehub.iot.api.ProviderStatus currentStatus)`

#### Parameters

- `providerId` (`java.lang.String`)
- `previousStatus` (`io.casehub.iot.api.ProviderStatus`)
- `currentStatus` (`io.casehub.iot.api.ProviderStatus`)

## Methods

### `public io.casehub.iot.api.ProviderStatus currentStatus()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public final int hashCode()`

### `public io.casehub.iot.api.ProviderStatus previousStatus()`

### `public java.lang.String providerId()`

### `public final java.lang.String toString()`

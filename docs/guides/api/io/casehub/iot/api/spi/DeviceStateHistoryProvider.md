# io.casehub.iot.api.spi.DeviceStateHistoryProvider

**Package:** `io.casehub.iot.api.spi`

**Kind:** `interface`

## Methods

### `public abstract java.util.List<io.casehub.iot.api.spi.DeviceStateHistoryProvider.HistoryEntry> findHistory(java.lang.String deviceId, java.lang.String tenancyId, java.time.Instant from, java.time.Instant to, int limit)`

#### Parameters

- `deviceId` (`java.lang.String`)
- `tenancyId` (`java.lang.String`)
- `from` (`java.time.Instant`)
- `to` (`java.time.Instant`)
- `limit` (`int`)

# io.casehub.iot.api.bridge.BridgeAuditStore

**Package:** `io.casehub.iot.api.bridge`

**Kind:** `interface`

## Methods

### `public abstract java.util.List<io.casehub.iot.api.bridge.BridgeAuditEvent> query(io.casehub.iot.api.bridge.BridgeAuditQuery query)`

Returns events matching the query criteria, ordered by
`receivedAt` descending (newest first). Implementations
MUST honour this ordering contract.

#### Parameters

- `query` (`io.casehub.iot.api.bridge.BridgeAuditQuery`)

### `public abstract void save(io.casehub.iot.api.bridge.BridgeAuditEvent event)`

#### Parameters

- `event` (`io.casehub.iot.api.bridge.BridgeAuditEvent`)

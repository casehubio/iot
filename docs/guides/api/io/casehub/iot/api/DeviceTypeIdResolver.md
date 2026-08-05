# io.casehub.iot.api.DeviceTypeIdResolver

**Package:** `io.casehub.iot.api`

**Kind:** `class`

Jackson type-id resolver for the `DeviceEntity` hierarchy.

<p>Uses compound IDs of the form `"DeviceClass:SimpleClassName"`
(e.g. `"SWITCH:SwitchDevice"`, `"THERMOSTAT:HomeAssistantThermostat"`).

<p>All 11 common types are registered in the static initializer.
Vendor modules register supplement types via Class).

<p>Deserialization tries an exact match first. On miss, it splits on `:`
and falls back to the common type for that `DeviceClass` prefix — this
provides graceful degradation when a remote node sends a vendor supplement
type unknown to the receiver.

## Fields

### `FALLBACK` (`java.util.Map<java.lang.String,java.lang.Class<? extends io.casehub.iot.api.DeviceEntity>>`)

DeviceClass name → common type class. Used for fallback resolution.

### `LOG` (`Logger`)

### `REGISTRY` (`java.util.concurrent.ConcurrentHashMap<java.lang.String,java.lang.Class<? extends io.casehub.iot.api.DeviceEntity>>`)

Compound-ID → concrete class. Thread-safe for runtime registration.

## Constructors

### `public DeviceTypeIdResolver()`

## Methods

### `public static void deregisterType(java.lang.String compoundId)`

Remove a previously registered type. Primarily for test cleanup.

#### Parameters

- `compoundId` (`java.lang.String`)

### `public JsonTypeInfo.Id getMechanism()`

### `public java.lang.String idFromValue(java.lang.Object value)`

#### Parameters

- `value` (`java.lang.Object`)

### `public java.lang.String idFromValueAndType(java.lang.Object value, java.lang.Class<?> suggestedType)`

#### Parameters

- `value` (`java.lang.Object`)
- `suggestedType` (`java.lang.Class<?>`)

### `public static boolean isRegistered(java.lang.String compoundId)`

Check whether a compound ID is registered (exact match).

#### Parameters

- `compoundId` (`java.lang.String`)

### `public static void registerType(java.lang.String compoundId, java.lang.Class<? extends io.casehub.iot.api.DeviceEntity> type)`

Register a vendor supplement type for exact compound-ID resolution.

#### Parameters

- `compoundId` (`java.lang.String`) — format: `"DEVICE_CLASS:SimpleClassName"`
- `type` (`java.lang.Class<? extends io.casehub.iot.api.DeviceEntity>`) — the concrete device class

### `public JavaType typeFromId(DatabindContext context, java.lang.String id)`

#### Parameters

- `context` (`DatabindContext`)
- `id` (`java.lang.String`)

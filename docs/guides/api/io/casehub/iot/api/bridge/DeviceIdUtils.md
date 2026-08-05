# io.casehub.iot.api.bridge.DeviceIdUtils

**Package:** `io.casehub.iot.api.bridge`

**Kind:** `class`

Utility methods for namespaced device IDs of the form `{tenancyId`/{localId}}.

## Constructors

### `private DeviceIdUtils()`

## Methods

### `public static java.lang.String extractTenancyId(java.lang.String namespacedId)`

Extract the tenancy ID from a namespaced device ID.
Returns the original string if no `/` separator is present.

#### Parameters

- `namespacedId` (`java.lang.String`)

### `public static java.lang.String stripPrefix(java.lang.String namespacedId)`

Strip the tenancy prefix from a namespaced device ID.
Returns the original string if no `/` separator is present.

#### Parameters

- `namespacedId` (`java.lang.String`)

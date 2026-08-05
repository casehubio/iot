# io.casehub.iot.api.IoTCloudEventAdapter

**Package:** `io.casehub.iot.api`

**Kind:** `class`

## Fields

### `LOG` (`Logger`)

### `SOURCE` (`java.net.URI`)

### `TYPE_PREFIX` (`java.lang.String`)

### `cloudEvents` (`Event<CloudEvent>`)

### `objectMapper` (`ObjectMapper`)

## Constructors

### `public IoTCloudEventAdapter(Event<CloudEvent> cloudEvents, ObjectMapper objectMapper)`

#### Parameters

- `cloudEvents` (`Event<CloudEvent>`)
- `objectMapper` (`ObjectMapper`)

## Methods

### `void onStateChange(io.casehub.iot.api.StateChangeEvent event)`

#### Parameters

- `event` (`io.casehub.iot.api.StateChangeEvent`)

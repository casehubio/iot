# io.casehub.iot.api.CoverDevice

**Package:** `io.casehub.iot.api`

**Kind:** `class`

## Fields

### `CAP_MOVING` (`java.lang.String`)

### `CAP_POSITION` (`java.lang.String`)

### `moving` (`boolean`)

### `position` (`java.lang.Integer`)

## Constructors

### `protected CoverDevice(io.casehub.iot.api.CoverDevice.AbstractBuilder<?,?> builder)`

#### Parameters

- `builder` (`io.casehub.iot.api.CoverDevice.AbstractBuilder<?,?>`)

## Methods

### `public java.util.Map<java.lang.String,java.lang.Object> capabilities()`

### `public boolean isMoving()`

### `public java.util.Optional<java.lang.Integer> position()`

Position as a percentage: 0 = fully closed, 100 = fully open.
Providers that use the opposite convention (e.g., OpenHAB Rollershutter:
0=open, 100=closed) must invert before populating this field.

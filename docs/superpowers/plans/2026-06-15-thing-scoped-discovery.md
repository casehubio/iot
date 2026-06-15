# Thing-Scoped Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Layered discovery for the OpenHAB provider — Equipment + Thing discovery always run and merge, with real-time Thing status via SSE.

**Architecture:** Extract shared device construction from resolution (ResolvedDeviceFields pattern), add Thing DTOs and resolution strategy, extend SSE client with Thing status events and dual reverse indexes, extend command dispatch with channel-type resolution. Both discovery layers always run; `thingDiscoveryEnabled` controls only whether unmapped Things produce DeviceEntities.

**Tech Stack:** Java 21, Quarkus 3.32, Mutiny `Uni`/`Multi`, MicroProfile REST Client, Jackson, JUnit 5, AssertJ

**Spec:** `docs/superpowers/specs/2026-06-14-thing-scoped-discovery-design.md`

---

## File Map

### New Files

| File | Responsibility |
|------|----------------|
| `openhab/src/main/java/io/casehub/iot/openhab/ResolvedDeviceFields.java` | Record carrying all device fields between resolution and construction |
| `openhab/src/main/java/io/casehub/iot/openhab/OpenHabDeviceBuilder.java` | Shared construction: ResolvedDeviceFields → DeviceEntity (all types including supplements) |
| `openhab/src/main/java/io/casehub/iot/openhab/OpenHabThingResolver.java` | Thing resolution strategy: Thing + item states → ResolvedDeviceFields |
| `openhab/src/main/java/io/casehub/iot/openhab/internal/OpenHabThingDto.java` | Thing DTO from `/rest/things` |
| `openhab/src/main/java/io/casehub/iot/openhab/internal/OpenHabChannelDto.java` | Channel DTO within a Thing |
| `openhab/src/main/java/io/casehub/iot/openhab/internal/OpenHabStatusInfoDto.java` | Status info DTO (used in Thing response and SSE payload) |
| `openhab/src/test/java/io/casehub/iot/openhab/OpenHabDeviceBuilderTest.java` | Shared construction tests |
| `openhab/src/test/java/io/casehub/iot/openhab/OpenHabThingResolverTest.java` | Thing resolution + DeviceClass inference tests |

### Modified Files

| File | Change |
|------|--------|
| `openhab/src/main/java/io/casehub/iot/openhab/OpenHabEntityMapper.java` | Extract resolution from construction; use ResolvedDeviceFields + OpenHabDeviceBuilder |
| `openhab/src/main/java/io/casehub/iot/openhab/OpenHabSseClient.java` | Add Thing caches, dual reverse indexes, Thing status event handling, layered discovery pipeline |
| `openhab/src/main/java/io/casehub/iot/openhab/OpenHabProvider.java` | Wire layered discovery via SSE client |
| `openhab/src/main/java/io/casehub/iot/openhab/OpenHabRestClient.java` | Add `getThings()` and `getAllItems()` methods |
| `openhab/src/main/java/io/casehub/iot/openhab/OpenHabSseRestClient.java` | Update topic subscription |
| `openhab/src/main/java/io/casehub/iot/openhab/OpenHabConfig.java` | Add `thingDiscoveryEnabled()` |
| `openhab/src/test/java/io/casehub/iot/openhab/OpenHabEntityMapperTest.java` | Verify existing tests pass unchanged after refactor |
| `openhab/src/test/java/io/casehub/iot/openhab/OpenHabSseClientTest.java` | Add Thing discovery, Thing SSE, layered merge tests |
| `openhab/src/test/java/io/casehub/iot/openhab/OpenHabCommandDispatchTest.java` | Add Thing-scoped dispatch tests |
| `openhab/src/test/resources/application.properties` | Add `casehub.iot.openhab.thing-discovery-enabled=true` |

---

## Task 1: Thing DTOs

**Files:**
- Create: `openhab/src/main/java/io/casehub/iot/openhab/internal/OpenHabStatusInfoDto.java`
- Create: `openhab/src/main/java/io/casehub/iot/openhab/internal/OpenHabChannelDto.java`
- Create: `openhab/src/main/java/io/casehub/iot/openhab/internal/OpenHabThingDto.java`
- Test: `openhab/src/test/java/io/casehub/iot/openhab/OpenHabDtoTest.java` (existing — add cases)

- [ ] **Step 1: Write failing test for Thing DTO deserialization**

Add to `OpenHabDtoTest.java`:

```java
// ---- Thing DTO deserialization ----

@Test
void thingDtoDeserializesFromJson() throws Exception {
    String json = """
        {
          "UID": "zwave:device:controller:node3",
          "label": "Living Room Dimmer",
          "thingTypeUID": "zwave:device",
          "statusInfo": { "status": "ONLINE", "statusDetail": "NONE" },
          "channels": [
            {
              "uid": "zwave:device:controller:node3:switch_dimmer",
              "id": "switch_dimmer",
              "channelTypeUID": "zwave:switch_dimmer",
              "itemType": "Dimmer",
              "kind": "STATE",
              "linkedItems": ["LivingRoom_Dimmer"],
              "defaultTags": []
            },
            {
              "uid": "zwave:device:controller:node3:alarm_system",
              "id": "alarm_system",
              "channelTypeUID": "zwave:alarm_system",
              "itemType": "Switch",
              "kind": "TRIGGER",
              "linkedItems": [],
              "defaultTags": []
            }
          ],
          "location": "Living Room"
        }
        """;

    var mapper = new ObjectMapper();
    var thing = mapper.readValue(json, OpenHabThingDto.class);

    assertThat(thing.uid()).isEqualTo("zwave:device:controller:node3");
    assertThat(thing.label()).isEqualTo("Living Room Dimmer");
    assertThat(thing.thingTypeUID()).isEqualTo("zwave:device");
    assertThat(thing.statusInfo().status()).isEqualTo("ONLINE");
    assertThat(thing.statusInfo().statusDetail()).isEqualTo("NONE");
    assertThat(thing.channels()).hasSize(2);
    assertThat(thing.channels().get(0).id()).isEqualTo("switch_dimmer");
    assertThat(thing.channels().get(0).itemType()).isEqualTo("Dimmer");
    assertThat(thing.channels().get(0).kind()).isEqualTo("STATE");
    assertThat(thing.channels().get(0).linkedItems()).containsExactly("LivingRoom_Dimmer");
    assertThat(thing.channels().get(1).kind()).isEqualTo("TRIGGER");
    assertThat(thing.channels().get(1).linkedItems()).isEmpty();
    assertThat(thing.location()).isEqualTo("Living Room");
}

@Test
void thingStatusPayloadDeserializesAsArray() throws Exception {
    String payload = """
        [{"status":"OFFLINE","statusDetail":"COMMUNICATION_ERROR"},{"status":"ONLINE","statusDetail":"NONE"}]
        """;

    var mapper = new ObjectMapper();
    var statuses = mapper.readValue(payload,
        mapper.getTypeFactory().constructCollectionType(List.class, OpenHabStatusInfoDto.class));

    assertThat(statuses).hasSize(2);
    assertThat(statuses.get(0).status()).isEqualTo("OFFLINE");
    assertThat(statuses.get(0).statusDetail()).isEqualTo("COMMUNICATION_ERROR");
    assertThat(statuses.get(1).status()).isEqualTo("ONLINE");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode -pl openhab test -Dtest=OpenHabDtoTest#thingDtoDeserializesFromJson`
Expected: FAIL — compilation error, `OpenHabThingDto` not found

- [ ] **Step 3: Create the three DTO records**

`OpenHabStatusInfoDto.java`:
```java
package io.casehub.iot.openhab.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenHabStatusInfoDto(
    String status,
    String statusDetail
) {}
```

`OpenHabChannelDto.java`:
```java
package io.casehub.iot.openhab.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenHabChannelDto(
    String uid,
    String id,
    String channelTypeUID,
    String itemType,
    String kind,
    List<String> linkedItems,
    List<String> defaultTags
) {
    public boolean isStateChannel() {
        return !"TRIGGER".equals(kind);
    }
}
```

`OpenHabThingDto.java`:
```java
package io.casehub.iot.openhab.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenHabThingDto(
    @JsonProperty("UID") String uid,
    String label,
    String thingTypeUID,
    OpenHabStatusInfoDto statusInfo,
    List<OpenHabChannelDto> channels,
    String location
) {
    public List<OpenHabChannelDto> stateChannels() {
        return channels != null
            ? channels.stream().filter(OpenHabChannelDto::isStateChannel).toList()
            : List.of();
    }

    public boolean isOnline() {
        return statusInfo != null && "ONLINE".equals(statusInfo.status());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn --batch-mode -pl openhab test -Dtest=OpenHabDtoTest`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add openhab/src/main/java/io/casehub/iot/openhab/internal/OpenHabStatusInfoDto.java \
       openhab/src/main/java/io/casehub/iot/openhab/internal/OpenHabChannelDto.java \
       openhab/src/main/java/io/casehub/iot/openhab/internal/OpenHabThingDto.java \
       openhab/src/test/java/io/casehub/iot/openhab/OpenHabDtoTest.java
git commit -m "feat(openhab): Thing DTOs — ThingDto, ChannelDto, StatusInfoDto #11"
```

---

## Task 2: ResolvedDeviceFields + OpenHabDeviceBuilder (Shared Construction)

Extract device construction from `OpenHabEntityMapper` into shared code. Existing Equipment tests are the safety net.

**Files:**
- Create: `openhab/src/main/java/io/casehub/iot/openhab/ResolvedDeviceFields.java`
- Create: `openhab/src/main/java/io/casehub/iot/openhab/OpenHabDeviceBuilder.java`
- Create: `openhab/src/test/java/io/casehub/iot/openhab/OpenHabDeviceBuilderTest.java`

- [ ] **Step 1: Write failing test for shared construction**

```java
package io.casehub.iot.openhab;

import io.casehub.iot.api.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OpenHabDeviceBuilderTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    // ---- 1. THERMOSTAT without demand → ThermostatDevice ----

    @Test
    void thermostatWithoutDemandBuildsThermostatDevice() {
        var fields = new ResolvedDeviceFields.Builder()
            .deviceId("therm1").label("Hall Thermostat").available(true).now(NOW).tenancyId("t1")
            .deviceClass(DeviceClass.THERMOSTAT)
            .currentTemperature(new Temperature(new BigDecimal("20.0"), Temperature.TemperatureUnit.CELSIUS))
            .targetTemperature(new Temperature(new BigDecimal("21.0"), Temperature.TemperatureUnit.CELSIUS))
            .mode(ThermostatMode.HEAT)
            .build();

        DeviceEntity result = OpenHabDeviceBuilder.build(fields);

        assertThat(result).isInstanceOf(ThermostatDevice.class);
        assertThat(result).isNotInstanceOf(OpenHabThermostat.class);
        var therm = (ThermostatDevice) result;
        assertThat(therm.currentTemperature().value()).isEqualByComparingTo("20.0");
        assertThat(therm.mode()).isEqualTo(ThermostatMode.HEAT);
    }

    // ---- 2. THERMOSTAT with heating demand → OpenHabThermostat ----

    @Test
    void thermostatWithDemandBuildsOpenHabThermostat() {
        var fields = new ResolvedDeviceFields.Builder()
            .deviceId("therm2").label("Demand Thermostat").available(true).now(NOW).tenancyId("t1")
            .deviceClass(DeviceClass.THERMOSTAT)
            .currentTemperature(new Temperature(new BigDecimal("19.0"), Temperature.TemperatureUnit.CELSIUS))
            .targetTemperature(new Temperature(new BigDecimal("21.0"), Temperature.TemperatureUnit.CELSIUS))
            .mode(ThermostatMode.HEAT)
            .heatingDemand(new BigDecimal("75"))
            .build();

        DeviceEntity result = OpenHabDeviceBuilder.build(fields);

        assertThat(result).isInstanceOf(OpenHabThermostat.class);
        assertThat(((OpenHabThermostat) result).heatingDemand()).hasValue(new BigDecimal("75"));
    }

    // ---- 3. LIGHT with HSB → OpenHabLight ----

    @Test
    void lightWithHsbBuildsOpenHabLight() {
        var fields = new ResolvedDeviceFields.Builder()
            .deviceId("light1").label("Color Light").available(true).now(NOW).tenancyId("t1")
            .deviceClass(DeviceClass.LIGHT)
            .on(true).brightness(50)
            .hsb(new OpenHabHsbType(new BigDecimal("240"), new BigDecimal("100"), new BigDecimal("50")))
            .build();

        DeviceEntity result = OpenHabDeviceBuilder.build(fields);

        assertThat(result).isInstanceOf(OpenHabLight.class);
        assertThat(((OpenHabLight) result).hsb()).isPresent();
    }

    // ---- 4. LIGHT without HSB → LightDevice ----

    @Test
    void lightWithoutHsbBuildsLightDevice() {
        var fields = new ResolvedDeviceFields.Builder()
            .deviceId("light2").label("Simple Light").available(true).now(NOW).tenancyId("t1")
            .deviceClass(DeviceClass.LIGHT).on(true)
            .build();

        DeviceEntity result = OpenHabDeviceBuilder.build(fields);

        assertThat(result).isInstanceOf(LightDevice.class);
        assertThat(result).isNotInstanceOf(OpenHabLight.class);
    }

    // ---- 5. SWITCH → SwitchDevice ----

    @Test
    void switchBuildsSwitchDevice() {
        var fields = new ResolvedDeviceFields.Builder()
            .deviceId("sw1").label("Outlet").available(true).now(NOW).tenancyId("t1")
            .deviceClass(DeviceClass.SWITCH).on(true)
            .build();

        DeviceEntity result = OpenHabDeviceBuilder.build(fields);

        assertThat(result).isInstanceOf(SwitchDevice.class);
        assertThat(((SwitchDevice) result).isOn()).isTrue();
    }

    // ---- 6. COVER with rollershutter → OpenHabRollershutter ----

    @Test
    void coverRollershutterBuildsOpenHabRollershutter() {
        var fields = new ResolvedDeviceFields.Builder()
            .deviceId("cover1").label("Blinds").available(true).now(NOW).tenancyId("t1")
            .deviceClass(DeviceClass.COVER).position(70).isRollershutter(true)
            .build();

        DeviceEntity result = OpenHabDeviceBuilder.build(fields);

        assertThat(result).isInstanceOf(OpenHabRollershutter.class);
    }

    // ---- 7. POWER_SENSOR → PowerSensor ----

    @Test
    void powerSensorBuildsPowerSensor() {
        var fields = new ResolvedDeviceFields.Builder()
            .deviceId("pow1").label("Power Meter").available(true).now(NOW).tenancyId("t1")
            .deviceClass(DeviceClass.POWER_SENSOR).power(new BigDecimal("150.5"))
            .build();

        DeviceEntity result = OpenHabDeviceBuilder.build(fields);

        assertThat(result).isInstanceOf(PowerSensor.class);
        assertThat(((PowerSensor) result).power()).hasValue(new BigDecimal("150.5"));
    }

    // ---- 8. PRESENCE_SENSOR → PresenceSensor ----

    @Test
    void presenceSensorBuildsPresenceSensor() {
        var fields = new ResolvedDeviceFields.Builder()
            .deviceId("pres1").label("Motion").available(true).now(NOW).tenancyId("t1")
            .deviceClass(DeviceClass.PRESENCE_SENSOR).present(true)
            .build();

        DeviceEntity result = OpenHabDeviceBuilder.build(fields);

        assertThat(result).isInstanceOf(PresenceSensor.class);
        assertThat(((PresenceSensor) result).isPresent()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn --batch-mode -pl openhab test -Dtest=OpenHabDeviceBuilderTest`
Expected: FAIL — compilation error, `ResolvedDeviceFields` not found

- [ ] **Step 3: Create ResolvedDeviceFields**

```java
package io.casehub.iot.openhab;

import io.casehub.iot.api.*;
import java.math.BigDecimal;
import java.time.Instant;

public record ResolvedDeviceFields(
    String deviceId, String label, boolean available, Instant now, String tenancyId,
    DeviceClass deviceClass,
    Boolean on,
    Temperature currentTemperature, Temperature targetTemperature, ThermostatMode mode,
    BigDecimal heatingDemand, BigDecimal coolingDemand,
    OpenHabHsbType hsb, Integer brightness,
    Boolean locked,
    Integer position, boolean isRollershutter,
    Integer volume,
    SensorType sensorType, BigDecimal numericValue, String unit,
    BigDecimal power, BigDecimal energy,
    Boolean present
) {
    public static class Builder {
        private String deviceId, label, tenancyId, unit;
        private boolean available;
        private Instant now;
        private DeviceClass deviceClass;
        private Boolean on, locked, present;
        private Temperature currentTemperature, targetTemperature;
        private ThermostatMode mode;
        private BigDecimal heatingDemand, coolingDemand, numericValue, power, energy;
        private OpenHabHsbType hsb;
        private Integer brightness, position, volume;
        private boolean isRollershutter;
        private SensorType sensorType;

        public Builder deviceId(String v) { this.deviceId = v; return this; }
        public Builder label(String v) { this.label = v; return this; }
        public Builder available(boolean v) { this.available = v; return this; }
        public Builder now(Instant v) { this.now = v; return this; }
        public Builder tenancyId(String v) { this.tenancyId = v; return this; }
        public Builder deviceClass(DeviceClass v) { this.deviceClass = v; return this; }
        public Builder on(Boolean v) { this.on = v; return this; }
        public Builder currentTemperature(Temperature v) { this.currentTemperature = v; return this; }
        public Builder targetTemperature(Temperature v) { this.targetTemperature = v; return this; }
        public Builder mode(ThermostatMode v) { this.mode = v; return this; }
        public Builder heatingDemand(BigDecimal v) { this.heatingDemand = v; return this; }
        public Builder coolingDemand(BigDecimal v) { this.coolingDemand = v; return this; }
        public Builder hsb(OpenHabHsbType v) { this.hsb = v; return this; }
        public Builder brightness(Integer v) { this.brightness = v; return this; }
        public Builder locked(Boolean v) { this.locked = v; return this; }
        public Builder position(Integer v) { this.position = v; return this; }
        public Builder isRollershutter(boolean v) { this.isRollershutter = v; return this; }
        public Builder volume(Integer v) { this.volume = v; return this; }
        public Builder sensorType(SensorType v) { this.sensorType = v; return this; }
        public Builder numericValue(BigDecimal v) { this.numericValue = v; return this; }
        public Builder unit(String v) { this.unit = v; return this; }
        public Builder power(BigDecimal v) { this.power = v; return this; }
        public Builder energy(BigDecimal v) { this.energy = v; return this; }
        public Builder present(Boolean v) { this.present = v; return this; }

        public ResolvedDeviceFields build() {
            return new ResolvedDeviceFields(deviceId, label, available, now, tenancyId,
                deviceClass, on, currentTemperature, targetTemperature, mode,
                heatingDemand, coolingDemand, hsb, brightness, locked,
                position, isRollershutter, volume,
                sensorType, numericValue, unit, power, energy, present);
        }
    }
}
```

- [ ] **Step 4: Create OpenHabDeviceBuilder**

```java
package io.casehub.iot.openhab;

import io.casehub.iot.api.*;

public final class OpenHabDeviceBuilder {

    private OpenHabDeviceBuilder() {}

    public static DeviceEntity build(ResolvedDeviceFields f) {
        return switch (f.deviceClass()) {
            case THERMOSTAT -> buildThermostat(f);
            case LIGHT -> buildLight(f);
            case SWITCH -> buildSwitch(f);
            case LOCK -> buildLock(f);
            case COVER -> buildCover(f);
            case MEDIA_PLAYER -> buildMediaPlayer(f);
            case FAN -> buildFan(f);
            case SENSOR -> buildSensor(f);
            case POWER_SENSOR -> buildPowerSensor(f);
            case PRESENCE_SENSOR -> buildPresenceSensor(f);
            default -> null;
        };
    }

    private static DeviceEntity buildThermostat(ResolvedDeviceFields f) {
        var ct = f.currentTemperature() != null ? f.currentTemperature()
            : new Temperature(java.math.BigDecimal.ZERO, Temperature.TemperatureUnit.CELSIUS);
        var tt = f.targetTemperature() != null ? f.targetTemperature()
            : new Temperature(java.math.BigDecimal.ZERO, Temperature.TemperatureUnit.CELSIUS);
        var mode = f.mode() != null ? f.mode() : ThermostatMode.OFF;

        if (f.heatingDemand() != null || f.coolingDemand() != null) {
            return OpenHabThermostat.builder()
                .deviceId(f.deviceId()).deviceClass(DeviceClass.THERMOSTAT).label(f.label())
                .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId())
                .currentTemperature(ct).targetTemperature(tt).mode(mode)
                .heatingDemand(f.heatingDemand()).coolingDemand(f.coolingDemand())
                .build();
        }

        return new ThermostatDevice.Builder()
            .deviceId(f.deviceId()).deviceClass(DeviceClass.THERMOSTAT).label(f.label())
            .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId())
            .currentTemperature(ct).targetTemperature(tt).mode(mode)
            .build();
    }

    private static DeviceEntity buildLight(ResolvedDeviceFields f) {
        boolean on = f.on() != null && f.on();
        if (f.hsb() != null) {
            return OpenHabLight.builder()
                .deviceId(f.deviceId()).deviceClass(DeviceClass.LIGHT).label(f.label())
                .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId())
                .on(on).brightness(f.brightness()).hsb(f.hsb())
                .build();
        }
        return new LightDevice.Builder()
            .deviceId(f.deviceId()).deviceClass(DeviceClass.LIGHT).label(f.label())
            .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId())
            .on(on).brightness(f.brightness())
            .build();
    }

    private static SwitchDevice buildSwitch(ResolvedDeviceFields f) {
        return SwitchDevice.builder()
            .deviceId(f.deviceId()).deviceClass(DeviceClass.SWITCH).label(f.label())
            .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId())
            .on(f.on() != null && f.on())
            .build();
    }

    private static LockDevice buildLock(ResolvedDeviceFields f) {
        return new LockDevice.Builder()
            .deviceId(f.deviceId()).deviceClass(DeviceClass.LOCK).label(f.label())
            .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId())
            .locked(f.locked() != null && f.locked())
            .build();
    }

    private static DeviceEntity buildCover(ResolvedDeviceFields f) {
        if (f.isRollershutter()) {
            return OpenHabRollershutter.builder()
                .deviceId(f.deviceId()).deviceClass(DeviceClass.COVER).label(f.label())
                .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId())
                .position(f.position()).moving(false)
                .build();
        }
        return new CoverDevice.Builder()
            .deviceId(f.deviceId()).deviceClass(DeviceClass.COVER).label(f.label())
            .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId())
            .position(f.position()).moving(false)
            .build();
    }

    private static MediaPlayerDevice buildMediaPlayer(ResolvedDeviceFields f) {
        return MediaPlayerDevice.builder()
            .deviceId(f.deviceId()).deviceClass(DeviceClass.MEDIA_PLAYER).label(f.label())
            .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId())
            .volume(f.volume())
            .build();
    }

    private static FanDevice buildFan(ResolvedDeviceFields f) {
        return FanDevice.builder()
            .deviceId(f.deviceId()).deviceClass(DeviceClass.FAN).label(f.label())
            .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId())
            .on(f.on() != null && f.on())
            .build();
    }

    private static SensorDevice buildSensor(ResolvedDeviceFields f) {
        return SensorDevice.builder()
            .deviceId(f.deviceId()).deviceClass(DeviceClass.SENSOR).label(f.label())
            .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId())
            .sensorType(f.sensorType() != null ? f.sensorType() : SensorType.GENERIC)
            .numericValue(f.numericValue()).unit(f.unit())
            .build();
    }

    private static PowerSensor buildPowerSensor(ResolvedDeviceFields f) {
        return PowerSensor.builder()
            .deviceId(f.deviceId()).deviceClass(DeviceClass.POWER_SENSOR).label(f.label())
            .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId())
            .power(f.power()).energy(f.energy())
            .build();
    }

    private static PresenceSensor buildPresenceSensor(ResolvedDeviceFields f) {
        return PresenceSensor.builder()
            .deviceId(f.deviceId()).deviceClass(DeviceClass.PRESENCE_SENSOR).label(f.label())
            .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId())
            .present(f.present() != null && f.present())
            .lastSeen(f.now())
            .build();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn --batch-mode -pl openhab test -Dtest=OpenHabDeviceBuilderTest`
Expected: ALL PASS (8 tests)

- [ ] **Step 6: Commit**

```bash
git add openhab/src/main/java/io/casehub/iot/openhab/ResolvedDeviceFields.java \
       openhab/src/main/java/io/casehub/iot/openhab/OpenHabDeviceBuilder.java \
       openhab/src/test/java/io/casehub/iot/openhab/OpenHabDeviceBuilderTest.java
git commit -m "feat(openhab): ResolvedDeviceFields + OpenHabDeviceBuilder — shared construction #11"
```

---

## Task 3: Refactor OpenHabEntityMapper to Use Shared Construction

Extract Equipment resolution into `resolveFromEquipment()` that produces `ResolvedDeviceFields`, delegate construction to `OpenHabDeviceBuilder.build()`. Existing tests are the safety net — no new tests needed.

**Files:**
- Modify: `openhab/src/main/java/io/casehub/iot/openhab/OpenHabEntityMapper.java`

- [ ] **Step 1: Refactor mapEquipmentUnsafe to use ResolvedDeviceFields**

Replace the body of `mapEquipmentUnsafe` and all private `mapXxx` methods. The mapper becomes:

1. `mapEquipmentUnsafe` → calls `resolveFromEquipment` → calls `OpenHabDeviceBuilder.build`
2. `resolveFromEquipment` iterates members by semantic tags, populates a `ResolvedDeviceFields.Builder`
3. DeviceClass refinement for POWER_SENSOR and PRESENCE_SENSOR happens in `resolveFromEquipment` (the `resolveDeviceClass` method still returns SENSOR; the resolution strategy refines it)
4. All private `mapXxx` methods are removed — their logic moves into `resolveFromEquipment`
5. Parsing utilities (`parseTemperature`, `parseHsb`, `parseCoverPosition`, `resolveHvacMode`, `mapHvacModeString`, `resolveSensorType`, `parseOrNull`, `parseIntOrNull`, `isNullOrUndef`, `nullSafe`, `detectTemperatureUnit`, `isAvailable`) become package-private static methods (shared with Thing resolver)

The key structural change: `resolveDeviceClass` returns the initial hint (e.g., SENSOR). The resolution strategy then inspects members to refine — if it finds `Measurement+Power`, it sets `deviceClass = POWER_SENSOR` in the ResolvedDeviceFields. If it finds `Measurement+Presence`, it sets `deviceClass = PRESENCE_SENSOR`. Construction dispatches on the refined class.

- [ ] **Step 2: Run all existing mapper tests**

Run: `mvn --batch-mode -pl openhab test -Dtest=OpenHabEntityMapperTest`
Expected: ALL 27 PASS — behaviour unchanged

- [ ] **Step 3: Run full openhab module tests**

Run: `mvn --batch-mode -pl openhab -am test`
Expected: ALL PASS — SSE client tests, provider tests, supplement tests all unchanged

- [ ] **Step 4: Commit**

```bash
git add openhab/src/main/java/io/casehub/iot/openhab/OpenHabEntityMapper.java
git commit -m "refactor(openhab): extract Equipment resolution from construction — use ResolvedDeviceFields #11"
```

---

## Task 4: Thing Resolution Strategy + DeviceClass Inference

**Files:**
- Create: `openhab/src/main/java/io/casehub/iot/openhab/OpenHabThingResolver.java`
- Create: `openhab/src/test/java/io/casehub/iot/openhab/OpenHabThingResolverTest.java`

- [ ] **Step 1: Write failing tests for Thing resolution**

Create `OpenHabThingResolverTest.java` with tests covering:

1. Color channel → LIGHT with HSB
2. Dimmer channel (no Color) → LIGHT with brightness
3. Switch-only channel → SWITCH
4. Rollershutter channel → COVER (position inverted)
5. Player channel → MEDIA_PLAYER
6. Number:Power channel → POWER_SENSOR
7. Number:Energy channel → POWER_SENSOR
8. Dual Number:Temperature (with setpoint channelTypeUID) → THERMOSTAT
9. Single Number:Temperature → SENSOR with SensorType.TEMPERATURE
10. Number:Humidity → SENSOR with SensorType.HUMIDITY
11. Contact → SENSOR with SensorType.GENERIC
12. Bare Number → SENSOR with SensorType.GENERIC
13. OFFLINE Thing → available=false
14. No mappable channels → null
15. TRIGGER channels excluded
16. Priority: Switch+Number Thing → SWITCH (not SENSOR)
17. Priority: Color+Switch Thing → LIGHT (not SWITCH)
18. Thermostat mode from String channel with "mode" in id
19. Thermostat mode defaults to OFF when no mode channel
20. Switch channel OFF on thermostat → mode OFF

Each test builds an `OpenHabThingDto` with appropriate channels, a `Map<String, OpenHabItemDto>` for item states, and calls `resolver.resolve(thing, itemStates, NOW)`.

Test helper:
```java
private OpenHabThingDto thing(String uid, String label, String status, OpenHabChannelDto... channels) {
    return new OpenHabThingDto(uid, label, "binding:type",
        new OpenHabStatusInfoDto(status, "NONE"), List.of(channels), null);
}

private OpenHabChannelDto channel(String id, String itemType, String kind, String... linkedItems) {
    return new OpenHabChannelDto("thing:" + id, id, "system:" + id,
        itemType, kind, List.of(linkedItems), List.of());
}

private OpenHabChannelDto channelWithType(String id, String itemType, String channelTypeUID, String... linkedItems) {
    return new OpenHabChannelDto("thing:" + id, id, channelTypeUID,
        itemType, "STATE", List.of(linkedItems), List.of());
}

private OpenHabItemDto item(String name, String state) {
    return new OpenHabItemDto(null, name, name, state, null, null, null);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode -pl openhab test -Dtest=OpenHabThingResolverTest`
Expected: FAIL — compilation error, `OpenHabThingResolver` not found

- [ ] **Step 3: Implement OpenHabThingResolver**

```java
package io.casehub.iot.openhab;

import io.casehub.iot.api.*;
import io.casehub.iot.openhab.internal.OpenHabChannelDto;
import io.casehub.iot.openhab.internal.OpenHabItemDto;
import io.casehub.iot.openhab.internal.OpenHabThingDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.casehub.iot.openhab.OpenHabEntityMapper.*;

public class OpenHabThingResolver {

    private final String tenancyId;

    public OpenHabThingResolver(String tenancyId) {
        this.tenancyId = tenancyId;
    }

    public ResolvedDeviceFields resolve(OpenHabThingDto thing, Map<String, OpenHabItemDto> itemStates, Instant now) {
        List<OpenHabChannelDto> channels = thing.stateChannels();
        DeviceClass deviceClass = resolveDeviceClass(channels);
        if (deviceClass == null) return null;

        boolean available = thing.isOnline();
        String label = thing.label() != null ? thing.label() : thing.uid();

        var builder = new ResolvedDeviceFields.Builder()
            .deviceId(thing.uid()).label(label).available(available).now(now).tenancyId(tenancyId)
            .deviceClass(deviceClass);

        populateFields(builder, deviceClass, channels, itemStates);

        return builder.build();
    }
}
```

The `resolveDeviceClass` method scans ALL state channels and picks the highest-priority candidate per the spec's priority table (scan all, pick highest — not first-match-wins).

The `populateFields` method extracts field values from channel-linked item states based on `itemType`. It uses the shared parsing utilities from `OpenHabEntityMapper` (now package-private static).

For thermostat mode: looks for a `Switch` channel (power override) and a `String` channel with `channelTypeUID` or `id` containing "mode". Uses shared `mapHvacModeString()`.

For setpoint disambiguation (thermostat and command dispatch): checks `channelTypeUID` and `id` for `setpoint`, `target`, `desired` (case-insensitive).

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode -pl openhab test -Dtest=OpenHabThingResolverTest`
Expected: ALL PASS (20 tests)

- [ ] **Step 5: Run full module tests to verify no regressions**

Run: `mvn --batch-mode -pl openhab -am test`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add openhab/src/main/java/io/casehub/iot/openhab/OpenHabThingResolver.java \
       openhab/src/test/java/io/casehub/iot/openhab/OpenHabThingResolverTest.java
git commit -m "feat(openhab): Thing resolution strategy + DeviceClass priority inference #11"
```

---

## Task 5: REST Client + Config Changes

**Files:**
- Modify: `openhab/src/main/java/io/casehub/iot/openhab/OpenHabRestClient.java`
- Modify: `openhab/src/main/java/io/casehub/iot/openhab/OpenHabConfig.java`
- Modify: `openhab/src/test/resources/application.properties`

- [ ] **Step 1: Add getThings() and getAllItems() to REST client**

Add to `OpenHabRestClient.java`:

```java
@GET @Path("/rest/things")
Uni<List<OpenHabThingDto>> getThings();

@GET @Path("/rest/items")
Uni<List<OpenHabItemDto>> getAllItems();
```

Add import for `OpenHabThingDto`.

- [ ] **Step 2: Add thingDiscoveryEnabled to config**

Add to `OpenHabConfig.java`:

```java
@WithDefault("true") boolean thingDiscoveryEnabled();
```

- [ ] **Step 3: Add config property to test properties**

Add to `openhab/src/test/resources/application.properties`:

```properties
casehub.iot.openhab.thing-discovery-enabled=true
```

- [ ] **Step 4: Verify compilation**

Run: `mvn --batch-mode -pl openhab compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add openhab/src/main/java/io/casehub/iot/openhab/OpenHabRestClient.java \
       openhab/src/main/java/io/casehub/iot/openhab/OpenHabConfig.java \
       openhab/src/test/resources/application.properties
git commit -m "feat(openhab): REST client getThings/getAllItems + thingDiscoveryEnabled config #11"
```

---

## Task 6: Layered Discovery Pipeline in SSE Client

The largest task. Extends `OpenHabSseClient` with Thing caches, dual reverse indexes, layered discovery pipeline, and Thing status SSE handling.

**Files:**
- Modify: `openhab/src/main/java/io/casehub/iot/openhab/OpenHabSseClient.java`
- Modify: `openhab/src/main/java/io/casehub/iot/openhab/OpenHabSseRestClient.java`
- Modify: `openhab/src/test/java/io/casehub/iot/openhab/OpenHabSseClientTest.java`

- [ ] **Step 1: Write failing tests for layered discovery**

Add to `OpenHabSseClientTest.java`:

1. `populateThingCaches_unmappedThingProducesDevice` — Thing with Switch channel, no Equipment overlap → appears in `cachedDevices()`
2. `populateThingCaches_equipmentBackedThingSkipped` — Thing whose linked item IS in Equipment → NOT in thingDeviceCache
3. `populateThingCaches_partialOverlapTreatedAsEquipmentBacked` — Thing with some items in Equipment → Equipment-backed
4. `thingStatusOfflineOverridesEquipmentAvailability` — Equipment-backed device has `available=true` from items, Thing OFFLINE → device becomes unavailable
5. `handleThingStatusEvent_offlineUpdatesThingDevice` — SSE ThingStatusInfoChangedEvent OFFLINE → Thing-only device availability updated
6. `handleThingStatusEvent_onlineRestoresAvailability` — SSE ThingStatusInfoChangedEvent ONLINE → availability restored
7. `handleThingStatusEvent_unknownThingIgnored` — unknown Thing UID → no crash, no state change
8. `sseEventForThingLinkedItem_updatesThingDeviceCache` — SSE ItemStateChangedEvent for Thing-linked item updates Thing device cache
9. `equipmentItemPriorityOverThingItem` — item in both Equipment and Thing → Equipment path used

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode -pl openhab test -Dtest=OpenHabSseClientTest`
Expected: FAIL — new methods not found

- [ ] **Step 3: Implement layered discovery in OpenHabSseClient**

Key changes:

1. **New fields:**
```java
private final ConcurrentHashMap<String, DeviceEntity> thingDeviceCache = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, OpenHabThingDto> thingCache = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, String> itemToThing = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, String> thingToEquipment = new ConcurrentHashMap<>();
// Item states for Thing-linked items (needed for SSE reconstruction)
private final ConcurrentHashMap<String, ConcurrentHashMap<String, OpenHabItemDto>> thingItemStateCache = new ConcurrentHashMap<>();
```

2. **Inject OpenHabThingResolver** (or construct in test constructor)

3. **cachedDevices()** returns union: `List.copyOf(Stream.concat(deviceCache.values().stream(), thingDeviceCache.values().stream()).toList())`

4. **populateThingCaches(things, itemLookup)** — for unmapped Things: build thingCache, thingItemStateCache, itemToThing, resolve via `OpenHabThingResolver` + `OpenHabDeviceBuilder`, populate thingDeviceCache

5. **buildThingIndexes(things, coveredItems)** — always runs: builds thingToEquipment for Equipment-backed Things

6. **enhanceAvailabilityFromThings(things, coveredItems)** — for Equipment-backed Things: if Thing OFFLINE, rebuild Equipment device with available=false

7. **handleSseEvent** — add ThingStatusInfoChangedEvent handling and itemToThing lookup

8. **SSE topic change** in `subscribeSse()`: `"openhab/items/*/statechanged,openhab/things/*/statuschanged"`

9. **stop()** — clear new caches

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn --batch-mode -pl openhab test -Dtest=OpenHabSseClientTest`
Expected: ALL PASS

- [ ] **Step 5: Run full module tests**

Run: `mvn --batch-mode -pl openhab -am test`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add openhab/src/main/java/io/casehub/iot/openhab/OpenHabSseClient.java \
       openhab/src/main/java/io/casehub/iot/openhab/OpenHabSseRestClient.java \
       openhab/src/test/java/io/casehub/iot/openhab/OpenHabSseClientTest.java
git commit -m "feat(openhab): layered discovery pipeline + Thing status SSE + dual indexes #11"
```

---

## Task 7: Provider Wiring + Command Dispatch for Thing-Scoped Devices

**Files:**
- Modify: `openhab/src/main/java/io/casehub/iot/openhab/OpenHabProvider.java`
- Modify: `openhab/src/main/java/io/casehub/iot/openhab/OpenHabSseClient.java` (add `resolveByChannelType`)
- Modify: `openhab/src/test/java/io/casehub/iot/openhab/OpenHabCommandDispatchTest.java`
- Modify: `openhab/src/test/java/io/casehub/iot/openhab/OpenHabProviderTest.java`

- [ ] **Step 1: Write failing tests for Thing-scoped command dispatch**

Add to `OpenHabCommandDispatchTest.java`:

1. `turnOnThingScopedSwitch_resolvesByChannelType` — Thing with Switch channel, turn_on → resolves to linked item
2. `setTemperatureThingScopedThermostat_findsSetpointChannel` — Thing with two Temperature channels, set_temperature → resolves to setpoint (not measurement)
3. `triggerChannelExcludedFromDispatch` — Thing with TRIGGER Switch channel → not resolved

Add to `OpenHabProviderTest.java`:

1. `discoverReturnsLayeredResults` — mock both Equipment and Thing REST calls → merged results
2. `thingDiscoveryDisabledSkipsThingMapping` — config false → only Equipment devices

- [ ] **Step 2: Run tests to verify they fail**

Expected: FAIL — `resolveByChannelType` not found or Thing dispatch path missing

- [ ] **Step 3: Implement Thing-scoped resolveTargetItem path**

In `OpenHabSseClient.resolveTargetItem`:

```java
public String resolveTargetItem(DeviceCommand command) {
    String deviceId = command.targetDeviceId();

    // Equipment path (existing)
    if (equipmentCache.containsKey(deviceId)) {
        // ... existing semantic tag resolution ...
    }

    // Thing path (new)
    OpenHabThingDto thing = thingCache.get(deviceId);
    if (thing != null) {
        return resolveByChannelType(command, thing);
    }

    return null;
}
```

`resolveByChannelType` maps command actions to channel `itemType` per the spec table. For `set_temperature`, uses the same setpoint channelTypeUID/id heuristics. Filters STATE channels only.

- [ ] **Step 4: Wire provider's connect() to use SSE client's layered pipeline**

The SSE client's `connect()` method already does the layered pipeline (Task 6). The provider calls it unchanged. `discover()` delegates to `sseClient.cachedDevices()` which now returns the union.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn --batch-mode -pl openhab -am test`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add openhab/src/main/java/io/casehub/iot/openhab/OpenHabSseClient.java \
       openhab/src/main/java/io/casehub/iot/openhab/OpenHabProvider.java \
       openhab/src/test/java/io/casehub/iot/openhab/OpenHabCommandDispatchTest.java \
       openhab/src/test/java/io/casehub/iot/openhab/OpenHabProviderTest.java
git commit -m "feat(openhab): Thing-scoped command dispatch + provider wiring #11"
```

---

## Task 8: Full Integration Verification + Close

- [ ] **Step 1: Run full mvn install across all modules**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS — all modules, all tests

- [ ] **Step 2: Verify test counts**

Expected: openhab module tests increased substantially (new DTOs, builder, resolver, SSE, dispatch tests)

- [ ] **Step 3: Update ARC42STORIES.MD**

Update C5 references if needed. The Chapter 4 layer entry should note that Thing-scoped discovery is now implemented.

- [ ] **Step 4: Final commit + close issue**

```bash
gh issue close 11 --repo casehubio/iot --comment "Layered discovery implemented: Equipment + Thing always run and merge. ResolvedDeviceFields eliminates mapper duplication. Thing status SSE for real-time availability. Channel-type command dispatch."
```

---

## Execution Order Summary

| Task | Depends on | What it delivers |
|------|-----------|-----------------|
| 1 | — | Thing DTOs (ThingDto, ChannelDto, StatusInfoDto) |
| 2 | — | ResolvedDeviceFields + OpenHabDeviceBuilder (shared construction) |
| 3 | 2 | OpenHabEntityMapper refactored to use shared construction |
| 4 | 2, 3 | OpenHabThingResolver (Thing → ResolvedDeviceFields) |
| 5 | 1 | REST client + config changes |
| 6 | 1, 3, 4, 5 | Layered discovery pipeline + SSE + caches |
| 7 | 6 | Command dispatch + provider wiring |
| 8 | 7 | Full integration verification |

Tasks 1 and 2 can run in parallel. Task 3 depends on 2. Task 4 depends on 2+3. Tasks 5 depends on 1. Task 6 is the integration point. Tasks 7 and 8 are sequential.

package io.casehub.iot.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class IoTCloudEventAdapterTest {

    private static final Instant OCCURRED = Instant.parse("2026-06-20T12:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private List<CloudEvent> firedEvents;
    private IoTCloudEventAdapter adapter;

    @BeforeEach
    void setUp() {
        firedEvents = new ArrayList<>();
        Event<CloudEvent> capturingEvent = new CapturingEvent(firedEvents);
        adapter = new IoTCloudEventAdapter(capturingEvent, MAPPER);
    }

    @Test
    void correctMapping() throws Exception {
        var after = new ThermostatDevice.Builder()
            .deviceId("therm-1").deviceClass(DeviceClass.THERMOSTAT).label("Living Room")
            .available(true).lastUpdated(OCCURRED).tenancyId("tenant-a").providerId("homeassistant")
            .currentTemperature(new Temperature(BigDecimal.valueOf(21), Temperature.TemperatureUnit.CELSIUS))
            .targetTemperature(new Temperature(BigDecimal.valueOf(22), Temperature.TemperatureUnit.CELSIUS))
            .mode(ThermostatMode.HEAT)
            .build();
        var before = new ThermostatDevice.Builder()
            .deviceId("therm-1").deviceClass(DeviceClass.THERMOSTAT).label("Living Room")
            .available(true).lastUpdated(OCCURRED).tenancyId("tenant-a").providerId("homeassistant")
            .currentTemperature(new Temperature(BigDecimal.valueOf(20), Temperature.TemperatureUnit.CELSIUS))
            .targetTemperature(new Temperature(BigDecimal.valueOf(22), Temperature.TemperatureUnit.CELSIUS))
            .mode(ThermostatMode.HEAT)
            .build();
        var event = new StateChangeEvent(before, after,
            Set.of(ThermostatDevice.CAP_CURRENT_TEMPERATURE), OCCURRED, "homeassistant");

        adapter.onStateChange(event);

        assertThat(firedEvents).hasSize(1);
        CloudEvent ce = firedEvents.get(0);
        assertThat(ce.getType()).isEqualTo("io.casehub.iot.state_change.thermostat");
        assertThat(ce.getSource()).isEqualTo(URI.create("/casehub-iot"));
        assertThat(ce.getSubject()).isEqualTo("device/therm-1");
        assertThat(ce.getTime()).isEqualTo(OCCURRED.atOffset(ZoneOffset.UTC));
        assertThat(ce.getId()).isNotBlank();
        assertThat(ce.getDataContentType()).isEqualTo("application/json");
        assertThat(ce.getExtension("tenancyid")).isEqualTo("tenant-a");
        assertThat(ce.getExtension("providerid")).isEqualTo("homeassistant");

        byte[] data = ce.getData().toBytes();
        StateChangeEvent deserialized = MAPPER.readValue(data, StateChangeEvent.class);
        assertThat(deserialized.after().deviceId()).isEqualTo("therm-1");
        assertThat(deserialized.before().deviceId()).isEqualTo("therm-1");
        assertThat(deserialized.changedCapabilities()).containsExactly(ThermostatDevice.CAP_CURRENT_TEMPERATURE);
    }

    @Test
    void polymorphismGuard() {
        var after = new FakeThermostat.Builder()
            .deviceId("ha-therm").deviceClass(DeviceClass.THERMOSTAT).label("HA Thermostat")
            .available(true).lastUpdated(OCCURRED).tenancyId("tenant-a").providerId("homeassistant")
            .currentTemperature(new Temperature(BigDecimal.valueOf(21), Temperature.TemperatureUnit.CELSIUS))
            .targetTemperature(new Temperature(BigDecimal.valueOf(22), Temperature.TemperatureUnit.CELSIUS))
            .mode(ThermostatMode.HEAT)
            .build();
        var event = new StateChangeEvent(null, after, Set.of(), OCCURRED, "homeassistant");

        adapter.onStateChange(event);

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).getType())
            .isEqualTo("io.casehub.iot.state_change.thermostat");
    }

    @Test
    void nullBefore_initialDiscovery() throws Exception {
        var after = new ThermostatDevice.Builder()
            .deviceId("therm-new").deviceClass(DeviceClass.THERMOSTAT).label("New Thermostat")
            .available(true).lastUpdated(OCCURRED).tenancyId("tenant-b").providerId("openhab")
            .currentTemperature(new Temperature(BigDecimal.valueOf(19), Temperature.TemperatureUnit.CELSIUS))
            .targetTemperature(new Temperature(BigDecimal.valueOf(21), Temperature.TemperatureUnit.CELSIUS))
            .mode(ThermostatMode.AUTO)
            .build();
        var event = new StateChangeEvent(null, after, Set.of(), OCCURRED, "openhab");

        adapter.onStateChange(event);

        assertThat(firedEvents).hasSize(1);
        CloudEvent ce = firedEvents.get(0);
        assertThat(ce.getType()).isEqualTo("io.casehub.iot.state_change.thermostat");
        assertThat(ce.getExtension("providerid")).isEqualTo("openhab");

        StateChangeEvent deserialized = MAPPER.readValue(ce.getData().toBytes(), StateChangeEvent.class);
        assertThat(deserialized.before()).isNull();
        assertThat(deserialized.after().deviceId()).isEqualTo("therm-new");
    }

    @Test
    void compoundDeviceClass_underscoreConvention() {
        var after = PresenceSensor.builder()
            .deviceId("ps-1").deviceClass(DeviceClass.PRESENCE_SENSOR).label("Front Door")
            .available(true).lastUpdated(OCCURRED).tenancyId("tenant-a").providerId("homeassistant")
            .present(true).lastSeen(OCCURRED)
            .build();
        var event = new StateChangeEvent(null, after, Set.of(), OCCURRED, "homeassistant");

        adapter.onStateChange(event);

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).getType())
            .isEqualTo("io.casehub.iot.state_change.presence_sensor");
    }

    private static class FakeThermostat extends ThermostatDevice {
        private FakeThermostat(Builder builder) { super(builder); }
        static class Builder extends ThermostatDevice.AbstractBuilder<FakeThermostat, Builder> {
            @Override protected Builder self() { return this; }
            @Override public FakeThermostat build() { return new FakeThermostat(this); }
        }
    }

    private static class CapturingEvent implements Event<CloudEvent> {
        private final List<CloudEvent> captured;

        CapturingEvent(List<CloudEvent> captured) {
            this.captured = captured;
        }

        @Override
        public void fire(CloudEvent event) {
            throw new UnsupportedOperationException("adapter must use fireAsync");
        }

        @Override
        public <U extends CloudEvent> CompletionStage<U> fireAsync(U event) {
            captured.add(event);
            return CompletableFuture.completedFuture(event);
        }

        @Override
        public <U extends CloudEvent> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
            return fireAsync(event);
        }

        @Override public Event<CloudEvent> select(Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public <U extends CloudEvent> Event<U> select(Class<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public <U extends CloudEvent> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
    }
}

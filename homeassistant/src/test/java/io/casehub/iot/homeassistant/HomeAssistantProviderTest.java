package io.casehub.iot.homeassistant;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.ProviderStatus;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.iot.homeassistant.TestHttpServerResource.StubbedResponse;
import io.casehub.iot.homeassistant.TestHttpServerResource.RecordedRequest;
import io.casehub.iot.homeassistant.TestHttpServerResource.TestHttpServer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(value = TestHttpServerResource.class, restrictToAnnotatedClass = true)
class HomeAssistantProviderTest {

    @Inject HomeAssistantProvider provider;

    private TestHttpServer server() {
        return TestHttpServerResource.INSTANCE;
    }

    @BeforeEach
    void drainStaleRequests() {
        server().drainRequests();
    }

    @Test
    void providerIdIsHomeassistant() {
        assertThat(provider.providerId()).isEqualTo("homeassistant");
    }

    @Test
    void discoverMapsHaStatesToDeviceEntities() {
        server().enqueue(StubbedResponse.json(200, """
                [
                  {"entity_id":"light.kitchen","state":"on","last_updated":"2026-06-09T10:00:00Z","last_changed":"2026-06-09T09:55:00Z","attributes":{"friendly_name":"Kitchen Light","brightness":200}},
                  {"entity_id":"switch.hallway","state":"off","last_updated":"2026-06-09T10:00:00Z","last_changed":"2026-06-09T09:50:00Z","attributes":{"friendly_name":"Hallway Switch"}},
                  {"entity_id":"lock.front","state":"locked","last_updated":"2026-06-09T10:00:00Z","last_changed":"2026-06-09T09:45:00Z","attributes":{"friendly_name":"Front Door Lock"}}
                ]
                """));

        List<DeviceEntity> devices = provider.discover().await().indefinitely();

        assertThat(devices).hasSize(3);
        assertThat(devices).extracting(DeviceEntity::deviceId)
                .containsExactly("light.kitchen", "switch.hallway", "lock.front");
    }

    @Test
    void dispatchTurnOnSendsCorrectServiceCall() {
        server().enqueue(StubbedResponse.json(200, "[]"));

        DeviceCommand cmd = DeviceCommand.turnOn("light.kitchen",
                Map.of("brightness", 200), "user1", "corr1");
        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.SENT);

        RecordedRequest request = server().takeRequest();
        assertThat(request).isNotNull();
        assertThat(request.path()).isEqualTo("/api/services/light/turn_on");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.body()).contains("\"entity_id\":\"light.kitchen\"");
        assertThat(request.body()).contains("\"brightness\"");
    }

    @Test
    void dispatchReturnsFailedOnHttp500() {
        server().enqueue(StubbedResponse.json(500, "{\"error\":\"internal\"}"));

        DeviceCommand cmd = DeviceCommand.turnOn("light.kitchen",
                Map.of(), "user1", "corr1");
        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.FAILED);
    }

    @Test
    void dispatchReturnsFailedOnUnknownAction() {
        DeviceCommand cmd = new DeviceCommand("light.kitchen",
                "unknown_action", Map.of(), "user1", "corr1");
        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.FAILED);
    }

    @Test
    void dispatchSetVolumeConvertsToFloat() {
        server().enqueue(StubbedResponse.json(200, "[]"));

        DeviceCommand cmd = DeviceCommand.setVolume("media_player.speaker",
                65, "user1", "corr1");
        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.SENT);

        RecordedRequest request = server().takeRequest();
        assertThat(request).isNotNull();
        assertThat(request.path()).isEqualTo("/api/services/media_player/volume_set");
        assertThat(request.body()).contains("\"entity_id\":\"media_player.speaker\"");
        assertThat(request.body()).contains("\"volume_level\"");
        assertThat(request.body()).contains("0.65");
    }

    @Test
    void statusDelegatesToWebSocketClient() {
        ProviderStatus status = provider.status();

        assertThat(status).isNotNull();
        assertThat(status).isIn(ProviderStatus.CONNECTED, ProviderStatus.CONNECTING,
                ProviderStatus.DISCONNECTED);
    }

    @Test
    void dispatchTurnOffSendsEmptyParameters() {
        server().enqueue(StubbedResponse.json(200, "[]"));

        DeviceCommand cmd = DeviceCommand.turnOff("switch.hallway", "user1", "corr1");
        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.SENT);

        RecordedRequest request = server().takeRequest();
        assertThat(request).isNotNull();
        assertThat(request.path()).isEqualTo("/api/services/switch/turn_off");
        assertThat(request.body()).contains("\"entity_id\":\"switch.hallway\"");
    }

    @Test
    void dispatchLockSendsCorrectServiceCall() {
        server().enqueue(StubbedResponse.json(200, "[]"));

        DeviceCommand cmd = DeviceCommand.lock("lock.front_door", "user1", "corr1");
        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.SENT);

        RecordedRequest request = server().takeRequest();
        assertThat(request).isNotNull();
        assertThat(request.path()).isEqualTo("/api/services/lock/lock");
    }

    @Test
    void dispatchUnlockSendsCorrectServiceCall() {
        server().enqueue(StubbedResponse.json(200, "[]"));

        DeviceCommand cmd = DeviceCommand.unlock("lock.front_door", "user1", "corr1");
        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.SENT);

        RecordedRequest request = server().takeRequest();
        assertThat(request).isNotNull();
        assertThat(request.path()).isEqualTo("/api/services/lock/unlock");
    }

    @Test
    void dispatchSetPositionSendsCorrectServiceCall() {
        server().enqueue(StubbedResponse.json(200, "[]"));

        DeviceCommand cmd = DeviceCommand.setPosition("cover.blinds", 75, "user1", "corr1");
        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.SENT);

        RecordedRequest request = server().takeRequest();
        assertThat(request).isNotNull();
        assertThat(request.path()).isEqualTo("/api/services/cover/set_cover_position");
        assertThat(request.body()).contains("\"entity_id\":\"cover.blinds\"");
        assertThat(request.body()).contains("\"position\"");
    }
}

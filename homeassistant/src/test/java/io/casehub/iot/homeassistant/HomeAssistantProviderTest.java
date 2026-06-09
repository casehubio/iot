package io.casehub.iot.homeassistant;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.ProviderStatus;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(MockWebServerResource.class)
class HomeAssistantProviderTest {

    @Inject HomeAssistantProvider provider;

    private MockWebServer server() {
        return MockWebServerResource.INSTANCE;
    }

    /**
     * Drain any stale requests left by other tests sharing the same MockWebServer.
     */
    @BeforeEach
    void drainStaleRequests() throws InterruptedException {
        while (server().takeRequest(50, TimeUnit.MILLISECONDS) != null) {
            // drain
        }
    }

    @Test
    void providerIdIsHomeassistant() {
        assertThat(provider.providerId()).isEqualTo("homeassistant");
    }

    @Test
    void discoverMapsHaStatesToDeviceEntities() {
        server().enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
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
    void dispatchTurnOnSendsCorrectServiceCall() throws InterruptedException {
        server().enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[]"));

        DeviceCommand cmd = DeviceCommand.turnOn("light.kitchen",
                Map.of("brightness", 200), "user1", "corr1");
        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.SENT);

        RecordedRequest request = server().takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/api/services/light/turn_on");
        assertThat(request.getMethod()).isEqualTo("POST");

        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"entity_id\":\"light.kitchen\"");
        assertThat(body).contains("\"brightness\"");
    }

    @Test
    void dispatchReturnsFailedOnHttp500() {
        server().enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"internal\"}"));

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
    void dispatchSetVolumeConvertsToFloat() throws InterruptedException {
        server().enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[]"));

        DeviceCommand cmd = DeviceCommand.setVolume("media_player.speaker",
                65, "user1", "corr1");
        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.SENT);

        RecordedRequest request = server().takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/api/services/media_player/volume_set");

        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"entity_id\":\"media_player.speaker\"");
        assertThat(body).contains("\"volume_level\"");
        assertThat(body).contains("0.65");
    }

    @Test
    void statusDelegatesToWebSocketClient() {
        ProviderStatus status = provider.status();

        assertThat(status).isNotNull();
        assertThat(status).isIn(ProviderStatus.CONNECTED, ProviderStatus.CONNECTING,
                ProviderStatus.DISCONNECTED);
    }

    @Test
    void dispatchTurnOffSendsEmptyParameters() throws InterruptedException {
        server().enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[]"));

        DeviceCommand cmd = DeviceCommand.turnOff("switch.hallway", "user1", "corr1");
        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.SENT);

        RecordedRequest request = server().takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/api/services/switch/turn_off");

        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"entity_id\":\"switch.hallway\"");
    }

    @Test
    void dispatchLockSendsCorrectServiceCall() throws InterruptedException {
        server().enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[]"));

        DeviceCommand cmd = DeviceCommand.lock("lock.front_door", "user1", "corr1");
        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.SENT);

        RecordedRequest request = server().takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/api/services/lock/lock");
    }

    @Test
    void dispatchUnlockSendsCorrectServiceCall() throws InterruptedException {
        server().enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[]"));

        DeviceCommand cmd = DeviceCommand.unlock("lock.front_door", "user1", "corr1");
        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.SENT);

        RecordedRequest request = server().takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/api/services/lock/unlock");
    }

    @Test
    void dispatchSetPositionSendsCorrectServiceCall() throws InterruptedException {
        server().enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[]"));

        DeviceCommand cmd = DeviceCommand.setPosition("cover.blinds", 75, "user1", "corr1");
        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.SENT);

        RecordedRequest request = server().takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/api/services/cover/set_cover_position");

        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"entity_id\":\"cover.blinds\"");
        assertThat(body).contains("\"position\"");
    }
}

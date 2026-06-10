package io.casehub.iot.openhab;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.ProviderStatus;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(OpenHabMockServerResource.class)
class OpenHabProviderTest {

    @Inject OpenHabProvider provider;

    private MockWebServer server() {
        return OpenHabMockServerResource.INSTANCE;
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
    void providerIdIsOpenhab() {
        assertThat(provider.providerId()).isEqualTo("openhab");
    }

    @Test
    void discoverMapsEquipmentToDeviceEntities() throws InterruptedException {
        server().enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        [
                          {"type":"Group","name":"Light_Kitchen","label":"Kitchen Light","state":"NULL","tags":["Equipment","Lightbulb"],"members":[
                            {"type":"Switch","name":"Light_Kitchen_Switch","state":"ON","tags":["Control","Switch"]}
                          ]},
                          {"type":"Group","name":"Lock_Front","label":"Front Door Lock","state":"NULL","tags":["Equipment","Lock"],"members":[
                            {"type":"Switch","name":"Lock_Front_Switch","state":"ON","tags":["Control","Switch"]}
                          ]}
                        ]
                        """));

        List<DeviceEntity> devices = provider.discover().await().indefinitely();

        assertThat(devices).hasSize(2);
        assertThat(devices).extracting(DeviceEntity::deviceId)
                .containsExactly("Light_Kitchen", "Lock_Front");

        var request = server().takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).contains("/rest/items");
        assertThat(request.getPath()).contains("tags=Equipment");
        assertThat(request.getPath()).contains("recursive=true");
    }

    @Test
    void dispatchReturnsFailedWhenTargetItemNotResolved() {
        // sseClient stub returns null from resolveTargetItem → dispatch should return FAILED
        DeviceCommand cmd = DeviceCommand.turnOn("Light_Kitchen",
                Map.of(), "user1", "corr1");
        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.FAILED);
    }

    @Test
    void dispatchReturnsFailedOnUnknownAction() {
        DeviceCommand cmd = new DeviceCommand("Light_Kitchen",
                "unknown_action", Map.of(), "user1", "corr1");
        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.FAILED);
    }

    @Test
    void statusDelegatesToSseClient() {
        ProviderStatus status = provider.status();

        assertThat(status).isNotNull();
        assertThat(status).isIn(ProviderStatus.CONNECTED, ProviderStatus.CONNECTING,
                ProviderStatus.DISCONNECTED);
    }
}

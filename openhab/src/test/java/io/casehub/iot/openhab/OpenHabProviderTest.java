package io.casehub.iot.openhab;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.ProviderStatus;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(OpenHabMockServerResource.class)
class OpenHabProviderTest {

    @Inject OpenHabProvider provider;

    @AfterEach
    void resetMockServer() {
        OpenHabMockServerResource.reset();
    }

    @Test
    void providerIdIsOpenhab() {
        assertThat(provider.providerId()).isEqualTo("openhab");
    }

    @Test
    void discoverMapsEquipmentToDeviceEntities() {
        OpenHabMockServerResource.setEquipmentBody("""
                [
                  {"type":"Group","name":"Light_Kitchen","label":"Kitchen Light","state":"NULL","tags":["Equipment","Lightbulb"],"members":[
                    {"type":"Switch","name":"Light_Kitchen_Switch","state":"ON","tags":["Control","Switch"]}
                  ]},
                  {"type":"Group","name":"Lock_Front","label":"Front Door Lock","state":"NULL","tags":["Equipment","Lock"],"members":[
                    {"type":"Switch","name":"Lock_Front_Switch","state":"ON","tags":["Control","Switch"]}
                  ]}
                ]
                """);

        List<DeviceEntity> devices = provider.discover().await().indefinitely();

        assertThat(devices).hasSize(2);
        assertThat(devices).extracting(DeviceEntity::deviceId)
                .containsExactly("Light_Kitchen", "Lock_Front");
    }

    @Test
    void dispatchReturnsFailedWhenTargetItemNotResolved() {
        // sseClient returns null from resolveTargetItem (no cached equipment with this ID)
        DeviceCommand cmd = DeviceCommand.turnOn("NonExistent_Device",
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
    void discoverySendsBearerAuthHeader() {
        OpenHabMockServerResource.setEquipmentBody("[]");

        provider.discover().await().indefinitely();

        assertThat(OpenHabMockServerResource.lastAuthHeader.get())
                .isEqualTo("Bearer test-token");
    }

    @Test
    void statusDelegatesToSseClient() {
        ProviderStatus status = provider.status();

        assertThat(status).isNotNull();
        assertThat(status).isIn(ProviderStatus.CONNECTED, ProviderStatus.CONNECTING,
                ProviderStatus.DISCONNECTED);
    }
}

package io.casehub.iot.homeassistant;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.iot.homeassistant.TestHttpServerResource.StubbedResponse;
import io.casehub.iot.homeassistant.TestHttpServerResource.TestHttpServer;
import io.casehub.iot.homeassistant.internal.HaServiceCallDto;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(value = TestHttpServerResource.class, restrictToAnnotatedClass = true)
class HomeAssistantRestClientTest {

    private HomeAssistantRestClient restClient;

    private TestHttpServer server() {
        return TestHttpServerResource.INSTANCE;
    }

    @BeforeEach
    void drainStaleRequests() {
        server().drainRequests();
        // Create client programmatically with test server URL
        String serverUrl = "http://localhost:" + server().port();
        this.restClient = RestClientBuilder.newBuilder()
            .baseUri(URI.create(serverUrl))
            .register(new BearerAuthFilter("test-token"))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build(HomeAssistantRestClient.class);
    }

    @Test
    void getStatesReturnsDeviceList() {
        server().enqueue(StubbedResponse.json(200, """
                [
                  {"entity_id":"light.kitchen","state":"on","last_updated":"2026-06-09T10:00:00Z","last_changed":"2026-06-09T09:55:00Z","attributes":{"friendly_name":"Kitchen"}},
                  {"entity_id":"switch.hallway","state":"off","last_updated":"2026-06-09T10:00:00Z","last_changed":"2026-06-09T09:50:00Z","attributes":{}}
                ]
                """));

        var states = restClient.getStates().await().indefinitely();

        assertThat(states).hasSize(2);
        assertThat(states.get(0).entityId()).isEqualTo("light.kitchen");
        assertThat(states.get(0).state()).isEqualTo("on");
        assertThat(states.get(1).entityId()).isEqualTo("switch.hallway");
    }

    @Test
    void callServiceReturnsResponse() {
        server().enqueue(StubbedResponse.json(200, "[]"));

        var body = new HaServiceCallDto("light.kitchen", Map.of("brightness", 200));
        var response = restClient.callService("light", "turn_on", body)
                .await().indefinitely();

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void authorizationHeaderSent() {
        server().enqueue(StubbedResponse.json(200, "[]"));

        restClient.getStates().await().indefinitely();

        var request = server().takeRequest();
        assertThat(request).isNotNull();
        assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer test-token");
    }

    @Test
    void callServiceSendsCorrectPath() {
        server().enqueue(StubbedResponse.json(200, "[]"));

        var body = new HaServiceCallDto("switch.hallway", Map.of());
        restClient.callService("switch", "turn_off", body)
                .await().indefinitely();

        var request = server().takeRequest();
        assertThat(request).isNotNull();
        assertThat(request.path()).isEqualTo("/api/services/switch/turn_off");
        assertThat(request.method()).isEqualTo("POST");
    }
}

package io.casehub.iot.homeassistant;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.homeassistant.internal.HaServiceCallDto;
import io.casehub.iot.homeassistant.internal.ServiceCallSpec;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Home Assistant implementation of the {@link DeviceProvider} SPI.
 *
 * <p>Wires discovery (REST), real-time state updates (WebSocket), and command
 * dispatch (REST service calls) into the common provider interface.
 */
@ApplicationScoped
@LookupIfProperty(name = "casehub.iot.homeassistant.enabled", stringValue = "true")
public class HomeAssistantProvider implements DeviceProvider {

    private static final Logger LOG = Logger.getLogger(HomeAssistantProvider.class);

    @Inject HomeAssistantConfig config;
    @Inject HomeAssistantWebSocketClient wsClient;
    @Inject HomeAssistantEntityMapper mapper;

    private volatile HomeAssistantRestClient restClient;
    private volatile boolean wsConnectAttempted = false;

    @PostConstruct
    void start() {
        // Defer WebSocket connection until first use to avoid startup race with test servers
        // Production use will trigger via first status() or discover() call
    }

    private void ensureWebSocketConnected() {
        if (!wsConnectAttempted) {
            synchronized (this) {
                if (!wsConnectAttempted) {
                    wsConnectAttempted = true;
                    wsClient.connect().subscribe().with(
                        v -> {},
                        e -> LOG.warnf(e, "HA initial connect failed")
                    );
                }
            }
        }
    }

    private HomeAssistantRestClient getRestClient() {
        if (restClient == null) {
            synchronized (this) {
                if (restClient == null) {
                    String resolvedUrl = config.url()
                        .orElseGet(() -> HomeAssistantDiscovery.resolve(config.discoveryTimeoutSeconds()));
                    String token = config.token()
                        .orElseThrow(() -> new IllegalStateException(
                            "casehub.iot.homeassistant.token is required"));

                    this.restClient = RestClientBuilder.newBuilder()
                        .baseUri(URI.create(resolvedUrl))
                        .register(new BearerAuthFilter(token))
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build(HomeAssistantRestClient.class);
                }
            }
        }
        return restClient;
    }

    @Override
    public String providerId() {
        return "homeassistant";
    }

    @Override
    public ProviderStatus status() {
        ensureWebSocketConnected();
        return wsClient.currentStatus();
    }

    @Override
    public Uni<List<DeviceEntity>> discover() {
        ensureWebSocketConnected();
        return getRestClient().getStates().map(mapper::mapAll);
    }

    @Override
    public Uni<CommandResult> dispatch(DeviceCommand command) {
        ServiceCallSpec spec = buildServiceCall(command);
        if (spec == null) {
            return Uni.createFrom().item(CommandResult.FAILED);
        }
        return getRestClient().callService(spec.domain(), spec.service(), spec.body())
            .map(resp -> resp.getStatus() < 300 ? CommandResult.SENT : CommandResult.FAILED)
            .onFailure(WebApplicationException.class).recoverWithItem(CommandResult.FAILED)
            .onFailure(ProcessingException.class).recoverWithItem(CommandResult.FAILED)
            .onFailure(TimeoutException.class).recoverWithItem(CommandResult.TIMEOUT);
    }

    private ServiceCallSpec buildServiceCall(DeviceCommand command) {
        String entityId = command.targetDeviceId();
        String domain = entityId.contains(".") ? entityId.split("\\.")[0] : entityId;
        return switch (command.action()) {
            case DeviceCommand.ACTION_TURN_ON -> new ServiceCallSpec(domain, "turn_on",
                new HaServiceCallDto(entityId, command.parameters()));
            case DeviceCommand.ACTION_TURN_OFF -> new ServiceCallSpec(domain, "turn_off",
                new HaServiceCallDto(entityId, Map.of()));
            case DeviceCommand.ACTION_SET_TEMPERATURE -> new ServiceCallSpec("climate", "set_temperature",
                new HaServiceCallDto(entityId, Map.of("temperature", command.parameters().get("temperature"))));
            case DeviceCommand.ACTION_LOCK -> new ServiceCallSpec("lock", "lock",
                new HaServiceCallDto(entityId, Map.of()));
            case DeviceCommand.ACTION_UNLOCK -> new ServiceCallSpec("lock", "unlock",
                new HaServiceCallDto(entityId, Map.of()));
            case DeviceCommand.ACTION_SET_POSITION -> new ServiceCallSpec("cover", "set_cover_position",
                new HaServiceCallDto(entityId, Map.of("position", command.parameters().get("position"))));
            case DeviceCommand.ACTION_SET_VOLUME -> {
                int raw = ((Number) command.parameters().get("volume")).intValue();
                yield new ServiceCallSpec("media_player", "volume_set",
                    new HaServiceCallDto(entityId, Map.of("volume_level", raw / 100.0)));
            }
            default -> null;
        };
    }
}

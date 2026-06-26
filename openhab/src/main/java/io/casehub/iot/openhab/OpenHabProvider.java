package io.casehub.iot.openhab;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.spi.DeviceProvider;
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
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OpenHAB implementation of the {@link DeviceProvider} SPI.
 *
 * <p>Wires discovery (REST Equipment query), real-time state updates (SSE), and
 * command dispatch (REST item commands) into the common provider interface.
 */
@ApplicationScoped
@LookupIfProperty(name = "casehub.iot.openhab.enabled", stringValue = "true")
public class OpenHabProvider implements DeviceProvider {

    private static final Logger LOG = Logger.getLogger(OpenHabProvider.class);

    @Inject OpenHabConfig config;
    @Inject OpenHabSseClient sseClient;
    @Inject OpenHabEntityMapper mapper;

    private volatile OpenHabRestClient restClient;
    private volatile boolean sseConnectAttempted = false;

    /** Package-private constructor for unit tests (no CDI). */
    OpenHabProvider() {}

    @PostConstruct
    void start() {
        // Defer SSE connection until first use to avoid startup race with test servers
        // Production use will trigger via first status() or discover() call
    }

    private void ensureSseConnected() {
        if (!sseConnectAttempted) {
            synchronized (this) {
                if (!sseConnectAttempted) {
                    sseConnectAttempted = true;
                    String resolvedUrl = config.url()
                        .orElseGet(() -> OpenHabDiscovery.resolve(config.discoveryTimeoutSeconds()));
                    var authFilter = new OpenHabAuthFilter(config.auth());
                    sseClient.init(resolvedUrl, authFilter);
                    sseClient.connect().subscribe().with(
                        v -> {},
                        e -> LOG.warnf(e, "OpenHAB initial connect failed")
                    );
                }
            }
        }
    }

    private OpenHabRestClient getRestClient() {
        if (restClient == null) {
            synchronized (this) {
                if (restClient == null) {
                    String resolvedUrl = config.url()
                        .orElseGet(() -> OpenHabDiscovery.resolve(config.discoveryTimeoutSeconds()));
                    var authFilter = new OpenHabAuthFilter(config.auth());

                    this.restClient = RestClientBuilder.newBuilder()
                        .baseUri(URI.create(resolvedUrl))
                        .register(authFilter)
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build(OpenHabRestClient.class);
                }
            }
        }
        return restClient;
    }

    @Override
    public String providerId() {
        return "openhab";
    }

    @Override
    public ProviderStatus status() {
        ensureSseConnected();
        return sseClient.currentStatus();
    }

    @Override
    public Uni<List<DeviceEntity>> discover() {
        ensureSseConnected();
        return getRestClient().getItems("Equipment", true)
            .map(items -> items.stream()
                .map(i -> mapper.mapEquipment(i, Instant.now()))
                .filter(Objects::nonNull)
                .toList());
    }

    @Override
    public Uni<CommandResult> dispatch(DeviceCommand command) {
        String commandValue = buildCommandValue(command);
        if (commandValue == null) {
            return Uni.createFrom().item(CommandResult.FAILED);
        }

        String targetItem = sseClient.resolveTargetItem(command);
        if (targetItem == null) {
            return Uni.createFrom().item(CommandResult.FAILED);
        }

        return getRestClient().sendCommand(targetItem, commandValue)
            .map(resp -> resp.getStatus() < 300 ? CommandResult.SENT : CommandResult.FAILED)
            .onFailure(WebApplicationException.class).recoverWithItem(CommandResult.FAILED)
            .onFailure(ProcessingException.class).recoverWithItem(CommandResult.FAILED)
            .onFailure(TimeoutException.class).recoverWithItem(CommandResult.TIMEOUT);
    }

    String buildCommandValue(DeviceCommand command) {
        return switch (command.action()) {
            case DeviceCommand.ACTION_TURN_ON -> "ON";
            case DeviceCommand.ACTION_TURN_OFF -> "OFF";
            case DeviceCommand.ACTION_LOCK -> "ON";
            case DeviceCommand.ACTION_UNLOCK -> "OFF";
            case DeviceCommand.ACTION_SET_TEMPERATURE ->
                String.valueOf(command.parameters().get("temperature"));
            case DeviceCommand.ACTION_SET_POSITION -> {
                int position = ((Number) command.parameters().get("position")).intValue();
                yield String.valueOf(100 - position);
            }
            case DeviceCommand.ACTION_SET_VOLUME ->
                String.valueOf(command.parameters().get("volume"));
            default -> null;
        };
    }
}

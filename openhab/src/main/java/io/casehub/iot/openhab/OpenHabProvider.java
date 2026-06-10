package io.casehub.iot.openhab;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.spi.DeviceProvider;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * OpenHAB implementation of the {@link DeviceProvider} SPI.
 *
 * <p>Wires discovery (REST Equipment query), real-time state updates (SSE), and
 * command dispatch (REST item commands) into the common provider interface.
 */
@ApplicationScoped
public class OpenHabProvider implements DeviceProvider {

    private static final Logger LOG = Logger.getLogger(OpenHabProvider.class);

    @Inject @RestClient OpenHabRestClient restClient;
    @Inject OpenHabSseClient sseClient;
    @Inject OpenHabEntityMapper mapper;

    @PostConstruct
    void start() {
        sseClient.connect().subscribe().with(
            v -> {},
            e -> LOG.warnf(e, "OpenHAB initial connect failed")
        );
    }

    @Override
    public String providerId() {
        return "openhab";
    }

    @Override
    public ProviderStatus status() {
        return sseClient.currentStatus();
    }

    @Override
    public Uni<List<DeviceEntity>> discover() {
        return restClient.getItems("Equipment", true)
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

        return restClient.sendCommand(targetItem, commandValue)
            .map(resp -> resp.getStatus() < 300 ? CommandResult.SENT : CommandResult.FAILED)
            .onFailure(WebApplicationException.class).recoverWithItem(CommandResult.FAILED)
            .onFailure(ProcessingException.class).recoverWithItem(CommandResult.FAILED)
            .onFailure(TimeoutException.class).recoverWithItem(CommandResult.TIMEOUT);
    }

    private String buildCommandValue(DeviceCommand command) {
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

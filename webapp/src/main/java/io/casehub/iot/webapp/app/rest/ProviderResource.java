package io.casehub.iot.webapp.app.rest;

import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/providers")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProviderResource {

    @Inject
    Instance<DeviceProvider> providers;

    @Inject
    DeviceRegistry deviceRegistry;

    @Inject
    CurrentPrincipal principal;

    @GET
    @RolesAllowed("iot-viewer")
    public List<ProviderStatusResponse> list() {
        return providers.stream()
                .map(p -> {
                    var status = p.status();
                    var deviceCount = deviceRegistry.findAll().stream()
                            .filter(d -> d.providerId().equals(p.providerId()))
                            .filter(d -> d.tenancyId().equals(principal.tenancyId()))
                            .count();

                    return new ProviderStatusResponse(
                            p.providerId(),
                            status.name(),
                            (int) deviceCount
                    );
                })
                .toList();
    }

    @GET
    @Path("/{providerId}")
    @RolesAllowed("iot-viewer")
    public ProviderStatusResponse get(@PathParam("providerId") String providerId) {
        var provider = providers.stream()
                .filter(p -> p.providerId().equals(providerId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Provider not found: " + providerId));

        var status = provider.status();
        var deviceCount = deviceRegistry.findAll().stream()
                .filter(d -> d.providerId().equals(provider.providerId()))
                .filter(d -> d.tenancyId().equals(principal.tenancyId()))
                .count();

        return new ProviderStatusResponse(
                provider.providerId(),
                status.name(),
                (int) deviceCount
        );
    }

    @POST
    @Path("/refresh")
    @RolesAllowed("iot-operator")
    public RefreshResponse refreshAll() {
        deviceRegistry.refresh().await().indefinitely();
        return new RefreshResponse("Device discovery triggered for all providers");
    }

    @POST
    @Path("/{providerId}/refresh")
    @RolesAllowed("iot-operator")
    public RefreshResponse refresh(@PathParam("providerId") String providerId) {
        try {
            deviceRegistry.refresh(providerId).await().indefinitely();
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(e.getMessage());
        }
        return new RefreshResponse("Device discovery triggered for provider: " + providerId);
    }

    public record ProviderStatusResponse(
            String providerId,
            String status,
            int deviceCount
    ) {
    }

    public record RefreshResponse(String message) {
    }
}

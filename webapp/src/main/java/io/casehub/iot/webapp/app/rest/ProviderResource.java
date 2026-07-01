package io.casehub.iot.webapp.app.rest;

import io.casehub.iot.api.DeviceRegistry;
import io.casehub.iot.api.provider.DeviceProvider;
import io.casehub.platform.api.CurrentPrincipal;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * REST resource for device provider operations.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/providers} — all registered providers with status
 *   <li>{@code GET /api/providers/{providerId}} — single provider detail
 *   <li>{@code POST /api/providers/refresh} — trigger device re-discovery
 * </ul>
 */
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

    /**
     * List all registered providers with status and device count.
     *
     * @return list of provider status records
     */
    @GET
    @RolesAllowed("iot-viewer")
    public List<ProviderStatusResponse> list() {
        return providers.stream()
                .map(p -> {
                    var status = p.status().await().indefinitely();
                    var deviceCount = deviceRegistry.findAll().stream()
                            .filter(d -> d.providerId().equals(p.providerId()))
                            .filter(d -> filterByTenancy(d.tenancyId()))
                            .count();

                    return new ProviderStatusResponse(
                            p.providerId(),
                            status.name(),
                            (int) deviceCount
                    );
                })
                .toList();
    }

    /**
     * Get single provider detail.
     *
     * @param providerId provider ID
     * @return provider status with device count
     */
    @GET
    @Path("/{providerId}")
    @RolesAllowed("iot-viewer")
    public ProviderStatusResponse get(@PathParam("providerId") String providerId) {
        var provider = providers.stream()
                .filter(p -> p.providerId().equals(providerId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Provider not found: " + providerId));

        var status = provider.status().await().indefinitely();
        var deviceCount = deviceRegistry.findAll().stream()
                .filter(d -> d.providerId().equals(provider.providerId()))
                .filter(d -> filterByTenancy(d.tenancyId()))
                .count();

        return new ProviderStatusResponse(
                provider.providerId(),
                status.name(),
                (int) deviceCount
        );
    }

    /**
     * Trigger device re-discovery across all providers.
     *
     * <p>Calls {@link DeviceRegistry#refresh()} which rediscovers all providers.
     * Per-provider refresh is not supported in the current SPI (see iot#43).
     */
    @POST
    @Path("/refresh")
    @RolesAllowed("iot-operator")
    public RefreshResponse refresh() {
        deviceRegistry.refresh().await().indefinitely();
        return new RefreshResponse("Device discovery triggered for all providers");
    }

    private boolean filterByTenancy(String deviceTenancyId) {
        return deviceTenancyId == null || deviceTenancyId.equals(principal.tenancyId());
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

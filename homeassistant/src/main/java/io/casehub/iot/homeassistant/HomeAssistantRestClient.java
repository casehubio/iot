package io.casehub.iot.homeassistant;

import io.casehub.iot.homeassistant.internal.HaServiceCallDto;
import io.casehub.iot.homeassistant.internal.HaStateDto;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import java.util.List;

/**
 * MicroProfile REST Client for the Home Assistant REST API.
 *
 * <p>Covers two endpoints:
 * <ul>
 *   <li>{@code GET /api/states} — full device discovery</li>
 *   <li>{@code POST /api/services/{domain}/{service}} — command dispatch</li>
 * </ul>
 *
 * <p>The {@code configKey} ties into Quarkus config:
 * {@code quarkus.rest-client."homeassistant".url} sets the base URL.
 * The bearer token is resolved from {@code casehub.iot.homeassistant.token}
 * via {@link #lookupToken()}.
 */
@RegisterRestClient(configKey = "homeassistant")
@ClientHeaderParam(name = "Authorization", value = "{lookupToken}")
public interface HomeAssistantRestClient {

    @GET
    @Path("/api/states")
    Uni<List<HaStateDto>> getStates();

    @POST
    @Path("/api/services/{domain}/{service}")
    Uni<Response> callService(@PathParam("domain") String domain,
                              @PathParam("service") String service,
                              HaServiceCallDto body);

    /**
     * Resolves the bearer token from MicroProfile Config.
     * Uses {@link ConfigProvider} because CDI injection is unavailable
     * in interface default methods.
     */
    default String lookupToken() {
        return "Bearer " + ConfigProvider.getConfig()
                .getValue("casehub.iot.homeassistant.token", String.class);
    }
}

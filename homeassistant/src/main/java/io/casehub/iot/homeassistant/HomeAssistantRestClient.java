package io.casehub.iot.homeassistant;

import io.casehub.iot.homeassistant.internal.HaServiceCallDto;
import io.casehub.iot.homeassistant.internal.HaStateDto;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
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
 * <p>Created programmatically via {@code RestClientBuilder} in
 * {@link HomeAssistantProvider#start()} with runtime-resolved URL and auth.
 */
public interface HomeAssistantRestClient {

    @GET
    @Path("/api/states")
    Uni<List<HaStateDto>> getStates();

    @POST
    @Path("/api/services/{domain}/{service}")
    Uni<Response> callService(@PathParam("domain") String domain,
                              @PathParam("service") String service,
                              HaServiceCallDto body);
}

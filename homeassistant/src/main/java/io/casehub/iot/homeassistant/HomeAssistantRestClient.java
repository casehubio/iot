package io.casehub.iot.homeassistant;

import io.casehub.iot.homeassistant.internal.HaServiceCallDto;
import io.casehub.iot.homeassistant.internal.HaStateDto;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import java.util.List;

public interface HomeAssistantRestClient {

    @GET
    @Path("/api/states")
    List<HaStateDto> getStates();

    @POST
    @Path("/api/services/{domain}/{service}")
    Response callService(@PathParam("domain") String domain,
                         @PathParam("service") String service,
                         HaServiceCallDto body);
}

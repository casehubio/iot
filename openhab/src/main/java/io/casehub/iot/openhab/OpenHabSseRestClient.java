package io.casehub.iot.openhab;

import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.client.SseEvent;

public interface OpenHabSseRestClient {

    @GET @Path("/rest/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    Multi<SseEvent<String>> subscribeEvents(
        @QueryParam("topics") String topics);
}

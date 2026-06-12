package io.casehub.iot.openhab;

import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.client.SseEvent;

@RegisterRestClient(configKey = "openhab-sse")
@RegisterClientHeaders(OpenHabAuthHeadersFactory.class)
public interface OpenHabSseRestClient {

    @GET @Path("/rest/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    Multi<SseEvent<String>> subscribeEvents(
        @QueryParam("topics") String topics);
}

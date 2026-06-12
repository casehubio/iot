package io.casehub.iot.openhab;

import io.casehub.iot.openhab.internal.OpenHabItemDto;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import java.util.List;

@RegisterRestClient(configKey = "openhab")
@RegisterClientHeaders(OpenHabAuthHeadersFactory.class)
public interface OpenHabRestClient {

    @GET @Path("/rest/items")
    Uni<List<OpenHabItemDto>> getItems(
        @QueryParam("tags") String tags,
        @QueryParam("recursive") boolean recursive);

    @POST @Path("/rest/items/{itemName}")
    @Consumes(MediaType.TEXT_PLAIN)
    Uni<Response> sendCommand(
        @PathParam("itemName") String itemName,
        String command);
}

package io.casehub.iot.openhab;

import io.casehub.iot.openhab.internal.OpenHabItemDto;
import io.casehub.iot.openhab.internal.OpenHabThingDto;
import io.casehub.iot.openhab.internal.OpenHabThingTypeDto;
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

    @GET @Path("/rest/things")
    Uni<List<OpenHabThingDto>> getThings();

    @GET @Path("/rest/items")
    Uni<List<OpenHabItemDto>> getAllItems();

    @GET @Path("/rest/thing-types")
    Uni<List<OpenHabThingTypeDto>> getThingTypes();

    @POST @Path("/rest/items/{itemName}")
    @Consumes(MediaType.TEXT_PLAIN)
    Uni<Response> sendCommand(
        @PathParam("itemName") String itemName,
        String command);
}

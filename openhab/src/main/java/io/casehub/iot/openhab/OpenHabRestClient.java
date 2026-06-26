package io.casehub.iot.openhab;

import io.casehub.iot.openhab.internal.OpenHabItemDto;
import io.casehub.iot.openhab.internal.OpenHabThingDto;
import io.casehub.iot.openhab.internal.OpenHabThingTypeDto;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

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

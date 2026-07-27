package io.casehub.iot.openhab;

import io.casehub.iot.openhab.internal.OpenHabItemDto;
import io.casehub.iot.openhab.internal.OpenHabThingDto;
import io.casehub.iot.openhab.internal.OpenHabThingTypeDto;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

public interface OpenHabRestClient {

    @GET
    @Path("/rest/items")
    List<OpenHabItemDto> getItems(
            @QueryParam("tags") String tags,
            @QueryParam("recursive") boolean recursive);

    @GET
    @Path("/rest/things")
    List<OpenHabThingDto> getThings();

    @GET
    @Path("/rest/items")
    List<OpenHabItemDto> getAllItems();

    @GET
    @Path("/rest/thing-types")
    List<OpenHabThingTypeDto> getThingTypes();

    @POST
    @Path("/rest/items/{itemName}")
    @Consumes(MediaType.TEXT_PLAIN)
    Response sendCommand(
            @PathParam("itemName") String itemName,
            String command);
}

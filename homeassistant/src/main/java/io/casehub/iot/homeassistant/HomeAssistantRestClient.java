package io.casehub.iot.homeassistant;

import io.casehub.iot.homeassistant.internal.HaAreaDto;
import io.casehub.iot.homeassistant.internal.HaDeviceRegistryDto;
import io.casehub.iot.homeassistant.internal.HaEntityRegistryDto;
import io.casehub.iot.homeassistant.internal.HaFloorDto;
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

    @GET
    @Path("/api/config/area_registry/list")
    List<HaAreaDto> getAreas();

    @GET
    @Path("/api/config/floor_registry/list")
    List<HaFloorDto> getFloors();

    @GET
    @Path("/api/config/entity_registry/list")
    List<HaEntityRegistryDto> getEntityRegistry();

    @GET
    @Path("/api/config/device_registry/list")
    List<HaDeviceRegistryDto> getDeviceRegistry();


    @POST
    @Path("/api/services/{domain}/{service}")
    Response callService(@PathParam("domain") String domain,
                         @PathParam("service") String service,
                         HaServiceCallDto body);
}

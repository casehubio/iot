package io.casehub.iot.webapp.spi;

import io.casehub.iot.webapp.NotImplementedException;
import io.casehub.iot.webapp.rest.DismissRequest;
import io.casehub.iot.webapp.rest.SituationDefinitionRequest;
import io.casehub.iot.webapp.rest.SituationSuggestionsResponse;
import io.casehub.iot.webapp.view.ActiveSituationView;
import io.casehub.iot.webapp.view.SituationDefinitionView;
import io.casehub.platform.api.mcp.ContextParam;
import io.casehub.platform.api.mcp.HttpMethod;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestMethod;
import io.casehub.platform.api.mcp.RestPath;
import io.casehub.platform.api.mcp.RestStatus;

import java.util.List;

@McpDomain(value = "iot/situations", basePath = "/api/situations")
public interface IoTSituationApi {

    @PlatformQuery("List situation definitions")
    @RestPath("/definitions")
    List<SituationDefinitionView> listDefinitions(@ContextParam("tenancyId") String tenancyId);

    @PlatformMutation("Create a situation definition")
    @RestPath("/definitions")
    @RestStatus(201)
    SituationDefinitionView createDefinition(SituationDefinitionRequest request,
                                              @ContextParam("tenancyId") String tenancyId);

    @PlatformMutation("Update a situation definition")
    @RestMethod(HttpMethod.PUT)
    @RestPath("/definitions/{situationId}")
    SituationDefinitionView updateDefinition(@PathParam String situationId,
                                              SituationDefinitionRequest request,
                                              @ContextParam("tenancyId") String tenancyId);

    @PlatformMutation("Delete a situation definition")
    @RestMethod(HttpMethod.DELETE)
    @RestPath("/definitions/{situationId}")
    void deleteDefinition(@PathParam String situationId,
                          @ContextParam("tenancyId") String tenancyId);

    @PlatformQuery("List active situations")
    @RestPath("/active")
    default List<ActiveSituationView> listActive(@ContextParam("tenancyId") String tenancyId) {
        throw new NotImplementedException("listActive");
    }

    @PlatformQuery("Get resolution suggestions for a situation")
    @RestPath("/{situationId}/suggestions")
    SituationSuggestionsResponse getSuggestions(@PathParam String situationId,
                                                @ContextParam("tenancyId") String tenancyId);

    @PlatformMutation("Dismiss an active situation")
    @RestPath("/active/{correlationKey}/dismiss")
    void dismissSituation(@PathParam String correlationKey,
                          DismissRequest request,
                          @ContextParam("tenancyId") String tenancyId);
}

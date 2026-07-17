package io.casehub.iot.webapp.rest;

import io.casehub.iot.webapp.cbr.ResolutionSuggestion;

import java.util.List;
import java.util.UUID;

public record SituationSuggestionsResponse(
        String situationId,
        List<CaseSuggestions> cases
) {
    public record CaseSuggestions(
            UUID caseId,
            String caseType,
            List<ResolutionSuggestion> suggestions
    ) {}
}

package io.casehub.iot.webapp.view;

import io.casehub.iot.webapp.cbr.ResolutionSuggestion;
import java.util.List;
import java.util.UUID;

public record SuggestionView(
    UUID caseId,
    String caseType,
    int suggestionCount,
    List<ResolutionSuggestion> suggestions) {}

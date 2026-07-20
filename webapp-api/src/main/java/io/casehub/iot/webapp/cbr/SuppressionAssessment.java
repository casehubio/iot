package io.casehub.iot.webapp.cbr;

public record SuppressionAssessment(
        SuppressionTier tier,
        double dismissalRate,
        int totalCases,
        int dismissedCases,
        double averageSimilarity) {}

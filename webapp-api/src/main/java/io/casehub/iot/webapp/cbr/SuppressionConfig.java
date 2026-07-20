package io.casehub.iot.webapp.cbr;

public record SuppressionConfig(
        double fullThreshold,
        double demotionThreshold,
        int minCases,
        int topK,
        double minSimilarity) {

    public SuppressionConfig {
        if (fullThreshold < demotionThreshold)
            throw new IllegalArgumentException(
                    "fullThreshold must be >= demotionThreshold, got: "
                    + fullThreshold + " < " + demotionThreshold);
        if (minCases < 1)
            throw new IllegalArgumentException("minCases must be >= 1, got: " + minCases);
    }

    public static SuppressionConfig defaults() {
        return new SuppressionConfig(0.9, 0.7, 5, 20, 0.5);
    }
}

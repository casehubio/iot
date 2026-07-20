package io.casehub.iot.webapp.app.cbr;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.iot.suppression")
public interface SuppressionConfigMapping {

    @WithDefault("0.9")
    double fullThreshold();

    @WithDefault("0.7")
    double demotionThreshold();

    @WithDefault("5")
    int minCases();

    @WithDefault("20")
    int topK();

    @WithDefault("0.5")
    double minSimilarity();
}

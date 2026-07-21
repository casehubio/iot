package io.casehub.iot.webapp.app.triage;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "casehub.iot.triage")
public interface IoTTriageConfig {

    @WithName("ai-resolution.min-similarity")
    @WithDefault("0.85")
    double aiMinSimilarity();

    @WithName("ai-resolution.min-consistency")
    @WithDefault("0.80")
    double aiMinConsistency();
}

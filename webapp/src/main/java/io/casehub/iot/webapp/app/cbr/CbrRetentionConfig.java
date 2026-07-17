package io.casehub.iot.webapp.app.cbr;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Optional;

@ConfigMapping(prefix = "casehub.iot.webapp.cbr.retention")
public interface CbrRetentionConfig {

    Optional<Integer> maxAgeDays();

    Optional<Integer> maxCasesPerType();

    @WithDefault("PT1H")
    Duration purgeInterval();
}

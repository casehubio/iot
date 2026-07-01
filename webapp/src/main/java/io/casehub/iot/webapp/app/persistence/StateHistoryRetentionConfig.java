package io.casehub.iot.webapp.app.persistence;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Optional;

@ConfigMapping(prefix = "casehub.iot.webapp.state-history")
public interface StateHistoryRetentionConfig {

    Optional<Integer> retentionDays();

    @WithDefault("PT1H")
    Duration purgeInterval();
}

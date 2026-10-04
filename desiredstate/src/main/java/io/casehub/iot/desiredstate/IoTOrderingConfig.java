package io.casehub.iot.desiredstate;

import io.smallrye.config.ConfigMapping;
import java.util.Optional;

@ConfigMapping(prefix = "casehub.iot.ordering")
public interface IoTOrderingConfig {
    Optional<String> path();
}

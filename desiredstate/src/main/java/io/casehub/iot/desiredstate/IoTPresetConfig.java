package io.casehub.iot.desiredstate;

import io.smallrye.config.ConfigMapping;
import java.util.Optional;

@ConfigMapping(prefix = "casehub.iot.presets")
public interface IoTPresetConfig {
    Optional<String> path();
}

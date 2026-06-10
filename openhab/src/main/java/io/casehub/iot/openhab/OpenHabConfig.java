package io.casehub.iot.openhab;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.iot.openhab")
public interface OpenHabConfig {
    String url();
    String token();
    String tenancyId();
    @WithDefault("5")   int reconnectBaseSeconds();
    @WithDefault("300") int reconnectMaxSeconds();
    @WithDefault("50")  int coalesceWindowMs();
}

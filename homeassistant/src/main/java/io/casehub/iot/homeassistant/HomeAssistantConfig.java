package io.casehub.iot.homeassistant;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.iot.homeassistant")
public interface HomeAssistantConfig {
    String url();
    String token();
    String tenancyId();
    @WithDefault("5")   int reconnectBaseSeconds();
    @WithDefault("300") int reconnectMaxSeconds();
    @WithDefault("30")  int pingIntervalSeconds();
    @WithDefault("10")  int pongTimeoutSeconds();
}

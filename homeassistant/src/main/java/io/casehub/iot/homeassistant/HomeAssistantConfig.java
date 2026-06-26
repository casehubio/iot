package io.casehub.iot.homeassistant;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

@ConfigMapping(prefix = "casehub.iot.homeassistant")
public interface HomeAssistantConfig {
    @WithDefault("false") boolean enabled();
    Optional<String> url();
    Optional<String> token();
    @WithDefault("5")   int reconnectBaseSeconds();
    @WithDefault("300") int reconnectMaxSeconds();
    @WithDefault("30")  int pingIntervalSeconds();
    @WithDefault("10")  int pongTimeoutSeconds();
    @WithDefault("5")   int discoveryTimeoutSeconds();
}

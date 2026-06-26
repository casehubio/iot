package io.casehub.iot.openhab;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

@ConfigMapping(prefix = "casehub.iot.openhab")
public interface OpenHabConfig {
    @WithDefault("false") boolean enabled();
    Optional<String> url();
    Auth auth();
    @WithDefault("5")   int reconnectBaseSeconds();
    @WithDefault("300") int reconnectMaxSeconds();
    @WithDefault("50")  int coalesceWindowMs();
    @WithDefault("true") boolean thingDiscoveryEnabled();
    @WithDefault("10")  int discoveryTimeoutSeconds();

    interface Auth {
        Optional<Bearer> bearer();
        Optional<Basic> basic();

        interface Bearer { String token(); }
        interface Basic { String username(); String password(); }
    }
}

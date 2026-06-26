package io.casehub.iot.bridge.agent;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.iot.bridge")
public interface BridgeAgentConfig {

    String cloudEndpoint();

    String token();

    @WithDefault("5")
    int reconnectBaseSeconds();

    @WithDefault("300")
    int reconnectMaxSeconds();

    @WithDefault("30")
    int heartbeatIntervalSeconds();

    EventStore eventStore();

    interface EventStore {
        @WithDefault("10000")
        int maxSize();

        @WithDefault("data/bridge-events")
        String directory();
    }
}

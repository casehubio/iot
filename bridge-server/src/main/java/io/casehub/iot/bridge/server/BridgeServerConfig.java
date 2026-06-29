package io.casehub.iot.bridge.server;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for the bridge server runtime.
 */
@ConfigMapping(prefix = "casehub.iot.bridge-server")
public interface BridgeServerConfig {

    /**
     * Maximum time in seconds to wait for a command response from the agent
     * before timing out.
     */
    @WithDefault("30")
    int commandTimeoutSeconds();
}

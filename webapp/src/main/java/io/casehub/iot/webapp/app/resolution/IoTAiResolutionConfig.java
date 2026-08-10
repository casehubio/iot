package io.casehub.iot.webapp.app.resolution;

import io.casehub.api.model.ai.ModelType;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.time.Duration;

@ConfigMapping(prefix = "casehub.iot.ai-resolution")
public interface IoTAiResolutionConfig {

    @WithDefault("true")
    boolean enabled();

    @WithName("poll-interval")
    @WithDefault("10s")
    Duration pollInterval();

    @WithName("timeout-seconds")
    @WithDefault("300")
    int timeoutSeconds();

    @WithName("model-type")
    @WithDefault("ANTHROPIC")
    ModelType modelType();

    @WithName("agent-id")
    @WithDefault("iot-ai-agent")
    String agentId();

    @WithName("max-concurrent-llm-calls")
    @WithDefault("3")
    int maxConcurrentLlmCalls();

    @WithName("conversation-mode")
    @WithDefault("auto")
    String conversationMode();

    @WithName("max-conversation-turns")
    @WithDefault("5")
    int maxConversationTurns();

    @WithName("max-concurrent-sessions")
    @WithDefault("1")
    int maxConcurrentSessions();

}

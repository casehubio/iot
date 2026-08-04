package io.casehub.iot.mcp;

import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpIdentityContextTest {

    private static final String CONFIG_TENANCY = "config-tenant-id";

    @Test
    void tenancyIdReturnsPrincipalTenancy() {
        var principal = stubPrincipal("tenant-from-principal", "user-123");
        var ctx = withPrincipal(principal);
        assertThat(ctx.tenancyId()).isEqualTo("tenant-from-principal");
    }

    @Test
    void tenancyIdFallsBackToConfig() {
        var ctx = withoutPrincipal();
        assertThat(ctx.tenancyId()).isEqualTo(CONFIG_TENANCY);
    }

    @Test
    void actorIdReturnsPrincipalActor() {
        var principal = stubPrincipal("some-tenant", "user-456");
        var ctx = withPrincipal(principal);
        assertThat(ctx.actorId()).isEqualTo("user-456");
    }

    @Test
    void actorIdFallsBackToMcpAgent() {
        var ctx = withoutPrincipal();
        assertThat(ctx.actorId()).isEqualTo("mcp-agent");
    }

    @SuppressWarnings("unchecked")
    private McpIdentityContext withPrincipal(CurrentPrincipal principal) {
        Instance<CurrentPrincipal> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(principal);
        return new McpIdentityContext(instance, CONFIG_TENANCY) {
            @Override
            boolean isPrincipalAvailable() {
                return true;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private McpIdentityContext withoutPrincipal() {
        Instance<CurrentPrincipal> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        return new McpIdentityContext(instance, CONFIG_TENANCY);
    }

    private CurrentPrincipal stubPrincipal(String tenancyId, String actorId) {
        return new CurrentPrincipal() {
            @Override public String actorId() { return actorId; }
            @Override public Set<String> groups() { return Set.of(); }
            @Override public String tenancyId() { return tenancyId; }
            @Override public boolean isCrossTenantAdmin() { return false; }
        };
    }
}

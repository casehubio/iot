package io.casehub.iot.webapp.app.mcp;

import io.casehub.iot.mcp.McpIdentityContext;
import io.casehub.iot.webapp.app.WebappPostgresTestResource;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.arc.Arc;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(WebappPostgresTestResource.class)
class McpIdentityContextIntegrationTest {

    @Inject
    McpIdentityContext identityContext;

    @Inject
    CurrentPrincipal currentPrincipal;

    @Test
    void withRequestContext_usesPrincipalTenancy() {
        assertThat(Arc.container().requestContext().isActive()).isTrue();
        assertThat(identityContext.tenancyId()).isEqualTo(currentPrincipal.tenancyId());
    }

    @Test
    void withRequestContext_usesPrincipalActorId() {
        assertThat(Arc.container().requestContext().isActive()).isTrue();
        assertThat(identityContext.actorId()).isEqualTo(currentPrincipal.actorId());
    }

    @Test
    void withoutRequestContext_fallsBackToConfigTenancy() {
        var requestContext = Arc.container().requestContext();
        requestContext.deactivate();
        try {
            assertThat(identityContext.tenancyId()).isEqualTo("test-tenant");
        } finally {
            requestContext.activate();
        }
    }

    @Test
    void withoutRequestContext_fallsBackToMcpAgentActorId() {
        var requestContext = Arc.container().requestContext();
        requestContext.deactivate();
        try {
            assertThat(identityContext.actorId()).isEqualTo("mcp-agent");
        } finally {
            requestContext.activate();
        }
    }
}

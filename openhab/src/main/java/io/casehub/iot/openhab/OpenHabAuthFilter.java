package io.casehub.iot.openhab;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

class OpenHabAuthFilter implements ClientRequestFilter {

    private final String authHeader;

    OpenHabAuthFilter(OpenHabConfig.Auth auth) {
        boolean hasBearer = auth.bearer().isPresent();
        boolean hasBasic = auth.basic().isPresent();
        if (hasBearer && hasBasic) {
            throw new IllegalStateException(
                "Configure either casehub.iot.openhab.auth.bearer or .auth.basic, not both");
        }
        if (hasBasic) {
            var basic = auth.basic().get();
            authHeader = "Basic " + Base64.getEncoder().encodeToString(
                (basic.username() + ":" + basic.password()).getBytes(StandardCharsets.UTF_8));
        } else if (hasBearer) {
            authHeader = "Bearer " + auth.bearer().get().token();
        } else {
            authHeader = null;
        }
    }

    @Override
    public void filter(ClientRequestContext ctx) {
        if (authHeader != null) {
            ctx.getHeaders().putSingle("Authorization", authHeader);
        }
    }
}

package io.casehub.iot.homeassistant;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;

class BearerAuthFilter implements ClientRequestFilter {

    private final String authHeader;

    BearerAuthFilter(String token) {
        this.authHeader = "Bearer " + token;
    }

    @Override
    public void filter(ClientRequestContext ctx) {
        ctx.getHeaders().putSingle("Authorization", authHeader);
    }
}

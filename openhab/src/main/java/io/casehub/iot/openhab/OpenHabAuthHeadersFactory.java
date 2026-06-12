package io.casehub.iot.openhab;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@ApplicationScoped
public class OpenHabAuthHeadersFactory implements ClientHeadersFactory {

    private final OpenHabConfig config;
    private String authHeader;

    @Inject
    OpenHabAuthHeadersFactory(OpenHabConfig config) {
        this.config = config;
    }

    @PostConstruct
    void resolve() {
        var auth = config.auth();
        boolean hasBearer = auth.bearer().isPresent();
        boolean hasBasic = auth.basic().isPresent();

        if (hasBearer && hasBasic) {
            throw new IllegalStateException(
                    "Configure either casehub.iot.openhab.auth.bearer or casehub.iot.openhab.auth.basic, not both");
        }

        if (hasBasic) {
            var basic = auth.basic().get();
            authHeader = "Basic " + Base64.getEncoder().encodeToString(
                    (basic.username() + ":" + basic.password()).getBytes(StandardCharsets.UTF_8));
        } else if (hasBearer) {
            authHeader = "Bearer " + auth.bearer().get().token();
        }
    }

    @Override
    public MultivaluedMap<String, String> update(
            MultivaluedMap<String, String> incomingHeaders,
            MultivaluedMap<String, String> clientOutgoingHeaders) {
        MultivaluedMap<String, String> result = new MultivaluedHashMap<>(clientOutgoingHeaders);
        if (authHeader != null) {
            result.putSingle("Authorization", authHeader);
        }
        return result;
    }
}

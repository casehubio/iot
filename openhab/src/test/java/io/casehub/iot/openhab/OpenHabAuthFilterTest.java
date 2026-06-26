package io.casehub.iot.openhab;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenHabAuthFilterTest {

    @Test
    void bearerTokenAddsAuthorizationHeader() throws Exception {
        var filter = buildFilter(
                "casehub.iot.openhab.auth.bearer.token", "my-token");

        var ctx = new TestRequestContext();
        filter.filter(ctx);
        assertThat(ctx.getHeaders().getFirst("Authorization"))
            .isEqualTo("Bearer my-token");
    }

    @Test
    void basicAuthAddsBase64Header() throws Exception {
        var filter = buildFilter(
                "casehub.iot.openhab.auth.basic.username", "user",
                "casehub.iot.openhab.auth.basic.password", "pass");

        var ctx = new TestRequestContext();
        filter.filter(ctx);

        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "user:pass".getBytes(StandardCharsets.UTF_8));
        assertThat(ctx.getHeaders().getFirst("Authorization")).isEqualTo(expected);
    }

    @Test
    void bothBearerAndBasicThrows() {
        assertThatThrownBy(() -> buildFilter(
                "casehub.iot.openhab.auth.bearer.token", "my-token",
                "casehub.iot.openhab.auth.basic.username", "admin",
                "casehub.iot.openhab.auth.basic.password", "secret"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noAuthSetsNoHeader() throws Exception {
        var filter = buildFilter();

        var ctx = new TestRequestContext();
        filter.filter(ctx);
        assertThat(ctx.getHeaders().containsKey("Authorization")).isFalse();
    }

    private static OpenHabAuthFilter buildFilter(String... keyValues) {
        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder()
                .withMapping(OpenHabConfig.class)
                .withDefaultValue("casehub.iot.openhab.url", "http://localhost:8080")
                .withDefaultValue("casehub.iot.tenancy-id", "test");
        for (int i = 0; i < keyValues.length; i += 2) {
            builder.withDefaultValue(keyValues[i], keyValues[i + 1]);
        }
        SmallRyeConfig config = builder.build();
        OpenHabConfig ohConfig = config.getConfigMapping(OpenHabConfig.class);

        return new OpenHabAuthFilter(ohConfig.auth());
    }

    private static class TestRequestContext implements ClientRequestContext {
        private final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        @Override
        public MultivaluedMap<String, Object> getHeaders() {
            return headers;
        }

        // All other methods are unused by the filter
        @Override public Object getProperty(String name) { return null; }
        @Override public java.util.Collection<String> getPropertyNames() { return null; }
        @Override public void setProperty(String name, Object object) {}
        @Override public void removeProperty(String name) {}
        @Override public java.net.URI getUri() { return null; }
        @Override public void setUri(java.net.URI uri) {}
        @Override public String getMethod() { return null; }
        @Override public void setMethod(String method) {}
        @Override public MultivaluedMap<String, String> getStringHeaders() { return null; }
        @Override public String getHeaderString(String name) { return null; }
        @Override public java.util.Date getDate() { return null; }
        @Override public java.util.Locale getLanguage() { return null; }
        @Override public jakarta.ws.rs.core.MediaType getMediaType() { return null; }
        @Override public java.util.List<jakarta.ws.rs.core.MediaType> getAcceptableMediaTypes() { return null; }
        @Override public java.util.List<java.util.Locale> getAcceptableLanguages() { return null; }
        @Override public java.util.Map<String, jakarta.ws.rs.core.Cookie> getCookies() { return null; }
        @Override public boolean hasEntity() { return false; }
        @Override public Object getEntity() { return null; }
        @Override public Class<?> getEntityClass() { return null; }
        @Override public java.lang.reflect.Type getEntityType() { return null; }
        @Override public void setEntity(Object entity) {}
        @Override public void setEntity(Object entity, java.lang.annotation.Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {}
        @Override public java.lang.annotation.Annotation[] getEntityAnnotations() { return null; }
        @Override public java.io.OutputStream getEntityStream() { return null; }
        @Override public void setEntityStream(java.io.OutputStream outputStream) {}
        @Override public jakarta.ws.rs.client.Client getClient() { return null; }
        @Override public jakarta.ws.rs.core.Configuration getConfiguration() { return null; }
        @Override public void abortWith(jakarta.ws.rs.core.Response response) {}
    }
}


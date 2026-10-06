package io.casehub.iot.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.yaml.jackson.YamlMappers;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class DeviceFixtureLoader {

    private static final ObjectMapper YAML_MAPPER = YamlMappers.create();

    private final DeviceTypeRegistry registry;

    public DeviceFixtureLoader(DeviceTypeRegistry registry) {
        this.registry = registry;
    }

    public static List<DeviceEntity> load(String classpathResource) {
        return new DeviceFixtureLoader(DeviceTypeRegistry.discover())
            .loadResource(classpathResource);
    }

    public List<DeviceEntity> loadResource(String classpathResource) {
        final InputStream resolved = resolveResource(classpathResource);
        try (resolved) {
            return loadStream(resolved);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                "Failed to close resource: " + classpathResource, e);
        }
    }

    private static InputStream resolveResource(String classpathResource) {
        InputStream stream = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream(classpathResource);
        if (stream == null) {
            stream = DeviceFixtureLoader.class.getClassLoader()
                .getResourceAsStream(classpathResource);
        }
        if (stream == null) {
            throw new IllegalArgumentException(
                "Resource not found: " + classpathResource);
        }
        return stream;
    }

    public List<DeviceEntity> loadStream(InputStream yaml) {
        try {
            JsonNode root = YAML_MAPPER.readTree(yaml);
            DeviceFixtureDefaults defaults = parseDefaults(root);
            JsonNode devicesNode = root.get("devices");
            if (devicesNode == null || !devicesNode.isArray()) {
                return List.of();
            }
            List<DeviceEntity> devices = new ArrayList<>();
            for (JsonNode deviceNode : devicesNode) {
                if (deviceNode.has("deviceClass")) {
                    throw new IllegalArgumentException(
                        "deviceClass is inferred from type; do not set explicitly"
                        + " (device: " + deviceNode.path("deviceId").asText("?") + ")");
                }
                if (!deviceNode.has("type")) {
                    throw new IllegalArgumentException(
                        "Missing required field 'type' (device: "
                        + deviceNode.path("deviceId").asText("?") + ")");
                }
                String typeName = deviceNode.get("type").asText();
                DeviceTypeHandler handler = registry.handlerFor(typeName);
                devices.add(handler.fromYaml(deviceNode, defaults));
            }
            return List.copyOf(devices);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse YAML fixture", e);
        }
    }

    private DeviceFixtureDefaults parseDefaults(JsonNode root) {
        JsonNode defaultsNode = root.get("defaults");
        if (defaultsNode == null) {
            return DeviceFixtureDefaults.DEFAULT;
        }
        String tenancyId = defaultsNode.has("tenancyId")
            ? defaultsNode.get("tenancyId").asText() : Fixtures.DEFAULT_TENANT;
        Instant lastUpdated = defaultsNode.has("lastUpdated")
            ? Instant.parse(defaultsNode.get("lastUpdated").asText()) : Fixtures.EPOCH;
        boolean available = defaultsNode.has("available")
            ? defaultsNode.get("available").asBoolean() : true;
        return new DeviceFixtureDefaults(tenancyId, lastUpdated, available);
    }
}

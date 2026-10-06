package io.casehub.iot.desiredstate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.yaml.jackson.YamlMappers;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
public class IoTGoalLoader {

    private final ObjectMapper yamlMapper = YamlMappers.create()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public IoTGoals load(String path) {
        try (InputStream is = resolveStream(path)) {
            return yamlMapper.readValue(is, IoTGoals.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load IoT topology from " + path, e);
        }
    }

    public IoTGoals loadFromNode(JsonNode node) {
        return yamlMapper.convertValue(node, IoTGoals.class);
    }


    public IoTGoals loadDirectory(String directoryPath) {
        Path dir = Path.of(directoryPath);
        List<IoTGoals> fragments = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".yaml") || name.endsWith(".yml");
                })
                .sorted()
                .forEach(p -> fragments.add(load(p.toString())));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list directory " + directoryPath, e);
        }
        if (fragments.isEmpty()) {
            throw new IllegalArgumentException("No YAML files found in " + directoryPath);
        }
        return merge(fragments.toArray(IoTGoals[]::new));
    }

    public static IoTGoals merge(IoTGoals... fragments) {
        if (fragments.length == 0) {
            throw new IllegalArgumentException("Cannot merge zero fragments");
        }
        var seen = new HashSet<String>();
        var merged = new ArrayList<IoTDeviceGoal>();
        var allOrdering = new java.util.LinkedHashSet<IoTOrderingEntry>();
        String tenancyId = fragments[0].tenancyId();
        for (IoTGoals fragment : fragments) {
            if (!fragment.tenancyId().equals(tenancyId)) {
                throw new IllegalArgumentException(
                    "Inconsistent tenancyId in merge: expected " + tenancyId + ", found " + fragment.tenancyId());
            }
            for (IoTDeviceGoal device : fragment.devices()) {
                if (!seen.add(device.deviceId())) {
                    throw new IllegalArgumentException(
                        "Duplicate deviceId in merge: " + device.deviceId());
                }
                merged.add(device);
            }
            allOrdering.addAll(fragment.ordering());
        }
        return new IoTGoals(tenancyId, merged, List.copyOf(allOrdering));
    }

    public static IoTGoals mergeGoals(IoTGoals... fragments) {
        if (fragments.length == 0) {
            throw new IllegalArgumentException("Cannot merge zero fragments");
        }
        String tenancyId = fragments[0].tenancyId();
        var    merged    = new java.util.LinkedHashMap<String, IoTDeviceGoal>();
        var    allOrdering = new java.util.LinkedHashSet<IoTOrderingEntry>();
        for (IoTGoals fragment : fragments) {
            if (!fragment.tenancyId().equals(tenancyId)) {
                throw new IllegalArgumentException(
                        "Inconsistent tenancyId in merge: expected " + tenancyId + ", found " + fragment.tenancyId());
            }
            for (IoTDeviceGoal device : fragment.devices()) {
                IoTDeviceGoal existing = merged.get(device.deviceId());
                if (existing != null) {
                    var deepMerged = new java.util.HashMap<>(existing.config());
                    deepMerged.putAll(device.config());
                    merged.put(device.deviceId(), new IoTDeviceGoal(
                            device.deviceId(),
                            device.deviceClass(),
                            device.label(),
                            device.physical(),
                            deepMerged,
                            device.dependsOn().isEmpty() ? existing.dependsOn() : device.dependsOn()));
                } else {
                    merged.put(device.deviceId(), device);
                }
            }
            allOrdering.addAll(fragment.ordering());
        }
        return new IoTGoals(tenancyId, List.copyOf(merged.values()), List.copyOf(allOrdering));
    }


    private InputStream resolveStream(String path) throws IOException {
        InputStream classpath = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream(path);
        if (classpath != null) return classpath;
        Path filePath = Path.of(path);
        if (Files.exists(filePath)) return Files.newInputStream(filePath);
        throw new IOException("Resource not found: " + path);
    }
}

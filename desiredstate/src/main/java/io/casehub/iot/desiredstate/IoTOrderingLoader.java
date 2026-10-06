package io.casehub.iot.desiredstate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.DeviceClass;
import io.casehub.yaml.jackson.YamlMappers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

@ApplicationScoped
public class IoTOrderingLoader {

    private final String orderingDir;
    private final ObjectMapper yamlMapper = YamlMappers.create();

    @Inject
    public IoTOrderingLoader(IoTOrderingConfig config) {
        this.orderingDir = config.path().orElse(null);
    }

    public IoTOrderingLoader(String orderingDir) {
        this.orderingDir = orderingDir;
    }

    public Set<IoTOrderingEntry> loadGlobal() {
        if (orderingDir == null) return Set.of();
        Path dir = Path.of(orderingDir);
        if (!Files.isDirectory(dir)) return Set.of();
        var entries = new LinkedHashSet<IoTOrderingEntry>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(this::isYaml).sorted().forEach(p -> entries.addAll(parseFile(p)));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list ordering directory", e);
        }
        return Set.copyOf(entries);
    }

    private Set<IoTOrderingEntry> parseFile(Path file) {
        try {
            JsonNode root = yamlMapper.readTree(file.toFile());
            JsonNode ordering = root.get("ordering");
            if (ordering == null || !ordering.isArray()) return Set.of();
            var entries = new LinkedHashSet<IoTOrderingEntry>();
            for (JsonNode entry : ordering) {
                JsonNode beforeNode = entry.get("before");
                JsonNode afterNode = entry.get("after");
                if (beforeNode == null || afterNode == null) {
                    throw new IllegalArgumentException(
                        "Ordering entry missing 'before' or 'after' key (file: " + file + ")");
                }
                DeviceClass before = parseDeviceClass(beforeNode.asText().toUpperCase(), file);
                DeviceClass after = parseDeviceClass(afterNode.asText().toUpperCase(), file);
                entries.add(new IoTOrderingEntry(before, after));
            }
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse ordering file: " + file, e);
        }
    }

    private static DeviceClass parseDeviceClass(String text, Path file) {
        try {
            return DeviceClass.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown device class '" + text + "' in ordering constraint (file: " + file + ")", e);
        }
    }


    private boolean isYaml(Path p) {
        String name = p.getFileName().toString();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }
}

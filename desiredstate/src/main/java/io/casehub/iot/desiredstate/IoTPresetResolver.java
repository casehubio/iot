package io.casehub.iot.desiredstate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.jackson.YamlMappers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
public class IoTPresetResolver {

    private final IoTGoalLoader loader;
    private final String presetDir;
    private final ObjectMapper yamlMapper = YamlMappers.create();

    @Inject
    public IoTPresetResolver(IoTGoalLoader loader, IoTPresetConfig config) {
        this.loader = loader;
        this.presetDir = config.path().orElse(null);
    }

    public IoTPresetResolver(IoTGoalLoader loader, String presetDir) {
        this.loader = loader;
        this.presetDir = presetDir;
    }

    public IoTGoals resolve(String name) {
        if (presetDir == null) {
            throw new IllegalStateException("casehub.iot.presets.path not configured");
        }
        Path presetPath = resolvePresetPath(name);
        IoTGoals self = loadPresetYaml(presetPath);

        List<String> imports = parseImports(presetPath);
        if (imports.isEmpty()) {
            return self;
        }

        List<IoTGoals> fragments = new ArrayList<>();
        for (String importName : imports) {
            Path importPath = resolvePresetPath(importName);
            fragments.add(loadPresetYaml(importPath));
        }
        fragments.add(self);
        return IoTGoalLoader.mergeGoals(fragments.toArray(IoTGoals[]::new));
    }

    private IoTGoals loadPresetYaml(Path presetPath) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) yamlMapper.readTree(presetPath.toFile());
            root.remove("import");
            return loader.loadFromNode(root);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to load preset: " + presetPath, e);
        }
    }

    public List<PresetInfo> listPresets() {
        if (presetDir == null) {
            return List.of();
        }
        Path dir = Path.of(presetDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<PresetInfo> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(this::isYaml).sorted().forEach(p -> {
                String name = stripExtension(p.getFileName().toString());
                List<String> imports = parseImports(p);
                int deviceCount = countDevices(p);
                result.add(new PresetInfo(name, imports, deviceCount));
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list preset directory", e);
        }
        return result;
    }

    private Path resolvePresetPath(String name) {
        Path dir = Path.of(presetDir).normalize();
        Path yaml = dir.resolve(name + ".yaml").normalize();
        if (!yaml.startsWith(dir)) {
            throw new IllegalArgumentException("Preset name contains path traversal: " + name);
        }
        if (Files.exists(yaml)) return yaml;
        Path yml = dir.resolve(name + ".yml").normalize();
        if (Files.exists(yml)) return yml;
        throw new IllegalArgumentException("Preset not found: " + name
            + " (searched " + yaml + " and " + yml + ")");
    }

    private List<String> parseImports(Path presetPath) {
        try {
            JsonNode root = yamlMapper.readTree(presetPath.toFile());
            JsonNode importNode = root.get("import");
            if (importNode == null || !importNode.isArray()) {
                return List.of();
            }
            List<String> imports = new ArrayList<>();
            importNode.forEach(n -> imports.add(n.asText()));
            return imports;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse preset: " + presetPath, e);
        }
    }

    private int countDevices(Path presetPath) {
        try {
            JsonNode root = yamlMapper.readTree(presetPath.toFile());
            JsonNode devices = root.get("devices");
            return devices != null && devices.isArray() ? devices.size() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private boolean isYaml(Path p) {
        String name = p.getFileName().toString();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}

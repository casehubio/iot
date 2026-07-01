package io.casehub.iot.webapp.app.situation;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.ras.api.Ganglion;
import io.casehub.ras.runtime.SituationDefinitionProvider;
import io.casehub.ras.runtime.SituationRegistration;
import io.casehub.iot.webapp.app.persistence.IoTSituationDefinitionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@ApplicationScoped
public class JpaRuntimeSituationDefinitionProvider implements SituationDefinitionProvider {

    private static final Logger LOG = Logger.getLogger(JpaRuntimeSituationDefinitionProvider.class.getName());

    private static final String[] CLASSPATH_RESOURCES = {
        "META-INF/ras-iot-situations.yaml",
        "META-INF/ras-iot-drools-situations.yaml"
    };

    private final EntityManager entityManager;
    private final CurrentPrincipal currentPrincipal;
    private final Instance<Ganglion> ganglia;

    private volatile List<SituationRegistration> cachedRegistrations;
    private volatile boolean initialized = false;

    @Inject
    JpaRuntimeSituationDefinitionProvider(final EntityManager entityManager,
                                          final CurrentPrincipal currentPrincipal,
                                          final Instance<Ganglion> ganglia) {
        this.entityManager = entityManager;
        this.currentPrincipal = currentPrincipal;
        this.ganglia = ganglia;
    }

    @Override
    public List<SituationRegistration> registrations() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    cachedRegistrations = loadAndMergeDefinitions();
                    initialized = true;
                }
            }
        }
        return cachedRegistrations;
    }

    private List<SituationRegistration> loadAndMergeDefinitions() {
        // Load classpath definitions
        final Map<String, SituationRegistration> classpathDefs = loadClasspathDefinitions();

        // Query database for tenant-specific overrides
        final String tenancyId = currentPrincipal.tenancyId();
        final List<IoTSituationDefinitionEntity> dbEntities = entityManager
            .createQuery(
                "SELECT s FROM IoTSituationDefinitionEntity s WHERE s.tenancyId = :tenancyId",
                IoTSituationDefinitionEntity.class
            )
            .setParameter("tenancyId", tenancyId)
            .getResultList();

        LOG.info(() -> String.format(
            "Loaded %d classpath situation definitions, %d database overrides for tenancy %s",
            classpathDefs.size(),
            dbEntities.size(),
            tenancyId
        ));

        // Merge: database definitions with matching situationId override classpath ones
        final Map<String, SituationRegistration> merged = new LinkedHashMap<>(classpathDefs);
        for (IoTSituationDefinitionEntity entity : dbEntities) {
            final var registration = new SituationRegistration(entity.getDefinition());
            merged.put(entity.getSituationId(), registration);
            LOG.fine(() -> String.format(
                "Override: situationId=%s from database for tenancy %s",
                entity.getSituationId(),
                tenancyId
            ));
        }

        return List.copyOf(merged.values());
    }

    private Map<String, SituationRegistration> loadClasspathDefinitions() {
        final Map<String, SituationRegistration> result = new LinkedHashMap<>();

        for (String resourcePath : CLASSPATH_RESOURCES) {
            try {
                final ClassLoader cl = Thread.currentThread().getContextClassLoader();
                final List<URL> resources = Collections.list(cl.getResources(resourcePath));

                if (resources.isEmpty()) {
                    LOG.fine(() -> "No classpath resources found at " + resourcePath);
                    continue;
                }

                for (URL url : resources) {
                    LOG.fine(() -> "Loading situation definitions from " + url);
                    try (InputStream is = url.openStream()) {
                        final List<SituationRegistration> registrations = parseYaml(is);
                        for (SituationRegistration reg : registrations) {
                            result.put(reg.definition().situationId(), reg);
                        }
                        LOG.info(() -> String.format(
                            "Loaded %d definitions from %s",
                            registrations.size(),
                            url
                        ));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(
                    "Failed to load classpath definitions from " + resourcePath,
                    e
                );
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private List<SituationRegistration> parseYaml(InputStream yaml) {
        final Map<String, Object> root = new Yaml().load(yaml);
        if (root == null || !root.containsKey("situations")) {
            return List.of();
        }

        final List<Map<String, Object>> situations =
            (List<Map<String, Object>>) root.get("situations");
        final List<SituationRegistration> result = new ArrayList<>(situations.size());

        for (Map<String, Object> sitMap : situations) {
            try {
                result.add(YamlParser.parseSituation(sitMap));
            } catch (Exception e) {
                final String sitId = sitMap.getOrDefault("situationId", "<unknown>").toString();
                LOG.severe(() -> String.format(
                    "Failed to parse situation definition %s: %s",
                    sitId,
                    e.getMessage()
                ));
                throw e;
            }
        }

        return result;
    }

    /**
     * Internal YAML parsing helper extracted for clarity.
     * Adapted from YamlSituationDefinitionProvider pattern.
     */
    private static class YamlParser {
        @SuppressWarnings("unchecked")
        static SituationRegistration parseSituation(Map<String, Object> map) {
            final String situationId = requireString(map, "situationId");
            final List<String> eventTypeList = (List<String>) map.get("eventTypes");
            if (eventTypeList == null || eventTypeList.isEmpty()) {
                throw new IllegalArgumentException(
                    "eventTypes must not be empty for situation '" + situationId + "'"
                );
            }

            java.time.Duration correlationWindow = null;
            if (map.containsKey("correlationWindow")) {
                correlationWindow = java.time.Duration.parse((String) map.get("correlationWindow"));
            }

            java.time.Duration eventBufferDelay = null;
            if (map.containsKey("eventBufferDelay")) {
                eventBufferDelay = java.time.Duration.parse((String) map.get("eventBufferDelay"));
            }

            final Map<String, Object> chainModeMap = (Map<String, Object>) map.get("chainMode");
            if (chainModeMap == null) {
                throw new IllegalArgumentException(
                    "chainMode required for situation '" + situationId + "'"
                );
            }

            final Map<String, Object> triggerMap = (Map<String, Object>) map.get("triggerConfig");
            if (triggerMap == null) {
                throw new IllegalArgumentException(
                    "triggerConfig required for situation '" + situationId + "'"
                );
            }

            final io.casehub.ras.api.ChainMode chainMode = parseChainMode(chainModeMap, situationId);
            final io.casehub.ras.api.CaseTriggerConfig triggerConfig = parseTriggerConfig(triggerMap);

            io.casehub.ras.api.TriggerMode triggerMode = new io.casehub.ras.api.TriggerMode.FireOnce();
            if (map.containsKey("triggerMode")) {
                triggerMode = parseTriggerMode((Map<String, Object>) map.get("triggerMode"));
            }

            final io.casehub.ras.api.SituationDefinition def = new io.casehub.ras.api.SituationDefinition(
                situationId,
                new java.util.LinkedHashSet<>(eventTypeList),
                correlationWindow,
                eventBufferDelay,
                chainMode,
                triggerConfig,
                triggerMode
            );
            return new SituationRegistration(def);
        }

        @SuppressWarnings("unchecked")
        private static io.casehub.ras.api.ChainMode parseChainMode(
                Map<String, Object> map, String situationId) {
            final String type = requireString(map, "type");
            return switch (type) {
                case "and" -> new io.casehub.ras.api.ChainMode.And(
                    new java.util.LinkedHashSet<>(requireList(map, "ganglia", situationId))
                );
                case "or" -> new io.casehub.ras.api.ChainMode.Or(
                    new java.util.LinkedHashSet<>(requireList(map, "ganglia", situationId))
                );
                case "threshold" -> new io.casehub.ras.api.ChainMode.Threshold(
                    new java.util.LinkedHashSet<>(requireList(map, "ganglia", situationId)),
                    requireNumber(map, "minConfidence", situationId).doubleValue()
                );
                case "sequence" -> new io.casehub.ras.api.ChainMode.Sequence(
                    requireList(map, "ganglia", situationId)
                );
                case "count" -> new io.casehub.ras.api.ChainMode.Count(
                    requireString(map, "ganglionId"),
                    requireNumber(map, "requiredCount", situationId).intValue()
                );
                default -> throw new IllegalArgumentException(
                    "Unknown chainMode type '" + type + "' in situation '" + situationId + "'"
                );
            };
        }

        @SuppressWarnings("unchecked")
        private static io.casehub.ras.api.CaseTriggerConfig parseTriggerConfig(
                Map<String, Object> map) {
            return new io.casehub.ras.api.CaseTriggerConfig(
                requireString(map, "caseNamespace"),
                requireString(map, "caseName"),
                requireString(map, "caseVersion"),
                (Map<String, Object>) map.getOrDefault("baseCaseData", Map.of())
            );
        }

        @SuppressWarnings("unchecked")
        private static io.casehub.ras.api.TriggerMode parseTriggerMode(Map<String, Object> map) {
            final String type = (String) map.getOrDefault("type", "fire-once");
            return switch (type) {
                case "fire-once" -> new io.casehub.ras.api.TriggerMode.FireOnce();
                case "repeating" -> {
                    final Object cooldownValue = map.get("cooldown");
                    if (cooldownValue == null) {
                        throw new IllegalArgumentException(
                            "triggerMode type 'repeating' requires 'cooldown' field"
                        );
                    }
                    final java.time.Duration cooldown =
                        java.time.Duration.parse(cooldownValue.toString());
                    yield new io.casehub.ras.api.TriggerMode.Repeating(cooldown);
                }
                default -> throw new IllegalArgumentException(
                    "Unknown triggerMode type: '" + type + "'. Expected 'fire-once' or 'repeating'"
                );
            };
        }

        private static String requireString(Map<String, Object> map, String key) {
            final Object value = map.get(key);
            if (value == null) {
                throw new IllegalArgumentException("Missing required field: " + key);
            }
            return value.toString();
        }

        @SuppressWarnings("unchecked")
        private static List<String> requireList(
                Map<String, Object> map, String key, String situationId) {
            final Object value = map.get(key);
            if (value == null) {
                throw new IllegalArgumentException(
                    "Missing required field '" + key + "' in chainMode for situation '"
                        + situationId + "'"
                );
            }
            return (List<String>) value;
        }

        private static Number requireNumber(
                Map<String, Object> map, String key, String situationId) {
            final Object value = map.get(key);
            if (value == null) {
                throw new IllegalArgumentException(
                    "Missing required field '" + key + "' in chainMode for situation '"
                        + situationId + "'"
                );
            }
            return (Number) value;
        }
    }
}

package io.casehub.iot.webapp.app.situation;

import io.casehub.iot.webapp.app.persistence.IoTSituationDefinitionEntity;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.ras.api.Ganglion;
import io.casehub.ras.api.SituationDefinitionProvider;
import io.casehub.ras.api.SituationRegistration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
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

    private final EntityManager      entityManager;
    private final CurrentPrincipal   currentPrincipal;
    private final Instance<Ganglion> ganglia;

    private volatile List<SituationRegistration> cachedRegistrations;
    private volatile boolean                     initialized = false;

    @Inject
    JpaRuntimeSituationDefinitionProvider(final EntityManager entityManager,
                                          final CurrentPrincipal currentPrincipal,
                                          final Instance<Ganglion> ganglia) {
        this.entityManager    = entityManager;
        this.currentPrincipal = currentPrincipal;
        this.ganglia          = ganglia;
    }

    @Override
    public List<SituationRegistration> registrations() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    cachedRegistrations = loadAndMergeDefinitions();
                    initialized         = true;
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
                final ClassLoader cl        = Thread.currentThread().getContextClassLoader();
                final List<URL>   resources = Collections.list(cl.getResources(resourcePath));

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
    private List<SituationRegistration> parseYaml(InputStream yaml) {return new io.casehub.ras.runtime.YamlSituationDefinitionProvider(yaml).registrations();}

}

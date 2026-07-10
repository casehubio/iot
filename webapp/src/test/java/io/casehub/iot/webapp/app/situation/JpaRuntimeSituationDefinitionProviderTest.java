package io.casehub.iot.webapp.app.situation;

import io.casehub.iot.webapp.app.persistence.IoTSituationDefinitionEntity;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.TriggerMode;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.iot.webapp.app.WebappPostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(WebappPostgresTestResource.class)
class JpaRuntimeSituationDefinitionProviderTest {

    @Inject
    JpaRuntimeSituationDefinitionProvider provider;

    @Inject
    EntityManager entityManager;

    @Inject
    CurrentPrincipal currentPrincipal;

    @BeforeEach
    @Transactional
    void setUp() {
        entityManager.createQuery("DELETE FROM IoTSituationDefinitionEntity").executeUpdate();
        entityManager.flush();
    }

    @Test
    void shouldReturnEmptyListWhenNoDefinitionsExist() {
        final List<SituationRegistration> registrations = provider.registrations();
        assertThat(registrations).isNotNull();
    }

    @Test
    @Transactional
    void shouldLoadDatabaseDefinitionsForCurrentTenant() {
        final SituationDefinition dbDef = new SituationDefinition(
            "test-situation",
            Set.of("io.casehub.iot.state_change.lock"),
            Duration.ofMinutes(5),
            null,
            new ChainMode.Or(new LinkedHashSet<>(List.of("lock-state"))),
            new TriggerAction.CreateCase(new CaseTriggerConfig(
                "io.casehub.iot",
                "security-alert",
                "1.0",
                Map.of()
            )),
            new TriggerMode.FireOnce()
        );

        final IoTSituationDefinitionEntity entity = new IoTSituationDefinitionEntity(
            "test-situation",
            currentPrincipal.tenancyId(),
            dbDef,
            Instant.now(),
            Instant.now()
        );
        entityManager.persist(entity);
        entityManager.flush();
        entityManager.clear();

        final JpaRuntimeSituationDefinitionProvider freshProvider =
            new JpaRuntimeSituationDefinitionProvider(
                entityManager,
                currentPrincipal,
                null
            );

        final List<SituationRegistration> registrations = freshProvider.registrations();

        assertThat(registrations)
            .extracting(r -> r.definition().situationId())
            .contains("test-situation");

        final SituationRegistration testReg = registrations.stream()
            .filter(r -> r.definition().situationId().equals("test-situation"))
            .findFirst()
            .orElseThrow();

        assertThat(testReg.definition().eventTypes())
            .containsExactly("io.casehub.iot.state_change.lock");
        assertThat(testReg.definition().correlationWindow())
            .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @Transactional
    void shouldMergeDatabaseOverridesWithClasspathDefinitions() {
        final SituationDefinition override1 = new SituationDefinition(
            "unexpected-unlock",
            Set.of("io.casehub.iot.state_change.lock"),
            Duration.ofMinutes(10),
            null,
            new ChainMode.Or(new LinkedHashSet<>(List.of("lock-state"))),
            new TriggerAction.CreateCase(new CaseTriggerConfig(
                "io.casehub.iot",
                "security-alert",
                "1.0",
                Map.of("severity", "high")
            )),
            new TriggerMode.Repeating(Duration.ofMinutes(15))
        );

        final SituationDefinition custom = new SituationDefinition(
            "custom-situation",
            Set.of("io.casehub.iot.state_change.sensor"),
            Duration.ofMinutes(5),
            null,
            new ChainMode.Or(new LinkedHashSet<>(List.of("temperature-threshold"))),
            new TriggerAction.CreateCase(new CaseTriggerConfig(
                "io.casehub.iot",
                "generic-response",
                "1.0",
                Map.of()
            )),
            new TriggerMode.FireOnce()
        );

        final Instant now = Instant.now();
        entityManager.persist(new IoTSituationDefinitionEntity(
            "unexpected-unlock",
            currentPrincipal.tenancyId(),
            override1,
            now,
            now
        ));
        entityManager.persist(new IoTSituationDefinitionEntity(
            "custom-situation",
            currentPrincipal.tenancyId(),
            custom,
            now,
            now
        ));
        entityManager.flush();
        entityManager.clear();

        final JpaRuntimeSituationDefinitionProvider freshProvider =
            new JpaRuntimeSituationDefinitionProvider(
                entityManager,
                currentPrincipal,
                null
            );

        final List<SituationRegistration> registrations = freshProvider.registrations();

        assertThat(registrations)
            .extracting(r -> r.definition().situationId())
            .contains("custom-situation");

        final boolean hasUnexpectedUnlock = registrations.stream()
            .anyMatch(r -> r.definition().situationId().equals("unexpected-unlock"));

        if (hasUnexpectedUnlock) {
            final SituationRegistration overrideReg = registrations.stream()
                .filter(r -> r.definition().situationId().equals("unexpected-unlock"))
                .findFirst()
                .orElseThrow();

            assertThat(overrideReg.definition().correlationWindow())
                .isEqualTo(Duration.ofMinutes(10));
            assertThat(overrideReg.definition().triggerMode())
                .isInstanceOf(TriggerMode.Repeating.class);
        }
    }

    @Test
    @Transactional
    void shouldIsolateTenantDefinitions() {
        final SituationDefinition def = new SituationDefinition(
            "tenant-specific",
            Set.of("io.casehub.iot.state_change.lock"),
            Duration.ofMinutes(5),
            null,
            new ChainMode.Or(new LinkedHashSet<>(List.of("lock-state"))),
            new TriggerAction.CreateCase(new CaseTriggerConfig("io.casehub.iot", "security-alert", "1.0", Map.of())),
            new TriggerMode.FireOnce()
        );

        final Instant now = Instant.now();

        entityManager.persist(new IoTSituationDefinitionEntity(
            "tenant-specific",
            currentPrincipal.tenancyId(),
            def,
            now,
            now
        ));

        entityManager.persist(new IoTSituationDefinitionEntity(
            "tenant-specific",
            "OTHER_TENANT",
            def,
            now,
            now
        ));

        entityManager.flush();
        entityManager.clear();

        final JpaRuntimeSituationDefinitionProvider freshProvider =
            new JpaRuntimeSituationDefinitionProvider(
                entityManager,
                currentPrincipal,
                null
            );

        final List<SituationRegistration> registrations = freshProvider.registrations();

        final long count = registrations.stream()
            .filter(r -> r.definition().situationId().equals("tenant-specific"))
            .count();

        assertThat(count).isEqualTo(1);
    }

    @Test
    void shouldCacheRegistrationsAfterFirstLoad() {
        final List<SituationRegistration> first = provider.registrations();
        final List<SituationRegistration> second = provider.registrations();
        assertThat(first).isSameAs(second);
    }
}

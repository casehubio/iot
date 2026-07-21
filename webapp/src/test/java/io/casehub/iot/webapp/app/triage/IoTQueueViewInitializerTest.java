package io.casehub.iot.webapp.app.triage;

import io.casehub.platform.api.view.SubjectViewSpec;
import io.casehub.platform.api.view.SubjectViewStore;
import io.casehub.platform.view.SubjectViewOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IoTQueueViewInitializerTest {

    private SubjectViewStore viewStore;
    private SubjectViewOrchestrator orchestrator;
    private IoTQueueViewInitializer initializer;
    private static final String TENANCY = "test-tenant";

    @BeforeEach
    void setUp() throws Exception {
        viewStore = mock(SubjectViewStore.class);
        orchestrator = mock(SubjectViewOrchestrator.class);
        initializer = new IoTQueueViewInitializer();
        inject(initializer, "viewStore", viewStore);
        inject(initializer, "orchestrator", orchestrator);
        inject(initializer, "tenancyId", TENANCY);
    }

    @Test
    void createsAllFourViewsWhenNoneExist() {
        when(viewStore.findByTenancy(TENANCY)).thenReturn(List.of());
        when(orchestrator.saveView(any())).thenAnswer(inv -> inv.getArgument(0));

        initializer.onStartup(null);

        verify(orchestrator, times(4)).saveView(any(SubjectViewSpec.class));
    }

    @Test
    void skipsExistingViews_idempotent() {
        List<SubjectViewSpec> existing = List.of(
            new SubjectViewSpec(UUID.randomUUID(), "iot-immediate", TENANCY,
                "iot-triage:immediate", null, "enqueuedAt", "ASC", null, Instant.now()),
            new SubjectViewSpec(UUID.randomUUID(), "iot-ai-resolution", TENANCY,
                "iot-triage:ai-resolution", null, "enqueuedAt", "ASC", null, Instant.now()),
            new SubjectViewSpec(UUID.randomUUID(), "iot-operator-assisted", TENANCY,
                "iot-triage:operator-assisted", null, "enqueuedAt", "ASC", null, Instant.now()),
            new SubjectViewSpec(UUID.randomUUID(), "iot-operator-manual", TENANCY,
                "iot-triage:operator-manual", null, "enqueuedAt", "ASC", null, Instant.now()));
        when(viewStore.findByTenancy(TENANCY)).thenReturn(existing);

        initializer.onStartup(null);

        verify(orchestrator, never()).saveView(any());
    }

    @Test
    void createsOnlyMissingViews() {
        List<SubjectViewSpec> existing = List.of(
            new SubjectViewSpec(UUID.randomUUID(), "iot-immediate", TENANCY,
                "iot-triage:immediate", null, "enqueuedAt", "ASC", null, Instant.now()));
        when(viewStore.findByTenancy(TENANCY)).thenReturn(existing);
        when(orchestrator.saveView(any())).thenAnswer(inv -> inv.getArgument(0));

        initializer.onStartup(null);

        verify(orchestrator, times(3)).saveView(any(SubjectViewSpec.class));
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}

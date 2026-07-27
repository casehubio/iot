package io.casehub.iot.webapp.observer;

import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.Ganglion;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.api.TriggerMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DismissalGangliaObserverTest {

    private DismissalGangliaObserver.GangliaLookup lookup;
    private DismissalGangliaObserver observer;

    @BeforeEach
    void setUp() {
        lookup = mock(DismissalGangliaObserver.GangliaLookup.class);
        observer = new DismissalGangliaObserver(lookup);
    }

    @Test
    void closesReferencedGangliaOnDismissal() {
        var ganglion = mock(Ganglion.class);
        // ganglion.close() is void — no mock setup needed
        when(lookup.ganglion("g1")).thenReturn(ganglion);

        var def = new SituationDefinition("sit-1", Set.of("evt"),
                null, null, new ChainMode.Count("g1", 3),
                new TriggerAction.CreateCase(new io.casehub.ras.api.CaseTriggerConfig("io.casehub.test", "test-case", "1.0", java.util.Map.of())),
                new TriggerMode.FireOnce());
        when(lookup.findBySituationId("sit-1"))
                .thenReturn(Optional.of(new SituationRegistration(def)));

        var event = new SituationChangeEvent("tenant-a", "sit-1", "key-1",
                SituationChangeEvent.ChangeType.DISMISSED,
                SituationContext.initial("sit-1", "key-1", "tenant-a", Instant.now()));

        observer.onDismissal(event);

        verify(ganglion).close("sit-1", "key-1", "tenant-a");
    }

    @Test
    void closesMultipleGangliaFromAndChain() {
        var g1 = mock(Ganglion.class);
        var g2 = mock(Ganglion.class);
        // ganglion.close() is void — no mock setup needed
        when(lookup.ganglion("g1")).thenReturn(g1);
        when(lookup.ganglion("g2")).thenReturn(g2);

        var def = new SituationDefinition("sit-2", Set.of("evt"),
                null, null, new ChainMode.And(Set.of("g1", "g2")),
                new TriggerAction.CreateCase(new io.casehub.ras.api.CaseTriggerConfig("io.casehub.test", "test-case", "1.0", java.util.Map.of())),
                new TriggerMode.FireOnce());
        when(lookup.findBySituationId("sit-2"))
                .thenReturn(Optional.of(new SituationRegistration(def)));

        var event = new SituationChangeEvent("tenant-a", "sit-2", "key-1",
                SituationChangeEvent.ChangeType.DISMISSED,
                SituationContext.initial("sit-2", "key-1", "tenant-a", Instant.now()));

        observer.onDismissal(event);

        verify(g1).close("sit-2", "key-1", "tenant-a");
        verify(g2).close("sit-2", "key-1", "tenant-a");
    }

    @Test
    void ignoresNonDismissalEvents() {
        var event = new SituationChangeEvent("tenant-a", "sit-1", "key-1",
                SituationChangeEvent.ChangeType.TRIGGERED,
                SituationContext.initial("sit-1", "key-1", "tenant-a", Instant.now()));

        observer.onDismissal(event);

        verifyNoInteractions(lookup);
    }

    @Test
    void handlesUnknownSituationGracefully() {
        when(lookup.findBySituationId("unknown")).thenReturn(Optional.empty());

        var event = new SituationChangeEvent("tenant-a", "unknown", "key-1",
                SituationChangeEvent.ChangeType.DISMISSED,
                SituationContext.initial("unknown", "key-1", "tenant-a", Instant.now()));

        observer.onDismissal(event);
    }
}

package io.casehub.iot.webapp.observer;

import io.casehub.ras.api.Ganglion;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.runtime.SituationDefinitionRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;

@ApplicationScoped
public class DismissalGangliaObserver {

    private static final Logger LOG = Logger.getLogger(DismissalGangliaObserver.class);

    public interface GangliaLookup {
        Optional<SituationRegistration> findBySituationId(String situationId);
        Ganglion ganglion(String ganglionId);
    }

    private final GangliaLookup lookup;

    @Inject
    public DismissalGangliaObserver(SituationDefinitionRegistry registry) {
        this.lookup = new GangliaLookup() {
            @Override public Optional<SituationRegistration> findBySituationId(String situationId) {
                return registry.findBySituationId(situationId);
            }
            @Override public Ganglion ganglion(String ganglionId) {
                return registry.ganglion(ganglionId);
            }
        };
    }

    DismissalGangliaObserver(GangliaLookup lookup) {
        this.lookup = lookup;
    }

    public void onDismissal(@ObservesAsync SituationChangeEvent event) {
        if (event.changeType() != SituationChangeEvent.ChangeType.DISMISSED) {
            return;
        }

        lookup.findBySituationId(event.situationId()).ifPresentOrElse(
            registration -> {
                var ganglia = registration.definition().chainMode().referencedGanglia();
                for (String ganglionId : ganglia) {
                    try {
                        lookup.ganglion(ganglionId)
                                .close(event.situationId(), event.correlationKey(), event.tenancyId());
                    } catch (Exception e) {
                        LOG.warnf(e, "Failed to close ganglion %s for dismissed situation %s",
                                ganglionId, event.situationId());
                    }
                }
                LOG.debugf("Closed %d ganglia for dismissed situation %s [%s]",
                        ganglia.size(), event.situationId(), event.correlationKey());
            },
            () -> LOG.debugf("No definition found for dismissed situation %s — ganglia not closed",
                    event.situationId())
        );
    }
}

package io.casehub.iot.webapp.app.cbr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.iot.webapp.cbr.WorkItemContext;
import io.casehub.iot.webapp.cbr.WorkItemFeatureExtractor;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.platform.api.path.Path;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.api.WorkItemStatusEvent;
import io.casehub.work.api.spi.WorkItemObserver;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.service.WorkItemService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@ApplicationScoped
public class WorkItemOutcomeRecorder implements WorkItemObserver {

    private static final Logger LOG = Logger.getLogger(WorkItemOutcomeRecorder.class);
    private static final Set<WorkItemStatus> TERMINAL = EnumSet.of(
            WorkItemStatus.COMPLETED, WorkItemStatus.REJECTED, WorkItemStatus.FAULTED,
            WorkItemStatus.CANCELLED, WorkItemStatus.EXPIRED, WorkItemStatus.ESCALATED,
            WorkItemStatus.OBSOLETE);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CbrCaseMemoryStore store;
    private final Function<UUID, Optional<WorkItem>> workItemLookup;
    private final CaseInstanceCache caseInstanceCache;
    private final WorkItemCbrConfig config;

    @Inject
    public WorkItemOutcomeRecorder(CbrCaseMemoryStore store,
                                    WorkItemService workItemService,
                                    CaseInstanceCache caseInstanceCache,
                                    WorkItemCbrConfig config) {
        this(store, workItemService::findById, caseInstanceCache, config);
    }

    WorkItemOutcomeRecorder(CbrCaseMemoryStore store,
                             Function<UUID, Optional<WorkItem>> workItemLookup,
                             CaseInstanceCache caseInstanceCache,
                             WorkItemCbrConfig config) {
        this.store = store;
        this.workItemLookup = workItemLookup;
        this.caseInstanceCache = caseInstanceCache;
        this.config = config;
    }

    @Override
    public void onStatusChange(WorkItemStatusEvent event) {
        if (!config.enabled()) return;
        if (!TERMINAL.contains(event.status())) return;

        try {
            var workItemOpt = workItemLookup.apply(event.workItemId());
            if (workItemOpt.isEmpty()) {
                LOG.warnv("Work item {0} not found for CBR recording", event.workItemId());
                return;
            }
            var workItem = workItemOpt.get();
            var ctx = buildContext(workItem, event);
            var rawFeatures = WorkItemFeatureExtractor.extractForRetain(ctx);
            String solution = coalesce(workItem.resolution, event.outcome(),
                    event.detail(), event.status().name());

            var cbrCase = new FeatureVectorCbrCase(
                    workItem.title != null ? workItem.title : "work-item",
                    solution,
                    event.status().name(),
                    1.0,
                    FeatureValue.toFeatureMap(rawFeatures),
                    null, null);

            String caseId = parseCaseId(workItem.payload);
            store.store(cbrCase, "iot-work-item", event.workItemId().toString(),
                    new MemoryDomain("iot"), event.tenancyId(),
                    caseId, Path.root());
        } catch (Exception e) {
            LOG.warnv(e, "CBR recording failed for work item {0}", event.workItemId());
        }
    }

    private WorkItemContext buildContext(WorkItem workItem, WorkItemStatusEvent event) {
        var payload = parsePayload(workItem.payload);
        String deviceClass = (String) payload.get("deviceClass");
        String roomType = (String) payload.get("roomType");
        String caseType = (String) payload.get("caseType");
        String workerName = (String) payload.get("workerName");
        String eventTs = (String) payload.get("eventTimestamp");

        if (caseType == null) {
            var caseOpt = findCaseForWorkItem(event.workItemId(), event.tenancyId());
            if (caseOpt.isPresent()) {
                var ci = caseOpt.get();
                caseType = ci.getCaseMetaModel().getName();
                var working = ci.getCaseContext().layer("working");
                if (deviceClass == null) {
                    Object dc = working.get("deviceClass");
                    if (dc instanceof String s) deviceClass = s;
                }
                if (roomType == null) {
                    Object rt = working.get("roomType");
                    if (rt instanceof String s) roomType = s;
                }
            }
        }

        return new WorkItemContext(
                workItem.title, workItem.description,
                workItem.types != null
                        ? workItem.types.stream().map(t -> t.path).toList()
                        : List.of(),
                workItem.priority != null ? workItem.priority.name() : "MEDIUM",
                workItem.candidateGroups,
                workerName != null ? workerName : "unknown",
                caseType != null ? caseType : "unknown",
                deviceClass, roomType,
                eventTs != null ? Instant.parse(eventTs) : null,
                event.status().name(),
                workItem.assigneeId,
                workItem.createdAt,
                Instant.now());
    }

    private Optional<CaseInstance> findCaseForWorkItem(UUID workItemId, String tenancyId) {
        return caseInstanceCache.getAll().stream()
                .filter(ci -> tenancyId.equals(ci.tenancyId))
                .filter(ci -> workItemId.toString().equals(ci.getWaitingForWorkId()))
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsePayload(String payload) {
        if (payload == null || payload.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(payload, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String parseCaseId(String payload) {
        var map = parsePayload(payload);
        Object caseId = map.get("caseId");
        return caseId instanceof String s ? s : null;
    }

    private static String coalesce(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "unknown";
    }
}

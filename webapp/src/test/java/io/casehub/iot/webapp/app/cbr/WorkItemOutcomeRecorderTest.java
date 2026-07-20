package io.casehub.iot.webapp.app.cbr;

import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.*;
import io.casehub.platform.api.path.Path;
import io.casehub.work.api.*;
import io.casehub.work.runtime.model.WorkItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class WorkItemOutcomeRecorderTest {

    @Test
    void terminalStatus_storesCbrCase() {
        var captured = new ArrayList<CbrCase>();
        var store = captureStore(captured);
        var workItem = testWorkItem(WorkItemStatus.COMPLETED, "tech-1",
                """
                {"caseId":"case-1","caseType":"hvac-anomaly","workerName":"human-review",
                 "deviceClass":"thermostat","roomType":"bedroom"}
                """);
        var recorder = recorder(store, workItem, enabledConfig());

        recorder.onStatusChange(terminalEvent(workItem.id, WorkItemStatus.COMPLETED, "tech-1"));

        assertThat(captured).hasSize(1);
        assertThat(captured.getFirst().outcome()).isEqualTo("COMPLETED");
    }

    @Test
    void nonTerminalStatus_noOp() {
        var captured = new ArrayList<CbrCase>();
        var recorder = recorder(captureStore(captured), null, enabledConfig());

        recorder.onStatusChange(new WorkItemStatusEvent(
                WorkEventType.ASSIGNED, UUID.randomUUID(), WorkItemStatus.ASSIGNED,
                "actor", null, null, "tech-1", "group", null, "t1",
                Instant.now()));

        assertThat(captured).isEmpty();
    }

    @Test
    void disabledConfig_noOp() {
        var captured = new ArrayList<CbrCase>();
        var workItem = testWorkItem(WorkItemStatus.COMPLETED, "tech-1", "{}");
        var recorder = recorder(captureStore(captured), workItem, disabledConfig());

        recorder.onStatusChange(terminalEvent(workItem.id, WorkItemStatus.COMPLETED, "tech-1"));

        assertThat(captured).isEmpty();
    }

    @Test
    void payloadWithIoTContext_extractsDeviceClass() {
        var captured = new ArrayList<CbrCase>();
        var workItem = testWorkItem(WorkItemStatus.COMPLETED, "tech-1",
                """
                {"caseId":"c1","caseType":"safety-alert","workerName":"human-review",
                 "deviceClass":"smoke_detector","roomType":"kitchen"}
                """);
        var recorder = recorder(captureStore(captured), workItem, enabledConfig());

        recorder.onStatusChange(terminalEvent(workItem.id, WorkItemStatus.COMPLETED, "tech-1"));

        assertThat(captured).hasSize(1);
        var features = captured.getFirst().features();
        assertThat(features.get("deviceClass")).isEqualTo(FeatureValue.string("smoke_detector"));
        assertThat(features.get("roomType")).isEqualTo(FeatureValue.string("kitchen"));
    }

    @Test
    void missingPayload_storesWithWorkItemOnlyFeatures() {
        var captured = new ArrayList<CbrCase>();
        var workItem = testWorkItem(WorkItemStatus.COMPLETED, "tech-1", null);
        var recorder = recorder(captureStore(captured), workItem, enabledConfig());

        recorder.onStatusChange(terminalEvent(workItem.id, WorkItemStatus.COMPLETED, "tech-1"));

        assertThat(captured).hasSize(1);
        var features = captured.getFirst().features();
        assertThat(features.get("caseType")).isEqualTo(FeatureValue.string("unknown"));
        assertThat(features).doesNotContainKey("deviceClass");
    }

    @Test
    void solutionFallback_usesStatusNameWhenNoResolution() {
        var captured = new ArrayList<CbrCase>();
        var workItem = testWorkItem(WorkItemStatus.CANCELLED, null, "{}");
        workItem.resolution = null;
        var recorder = recorder(captureStore(captured), workItem, enabledConfig());

        recorder.onStatusChange(new WorkItemStatusEvent(
                WorkEventType.CANCELLED, workItem.id, WorkItemStatus.CANCELLED,
                "system", null, null, null, null, null, "t1", Instant.now()));

        assertThat(captured).hasSize(1);
        assertThat(captured.getFirst().solution()).isEqualTo("CANCELLED");
    }

    // --- helpers ---

    private static WorkItemOutcomeRecorder recorder(CbrCaseMemoryStore store,
                                                     WorkItem workItem,
                                                     WorkItemCbrConfig config) {
        return new WorkItemOutcomeRecorder(store, id ->
                workItem != null && workItem.id.equals(id)
                        ? Optional.of(workItem) : Optional.empty(),
                emptyCache(), config);
    }

    private static WorkItem testWorkItem(WorkItemStatus status, String assignee, String payload) {
        var wi = new WorkItem();
        wi.id = UUID.randomUUID();
        wi.status = status;
        wi.title = "Test work item";
        wi.description = "Test description";
        wi.priority = WorkItemPriority.HIGH;
        wi.candidateGroups = "hvac-technicians";
        wi.assigneeId = assignee;
        wi.payload = payload;
        wi.resolution = assignee != null ? "Resolved by " + assignee : null;
        wi.createdAt = Instant.parse("2026-07-17T12:00:00Z");
        wi.tenancyId = "t1";
        return wi;
    }

    private static WorkItemStatusEvent terminalEvent(UUID workItemId, WorkItemStatus status,
                                                      String assignee) {
        return new WorkItemStatusEvent(
                WorkEventType.COMPLETED, workItemId, status,
                "actor", null, null, assignee, "hvac-technicians",
                status.name(), "t1", Instant.now());
    }



    private static CaseInstanceCache emptyCache() {
        return new CaseInstanceCache() {
            @Override public void put(CaseInstance instance) {}
            @Override public CaseInstance get(UUID caseId) { return null; }
            @Override public void clear() {}
            @Override public List<CaseInstance> getAll() { return List.of(); }
        };
    }

    private static CbrCaseMemoryStore captureStore(List<CbrCase> captured) {
        return new CbrCaseMemoryStore() {
            @Override
            public String store(CbrCase c, String ct, String eid,
                                MemoryDomain d, String tid, String cid, Path scope) {
                captured.add(c);
                return "id";
            }
            @Override public void registerSchema(CbrFeatureSchema schema) {}
            @Override public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(
                    CbrQuery query, Class<C> caseType) { return List.of(); }
            @Override public Integer erase(EraseRequest r) { return 0; }
            @Override public Integer eraseEntity(String eid, String tid) { return 0; }
            @Override public Integer eraseByScope(Path scope, String tid) { return 0; }
            @Override public void recordOutcome(String cid, String tid, CbrOutcome o) {}
            @Override public Integer purge(CbrRetentionPolicy p) { return 0; }
            @Override public void supersede(String cid, String tid, String scid, String r) {}
            @Override public void reinstate(String cid, String tid) {}
            @Override public java.util.List<io.casehub.neocortex.memory.cbr.SupersessionStatus> findSupersededCases(String cid, io.casehub.neocortex.memory.MemoryDomain d) { return java.util.List.of(); }
            @Override public io.casehub.neocortex.memory.cbr.SupersessionStatus getSupersessionStatus(String cid, String tid) { return null; }
        };
    }

    private static WorkItemCbrConfig enabledConfig() {
        return new WorkItemCbrConfig() {
            @Override public boolean enabled() { return true; }
            @Override public int topK() { return 20; }
            @Override public double minSimilarity() { return 0.3; }
        };
    }

    private static WorkItemCbrConfig disabledConfig() {
        return new WorkItemCbrConfig() {
            @Override public boolean enabled() { return false; }
            @Override public int topK() { return 20; }
            @Override public double minSimilarity() { return 0.3; }
        };
    }
}

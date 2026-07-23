package io.casehub.iot.webapp.worker;

import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemPriority;
import io.casehub.work.api.WorkItemRef;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.api.spi.WorkItemCreator;
import io.casehub.worker.api.WorkerResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HumanDecisionWorkerFunctionTest {

    @Test
    void createsWorkItemWithCorrectFields() {
        var captured = new ArrayList<WorkItemCreateRequest>();
        var fn       = new HumanDecisionWorkerFunction(captureCreator(captured));

        fn.apply(fullInput());

        assertThat(captured).hasSize(1);
        var req = captured.getFirst();
        assertThat(req.types).containsExactly("human-review");
        assertThat(req.priority).isEqualTo(WorkItemPriority.HIGH);
        assertThat(req.candidateGroups).isEqualTo("hvac-technicians");
    }

    @Test
    void payloadContainsIoTContext() {
        var captured = new ArrayList<WorkItemCreateRequest>();
        var fn       = new HumanDecisionWorkerFunction(captureCreator(captured));

        fn.apply(fullInput());

        var req = captured.getFirst();
        assertThat(req.payload).contains("\"deviceClass\":\"thermostat\"");
        assertThat(req.payload).contains("\"caseId\":\"case-uuid-1\"");
        assertThat(req.payload).contains("\"caseType\":\"hvac-anomaly\"");
    }

    @Test
    void returnsWorkItemIdInResult() {
        var fn = new HumanDecisionWorkerFunction(captureCreator(new ArrayList<>()));

        WorkerResult<Map<String, Object>> result = fn.apply(fullInput());

        assertThat(result.output()).containsKey("workItemId");
    }

    @Test
    void priorityMapping_criticalToUrgent() {
        var captured = new ArrayList<WorkItemCreateRequest>();
        var fn       = new HumanDecisionWorkerFunction(captureCreator(captured));

        var input = new LinkedHashMap<>(fullInput());
        input.put("urgency", "critical");
        fn.apply(input);

        assertThat(captured.getFirst().priority).isEqualTo(WorkItemPriority.URGENT);
    }

    @Test
    void priorityMapping_absentToMedium() {
        var captured = new ArrayList<WorkItemCreateRequest>();
        var fn       = new HumanDecisionWorkerFunction(captureCreator(captured));

        var input = new LinkedHashMap<>(fullInput());
        input.remove("urgency");
        fn.apply(input);

        assertThat(captured.getFirst().priority).isEqualTo(WorkItemPriority.MEDIUM);
    }

    private static Map<String, Object> fullInput() {
        var map = new LinkedHashMap<String, Object>();
        map.put("caseId", "case-uuid-1");
        map.put("caseType", "hvac-anomaly");
        map.put("workerName", "human-review");
        map.put("deviceClass", "thermostat");
        map.put("roomType", "bedroom");
        map.put("eventTimestamp", "2026-07-17T14:30:00Z");
        map.put("situationId", "sustained-temp-rise");
        map.put("urgency", "high");
        map.put("candidateGroups", "hvac-technicians");
        map.put("planItemId", "pi-uuid-1");
        map.put("situationContext", "Temperature anomaly detected");
        return map;
    }

    private static WorkItemCreator captureCreator(List<WorkItemCreateRequest> captured) {
        return new WorkItemCreator() {
            @Override
            public WorkItemRef create(WorkItemCreateRequest req) {
                captured.add(req);
                return new WorkItemRef(UUID.randomUUID(), WorkItemStatus.PENDING,
                                       req.callerRef, null, null, req.candidateGroups,
                                       null, req.tenancyId, req.payload, null, null);
            }

            @Override
            public Optional<WorkItemRef> findByCallerRef(String ref)       {return Optional.empty();}

            @Override
            public Optional<WorkItemRef> findActiveByCallerRef(String ref) {return Optional.empty();}

            @Override
            public void obsoleteByCallerRef(String ref)                    {}
        };
    }
}

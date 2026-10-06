package io.casehub.iot.scenario;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.PlaybookBindingEvent;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.desiredstate.IoTActualStateAdapter;
import io.casehub.iot.desiredstate.IoTDeviceGoal;
import io.casehub.iot.desiredstate.IoTGoalCompiler;
import io.casehub.iot.desiredstate.IoTGoals;
import io.casehub.iot.desiredstate.IoTNodeProvisioner;
import io.casehub.iot.desiredstate.IoTPresetResolver;
import io.casehub.pages.playbook.DeliveryContext;
import io.casehub.pages.playbook.DeliveryHandler;
import io.casehub.pages.playbook.StepOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@ApplicationScoped
public class DesiredStateDeliveryHandler implements DeliveryHandler {

    private final DeviceRegistry registry;
    private final IoTPresetResolver presetResolver;
    private final IoTGoalCompiler compiler;
    private final IoTActualStateAdapter actualStateAdapter;
    private final IoTNodeProvisioner provisioner;
    private final String tenancyId;
    private final Consumer<PlaybookBindingEvent> bindingEvent;


    private final DefaultDesiredStateGraphFactory graphFactory =
            new DefaultDesiredStateGraphFactory();
    private final TransitionPlanner planner = new TransitionPlanner();

    @Inject
    public DesiredStateDeliveryHandler(
            DeviceRegistry registry,
            IoTPresetResolver presetResolver,
            IoTGoalCompiler compiler,
            IoTActualStateAdapter actualStateAdapter,
            IoTNodeProvisioner provisioner,
            @ConfigProperty(name = "casehub.iot.tenancy-id") String tenancyId,
            jakarta.enterprise.event.Event<PlaybookBindingEvent> bindingEvent) {
        this(registry, presetResolver, compiler, actualStateAdapter, provisioner, tenancyId,
                bindingEvent::fire);
    }

    DesiredStateDeliveryHandler(
            DeviceRegistry registry,
            IoTPresetResolver presetResolver,
            IoTGoalCompiler compiler,
            IoTActualStateAdapter actualStateAdapter,
            IoTNodeProvisioner provisioner,
            String tenancyId,
            Consumer<PlaybookBindingEvent> bindingEvent) {
        this.registry = registry;
        this.presetResolver = presetResolver;
        this.compiler = compiler;
        this.actualStateAdapter = actualStateAdapter;
        this.provisioner = provisioner;
        this.tenancyId = tenancyId;
        this.bindingEvent = bindingEvent;
    }

    @Override
    public String name() {
        return "desired-state";
    }

    @Override
    public StepOutcome execute(String stepName, Map<String, Object> data,
                               DeliveryContext ctx) {
        String   executionId = UUID.randomUUID().toString();
        IoTGoals goals;
        try {
            goals = resolveGoals(data);
        } catch (Exception e) {
            return StepOutcome.fail(stepName, e.getMessage());
        }

        try {
            return reconcile(stepName, goals, executionId);
        } catch (Exception e) {
            return StepOutcome.fail(stepName, "Reconciliation failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private IoTGoals resolveGoals(Map<String, Object> data) {
        if (data.containsKey("preset")) {
            return presetResolver.resolve((String) data.get("preset"));
        }

        List<IoTDeviceGoal> deviceGoals = new ArrayList<>();
        List<String> unknownIds = new ArrayList<>();

        for (var entry : data.entrySet()) {
            String deviceId = entry.getKey();
            var entity = registry.findById(deviceId);
            if (entity.isEmpty()) {
                unknownIds.add(deviceId);
                continue;
            }
            DeviceEntity device = entity.get();
            deviceGoals.add(new IoTDeviceGoal(
                    deviceId,
                    device.deviceClass(),
                    device.label(),
                    false,
                    (Map<String, Object>) entry.getValue(),
                    List.of()));
        }

        if (!unknownIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown device IDs: " + unknownIds);
        }

        return new IoTGoals(tenancyId, deviceGoals);
    }

    private StepOutcome reconcile(String stepName, IoTGoals goals, String executionId) {
        var compilationResult = compiler.compile(goals, graphFactory);
        if (!(compilationResult instanceof CompilationResult.SingleGraph sg)) {
            return StepOutcome.fail(stepName,
                                    "Unexpected compilation result: "
                                    + compilationResult.getClass().getSimpleName());
        }

        DesiredStateGraph graph  = sg.graph();
        var               actual = actualStateAdapter.readActual(graph, tenancyId);
        var               plan   = planner.plan(graph, actual);

        Set<String> planDeviceIds = plan.flatAdditions().stream()
                                        .filter(s -> s.action() == StepAction.PROVISION)
                                        .map(s -> extractDeviceId(s.node()))
                                        .collect(Collectors.toSet());

        boolean             stepStartFired = false;
        Map<String, String> deviceOutcomes = new LinkedHashMap<>();
        List<String>        failedDetails  = new ArrayList<>();

        try {
            if (!planDeviceIds.isEmpty()) {
                bindingEvent.accept(new PlaybookBindingEvent.StepStart(
                        executionId, tenancyId, stepName, planDeviceIds));
                stepStartFired = true;
            }

            for (OrderedStep step : plan.flatAdditions()) {
                if (step.action() == StepAction.PROVISION) {
                    var result = provisioner.provision(
                            step.node(), new ProvisionContext(tenancyId, graph));
                    String deviceId = extractDeviceId(step.node());
                    if (result instanceof ProvisionResult.Success
                        || result instanceof ProvisionResult.AlreadyConverged) {
                        if (!deviceOutcomes.containsKey(deviceId)) {
                            deviceOutcomes.put(deviceId, "provisioned");
                            bindingEvent.accept(new PlaybookBindingEvent.DeviceProvisioned(
                                    executionId, tenancyId, stepName, deviceId));
                        }
                    } else if (result instanceof ProvisionResult.Failed f) {
                        deviceOutcomes.put(deviceId, "failed");
                        failedDetails.add(deviceId + ": " + f.reason());
                        bindingEvent.accept(new PlaybookBindingEvent.DeviceFailed(
                                executionId, tenancyId, stepName, deviceId, f.reason()));
                    } else {
                        deviceOutcomes.put(deviceId, "failed");
                        failedDetails.add(deviceId + ": " + result.getClass().getSimpleName());
                        bindingEvent.accept(new PlaybookBindingEvent.DeviceFailed(
                                executionId, tenancyId, stepName, deviceId,
                                result.getClass().getSimpleName()));
                    }
                }
            }

            int provisioned = (int) deviceOutcomes.values().stream()
                                                  .filter("provisioned"::equals).count();
            int failed = (int) deviceOutcomes.values().stream()
                                             .filter("failed"::equals).count();

            if (stepStartFired) {
                if (failed > 0) {
                    bindingEvent.accept(new PlaybookBindingEvent.StepFailed(
                            executionId, tenancyId, stepName, provisioned, failed, failedDetails));
                } else {
                    bindingEvent.accept(new PlaybookBindingEvent.StepComplete(
                            executionId, tenancyId, stepName, provisioned, failed));
                }
            }

            if (failed > 0) {
                return StepOutcome.fail(stepName,
                                        failed + " of " + (provisioned + failed)
                                        + " devices failed: " + failedDetails);
            }
            return StepOutcome.ok(stepName, Map.of(
                    "provisioned", provisioned,
                    "converged", true));
        } catch (Exception e) {
            if (stepStartFired) {
                bindingEvent.accept(new PlaybookBindingEvent.Clear(executionId, tenancyId));
            }
            throw e;
        }
    }

    private static String extractDeviceId(DesiredNode node) {
        String id = node.id().value();
        return id.endsWith("-config") ? id.substring(0, id.length() - 7) : id;
    }


}

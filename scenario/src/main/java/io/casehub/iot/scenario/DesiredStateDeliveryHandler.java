package io.casehub.iot.scenario;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.desiredstate.IoTActualStateAdapter;
import io.casehub.iot.desiredstate.IoTDeviceGoal;
import io.casehub.iot.desiredstate.IoTGoalCompiler;
import io.casehub.iot.desiredstate.IoTGoals;
import io.casehub.iot.desiredstate.IoTNodeProvisioner;
import io.casehub.iot.desiredstate.IoTPresetResolver;
import io.casehub.pages.scenario.DeliveryContext;
import io.casehub.pages.scenario.DeliveryHandler;
import io.casehub.pages.scenario.StepOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class DesiredStateDeliveryHandler implements DeliveryHandler {

    private final DeviceRegistry registry;
    private final IoTPresetResolver presetResolver;
    private final IoTGoalCompiler compiler;
    private final IoTActualStateAdapter actualStateAdapter;
    private final IoTNodeProvisioner provisioner;
    private final String tenancyId;

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
            @ConfigProperty(name = "casehub.iot.tenancy-id") String tenancyId) {
        this.registry = registry;
        this.presetResolver = presetResolver;
        this.compiler = compiler;
        this.actualStateAdapter = actualStateAdapter;
        this.provisioner = provisioner;
        this.tenancyId = tenancyId;
    }

    @Override
    public String name() {
        return "desired-state";
    }

    @Override
    public StepOutcome execute(String stepName, Map<String, Object> data,
                               DeliveryContext ctx) {
        IoTGoals goals;
        try {
            goals = resolveGoals(data);
        } catch (Exception e) {
            return StepOutcome.fail(stepName, e.getMessage());
        }

        try {
            return reconcile(stepName, goals);
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

    private StepOutcome reconcile(String stepName, IoTGoals goals) {
        var compilationResult = compiler.compile(goals, graphFactory);
        if (!(compilationResult instanceof CompilationResult.SingleGraph sg)) {
            return StepOutcome.fail(stepName,
                    "Unexpected compilation result: "
                            + compilationResult.getClass().getSimpleName());
        }

        DesiredStateGraph graph = sg.graph();
        var actual = actualStateAdapter.readActual(graph, tenancyId);
        var plan = planner.plan(graph, actual);

        int provisioned = 0;
        int failed = 0;
        List<String> failedDetails = new ArrayList<>();

        for (OrderedStep step : plan.flatAdditions()) {
            if (step.action() == StepAction.PROVISION) {
                var result = provisioner.provision(
                        step.node(), new ProvisionContext(tenancyId, graph));
                if (result instanceof ProvisionResult.Success
                        || result instanceof ProvisionResult.AlreadyConverged) {
                    provisioned++;
                } else if (result instanceof ProvisionResult.Failed f) {
                    failed++;
                    failedDetails.add(step.node().id().value() + ": " + f.reason());
                } else {
                    failed++;
                    failedDetails.add(step.node().id().value() + ": "
                            + result.getClass().getSimpleName());
                }
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
    }
}

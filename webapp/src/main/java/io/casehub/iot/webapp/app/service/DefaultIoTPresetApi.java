package io.casehub.iot.webapp.app.service;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.desiredstate.IoTActualStateAdapter;
import io.casehub.iot.desiredstate.IoTGoalCompiler;
import io.casehub.iot.desiredstate.IoTGoals;
import io.casehub.iot.desiredstate.IoTNodeProvisioner;
import io.casehub.iot.desiredstate.IoTPresetResolver;
import io.casehub.iot.desiredstate.PresetInfo;
import io.casehub.iot.webapp.rest.PresetApplyResult;
import io.casehub.iot.webapp.rest.PresetDiff;
import io.casehub.platform.api.mcp.ContextParam;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@McpDomain(value = "iot/presets", app = "iot", basePath = "/api/presets",
    summary = "IoT saved state presets — list, preview, apply named configurations")
@ApplicationScoped
@Blocking
public class DefaultIoTPresetApi {

    @Inject IoTPresetResolver resolver;
    @Inject DeviceRegistry deviceRegistry;
    @Inject PresetDiffCalculator diffCalculator;
    @Inject IoTGoalCompiler compiler;
    @Inject IoTActualStateAdapter actualStateAdapter;
    @Inject IoTNodeProvisioner provisioner;

    private final DefaultDesiredStateGraphFactory graphFactory = new DefaultDesiredStateGraphFactory();
    private final TransitionPlanner planner = new TransitionPlanner();

    @PlatformQuery("List available presets")
    @RestPath("/")
    public List<PresetInfo> listPresets(@ContextParam("tenancyId") String tenancyId) {
        return resolver.listPresets();
    }

    @PlatformQuery("Preview what changes a preset would make without applying it")
    @RestPath("/{name}/diff")
    public PresetDiff diffPreset(
            @ContextParam("tenancyId") String tenancyId,
            @PathParam("name") String name) {
        IoTGoals goals = resolver.resolve(name);
        return diffCalculator.calculate(goals, deviceRegistry, tenancyId);
    }

    @PlatformMutation("Apply a named preset — converges devices to the preset's desired state")
    @RestPath("/{name}/apply")
    public PresetApplyResult applyPreset(
            @ContextParam("tenancyId") String tenancyId,
            @PathParam("name") String name) {
        IoTGoals goals = resolver.resolve(name);
        var compilationResult = compiler.compile(goals, graphFactory);
        if (!(compilationResult instanceof CompilationResult.SingleGraph sg)) {
            throw new IllegalStateException("Preset compilation produced unexpected result type: "
                + compilationResult.getClass().getSimpleName());
        }
        DesiredStateGraph graph = sg.graph();
        var actual      = actualStateAdapter.readActual(graph, tenancyId);
        var plan        = planner.plan(graph, actual);
        int provisioned = 0;
        int skipped     = 0;
        for (var step : plan.flatAdditions()) {
            if (step.action() == StepAction.PROVISION) {
                var result = provisioner.provision(step.node(),
                                                   new ProvisionContext(tenancyId, graph));
                if (result instanceof ProvisionResult.Success) {
                    provisioned++;
                } else {
                    skipped++;
                }
            }
        }
        return new PresetApplyResult(name, provisioned, skipped);
    }


}

package io.casehub.iot.testing;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.platform.simulation.CorpusSeed;
import io.casehub.platform.simulation.KeyExtractor;
import io.casehub.platform.simulation.SimulationRuntime;

import java.util.List;

public final class IoTCorpusSeed {

    public static final String DISCOVER = "device-provider.discover";
    public static final String DISPATCH = "device-provider.dispatch";

    private static final String DEFAULT_TENANT = "default-tenant";
    private static final String SIM_DEVICE = "sim-device";

    private IoTCorpusSeed() {}

    public static void apply(SimulationRuntime runtime) {
        apply(runtime, "fixtures/standard-home.yaml");
    }

    public static void apply(SimulationRuntime runtime, String fixtureResource) {
        runtime.apply(discoverSeed(fixtureResource));
        runtime.apply(dispatchSeed());
    }

    public static CorpusSeed<Object, List<DeviceEntity>> discoverSeed(String fixtureResource) {
        List<DeviceEntity> devices = DeviceFixtureLoader.load(fixtureResource);
        var seed = new CorpusSeed<Object, List<DeviceEntity>>(DISCOVER, DEFAULT_TENANT);
        seed.add(null, devices);
        return seed;
    }

    public static CorpusSeed<DeviceCommand, CommandResult> dispatchSeed() {
        var seed = new CorpusSeed<DeviceCommand, CommandResult>(DISPATCH, DEFAULT_TENANT)
                .withKeyExtractor(keyExtractor());

        seed.add(new DeviceCommand(SIM_DEVICE, DeviceCommand.ACTION_TURN_ON, null, null, null), CommandResult.SENT);
        seed.add(new DeviceCommand(SIM_DEVICE, DeviceCommand.ACTION_TURN_OFF, null, null, null), CommandResult.SENT);
        seed.add(new DeviceCommand(SIM_DEVICE, DeviceCommand.ACTION_SET_TEMPERATURE, null, null, null), CommandResult.SENT);
        seed.add(new DeviceCommand(SIM_DEVICE, DeviceCommand.ACTION_LOCK, null, null, null), CommandResult.SENT);
        seed.add(new DeviceCommand(SIM_DEVICE, DeviceCommand.ACTION_UNLOCK, null, null, null), CommandResult.SENT);
        seed.add("unknown_action",
                new DeviceCommand(SIM_DEVICE, "unknown_action", null, null, null),
                CommandResult.FAILED);

        return seed;
    }

    public static KeyExtractor<DeviceCommand> keyExtractor() {
        return DeviceCommand::action;
    }
}

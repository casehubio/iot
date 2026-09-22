package io.casehub.iot.testing;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.platform.simulation.MapSimulationConfig;
import io.casehub.platform.simulation.SimulationRuntime;
import io.casehub.platform.simulation.inmem.InMemorySimulationCorpus;
import io.casehub.platform.simulation.SimulationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IoTCorpusSeedTest {

    private SimulationRuntime runtime;

    @BeforeEach
    void setUp() {
        var config = MapSimulationConfig.builder()
                .strategy(IoTCorpusSeed.DISCOVER, "sequential")
                .strategy(IoTCorpusSeed.DISPATCH, "key-lookup")
                .build();
        runtime = new SimulationRuntime(config, new InMemorySimulationCorpus<>());
        IoTCorpusSeed.apply(runtime);
    }

    @Test
    void discoverSeedLoadsStandardHomeFixture() {
        var seed = IoTCorpusSeed.discoverSeed("fixtures/standard-home.yaml");
        List<?> records = seed.build();

        assertThat(records).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void discoverStrategyReturnsFixtureDevices() {
        Optional<SimulationStrategy<Object, List<DeviceEntity>>> strategy =
                runtime.strategyFor(IoTCorpusSeed.DISCOVER);

        assertThat(strategy).isPresent();
        List<DeviceEntity> devices = strategy.get().resolve(null);
        assertThat(devices).isNotEmpty();
        assertThat(devices.stream().map(DeviceEntity::deviceId))
                .contains("light-living-1", "thermostat-living-1", "presence-front-1");
    }

    @Test
    void dispatchSeedRegistersKeyExtractor() {
        var seed = IoTCorpusSeed.dispatchSeed();

        assertThat(seed.keyExtractor()).isNotNull();
        assertThat(seed.keyExtractor().extract(
                new DeviceCommand("d1", "turn_on", null, null, null)))
                .isEqualTo("turn_on");
    }

    @Test
    void dispatchSeedHasSixRecords() {
        var seed = IoTCorpusSeed.dispatchSeed();
        assertThat(seed.build()).hasSize(6);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatchStrategyResolvesTornOn() {
        Optional<SimulationStrategy<DeviceCommand, CommandResult>> strategy =
                runtime.strategyFor(IoTCorpusSeed.DISPATCH);

        assertThat(strategy).isPresent();
        CommandResult result = strategy.get().resolve(
                new DeviceCommand("any-device", DeviceCommand.ACTION_TURN_ON, null, null, null));
        assertThat(result).isEqualTo(CommandResult.SENT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatchStrategyResolvesTornOff() {
        Optional<SimulationStrategy<DeviceCommand, CommandResult>> strategy =
                runtime.strategyFor(IoTCorpusSeed.DISPATCH);

        assertThat(strategy).isPresent();
        CommandResult result = strategy.get().resolve(
                new DeviceCommand("any-device", DeviceCommand.ACTION_TURN_OFF, null, null, null));
        assertThat(result).isEqualTo(CommandResult.SENT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatchStrategyResolvesSetTemperature() {
        Optional<SimulationStrategy<DeviceCommand, CommandResult>> strategy =
                runtime.strategyFor(IoTCorpusSeed.DISPATCH);

        assertThat(strategy).isPresent();
        CommandResult result = strategy.get().resolve(
                new DeviceCommand("any-device", DeviceCommand.ACTION_SET_TEMPERATURE, null, null, null));
        assertThat(result).isEqualTo(CommandResult.SENT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatchStrategyResolvesUnknownActionToFailed() {
        Optional<SimulationStrategy<DeviceCommand, CommandResult>> strategy =
                runtime.strategyFor(IoTCorpusSeed.DISPATCH);

        assertThat(strategy).isPresent();
        CommandResult result = strategy.get().resolve(
                new DeviceCommand("any-device", "unknown_action", null, null, null));
        assertThat(result).isEqualTo(CommandResult.FAILED);
    }

    @Test
    void keyExtractorMapsActionToKey() {
        var extractor = IoTCorpusSeed.keyExtractor();

        assertThat(extractor.extract(
                new DeviceCommand("d1", "turn_on", null, null, null)))
                .isEqualTo("turn_on");
        assertThat(extractor.extract(
                new DeviceCommand("d2", "set_temperature", null, null, null)))
                .isEqualTo("set_temperature");
    }
}

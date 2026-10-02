package io.casehub.iot.desiredstate;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IoTNodeProvisionerTest {

    private static final Instant NOW = Instant.now();
    private static final DefaultDesiredStateGraphFactory FACTORY = new DefaultDesiredStateGraphFactory();

    @Test
    void configProvision_deviceFound_dispatchesCommand() {
        var dispatched = new ArrayList<DeviceCommand>();
        var device = switchDevice("sw-1", false);
        var provisioner = provisioner(device, dispatched, CommandResult.SENT);

        var node = configNode("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var result = provisioner.provision(node, context());

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.getFirst().action()).isEqualTo(DeviceCommand.ACTION_TURN_ON);
    }

    @Test
    void configProvision_deviceAbsent_failed() {
        var provisioner = provisioner(null, new ArrayList<>(), CommandResult.SENT);

        var node = configNode("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var result = provisioner.provision(node, context());

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
    }

    @Test
    void configProvision_commandFailed_failed() {
        var device = switchDevice("sw-1", false);
        var provisioner = provisioner(device, new ArrayList<>(), CommandResult.FAILED);

        var node = configNode("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var result = provisioner.provision(node, context());

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
    }

    @Test
    void configProvision_commandTimeout_failed() {
        var device = switchDevice("sw-1", false);
        var provisioner = provisioner(device, new ArrayList<>(), CommandResult.TIMEOUT);

        var node = configNode("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var result = provisioner.provision(node, context());

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
        assertThat(((ProvisionResult.Failed) result).reason()).contains("TIMEOUT");
    }

    @Test
    void configProvision_noDrift_noCommandsDispatched() {
        var dispatched = new ArrayList<DeviceCommand>();
        var device = switchDevice("sw-1", true);
        var provisioner = provisioner(device, dispatched, CommandResult.SENT);

        var node = configNode("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var result = provisioner.provision(node, context());

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(dispatched).isEmpty();
    }

    @Test
    void physicalProvision_rejectedAsUnknownSpec() {
        var provisioner = provisioner(null, new ArrayList<>(), CommandResult.SENT);
        var node = new DesiredNode(NodeId.of("dev-1"),
            new PhysicalDeviceSpec("dev-1", DeviceClass.THERMOSTAT, "Label"), HumanGating.NONE);
        var result = provisioner.provision(node, context());

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
    }

    @Test
    void configDeprovision_returnsSuccess() {
        var provisioner = provisioner(null, new ArrayList<>(), CommandResult.SENT);
        var node = configNode("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var result = provisioner.deprovision(node, deprovisionContext());

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
    }

    @Test
    void physicalDeprovision_returnsSuccess() {
        var provisioner = provisioner(null, new ArrayList<>(), CommandResult.SENT);
        var node = new DesiredNode(NodeId.of("dev-1"),
            new PhysicalDeviceSpec("dev-1", DeviceClass.THERMOSTAT, "Label"), HumanGating.NONE);
        var result = provisioner.deprovision(node, deprovisionContext());

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
    }

    @Test
    void noProviderForDevice_returnsFailed() {
        var device = SwitchDevice.builder()
            .deviceId("sw-1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("unknown-provider")
            .on(false).build();
        var registry = singleDeviceRegistry(device);
        var provisioner = new IoTNodeProvisioner(registry, List.of());

        var node = configNode("sw-1", DeviceClass.SWITCH, Map.of("isOn", true));
        var result = provisioner.provision(node, context());

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
        assertThat(((ProvisionResult.Failed) result).reason()).contains("no provider");
    }

    @Test
    void handledTypes_includesReviewType() {
        var provisioner = provisioner(null, new ArrayList<>(), CommandResult.SENT);
        assertThat(provisioner.handledTypes()).contains(NodeType.of("iot-review"));
    }

    @Test
    void reviewProvision_returnsSuccess() {
        var provisioner = provisioner(null, new ArrayList<>(), CommandResult.SENT);
        var spec = new IoTReviewSpec(NodeId.of("dev-1-config"), "test reason");
        var node = new DesiredNode(NodeId.of("review-dev-1-config"), spec, HumanGating.ALL);
        var result = provisioner.provision(node, context());

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    }

    @Test
    void reviewDeprovision_returnsSuccess() {
        var provisioner = provisioner(null, new ArrayList<>(), CommandResult.SENT);
        var spec = new IoTReviewSpec(NodeId.of("dev-1-config"), "test reason");
        var node = new DesiredNode(NodeId.of("review-dev-1-config"), spec, HumanGating.ALL);
        var result = provisioner.deprovision(node, deprovisionContext());

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
    }

    private SwitchDevice switchDevice(String id, boolean on) {
        return SwitchDevice.builder()
            .deviceId(id).deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t").providerId("test-provider")
            .on(on).build();
    }

    private DesiredNode configNode(String id, DeviceClass dc, Map<String, Object> caps) {
        return new DesiredNode(NodeId.of(id + "-config"), new DeviceConfigSpec(id, dc, caps), HumanGating.NONE);
    }

    private ProvisionContext context() {
        return new ProvisionContext("default", FACTORY.empty());
    }

    private DeprovisionContext deprovisionContext() {
        return new DeprovisionContext("default", FACTORY.empty());
    }

    private IoTNodeProvisioner provisioner(DeviceEntity device,
                                            List<DeviceCommand> dispatched,
                                            CommandResult dispatchResult) {
        var registry = device != null ? singleDeviceRegistry(device) : emptyRegistry();
        var provider = new DeviceProvider() {
            public String providerId() { return "test-provider"; }
            public List<DeviceEntity> discover() { return List.of(); }
            public CommandResult dispatch(DeviceCommand cmd) {
                dispatched.add(cmd);
                return dispatchResult;
            }
            public ProviderStatus status() { return ProviderStatus.CONNECTED; }
        };
        return new IoTNodeProvisioner(registry, List.of(provider));
    }

    private DeviceRegistry singleDeviceRegistry(DeviceEntity device) {
        return new DeviceRegistry() {
            public Optional<DeviceEntity> findById(String id) {
                return device.deviceId().equals(id) ? Optional.of(device) : Optional.empty();
            }
            public <T extends DeviceEntity> List<T> findByClass(Class<T> c) { return List.of(); }
            public List<DeviceEntity> findByTenancyId(String t) { return List.of(); }
            public List<DeviceEntity> findAll() { return List.of(device); }
            public void refresh() { }
            public void refresh(String providerId) { }
        };
    }

    private DeviceRegistry emptyRegistry() {
        return new DeviceRegistry() {
            public Optional<DeviceEntity> findById(String id) { return Optional.empty(); }
            public <T extends DeviceEntity> List<T> findByClass(Class<T> c) { return List.of(); }
            public List<DeviceEntity> findByTenancyId(String t) { return List.of(); }
            public List<DeviceEntity> findAll() { return List.of(); }
            public void refresh() { }
            public void refresh(String providerId) { }
        };
    }
}

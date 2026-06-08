package io.casehub.iot.testing;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.spi.DeviceProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Not thread-safe — designed for sequential test use only. */
public class MockDeviceProvider implements DeviceProvider {

    private final String providerId;
    private final Map<String, DeviceEntity> devices = new LinkedHashMap<>();
    private final List<DeviceCommand> dispatchedCommands = new ArrayList<>();
    private ProviderStatus status = ProviderStatus.CONNECTED;
    private CommandResult dispatchResult = CommandResult.SENT;

    public MockDeviceProvider(String providerId) {
        this.providerId = providerId;
    }

    @Override
    public String providerId() { return providerId; }

    @Override
    public List<DeviceEntity> discover() {
        return List.copyOf(devices.values());
    }

    @Override
    public CommandResult dispatch(DeviceCommand command) {
        dispatchedCommands.add(command);
        return dispatchResult;
    }

    @Override
    public ProviderStatus status() { return status; }

    public void addDevice(DeviceEntity device) {
        devices.put(device.deviceId(), device);
    }

    public void removeDevice(String deviceId) {
        devices.remove(deviceId);
    }

    public void clear() {
        devices.clear();
    }

    public void setStatus(ProviderStatus status) {
        this.status = status;
    }

    public void setDispatchResult(CommandResult dispatchResult) {
        this.dispatchResult = dispatchResult;
    }

    public List<DeviceCommand> dispatchedCommands() {
        return Collections.unmodifiableList(dispatchedCommands);
    }

    public void clearDispatchedCommands() {
        dispatchedCommands.clear();
    }
}

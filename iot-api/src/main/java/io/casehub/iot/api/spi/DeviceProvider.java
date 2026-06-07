package io.casehub.iot.api.spi;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.ProviderStatus;
import java.util.List;

public interface DeviceProvider {
    String providerId();
    List<DeviceEntity> discover();
    CommandResult dispatch(DeviceCommand command);
    ProviderStatus status();
}

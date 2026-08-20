package io.casehub.iot.mcp;

import io.casehub.iot.api.StateChangeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

@ApplicationScoped
public class IoTStateChangeResourceObserver {

    @Inject
    IoTResourceRegistrar registrar;

    void onStateChange(@ObservesAsync StateChangeEvent event) {
        registrar.addChange(StateChangeSummary.from(event));

        String deviceId = event.after().deviceId();
        registrar.notifyDeviceUpdate(deviceId);
        registrar.notifyChangesUpdate();
    }
}

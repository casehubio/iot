package io.casehub.iot.spi;

import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.jboss.logging.Logger;

@ApplicationScoped
@DefaultBean
public class CdiDeviceRegistry implements DeviceRegistry {

    private static final Logger LOG = Logger.getLogger(CdiDeviceRegistry.class);

    @Inject @Any
    Instance<DeviceProvider> providers;

    private volatile Map<String, DeviceEntity> devices = Map.of();

    void onStartup(@Observes StartupEvent event) {
        refresh().await().indefinitely();
    }

    @Override
    public Uni<Void> refresh() {
        return Uni.join().all(
                StreamSupport.stream(providers.spliterator(), false)
                    .map(p -> p.discover()
                        .onFailure().invoke(e -> LOG.warnf(e, "Provider %s failed during discover", p.providerId()))
                        .onFailure().recoverWithItem(List.of()))
                    .toList()
            ).andCollectFailures()
            .map(listOfLists -> {
                synchronized (CdiDeviceRegistry.this) {
                    Map<String, DeviceEntity> next = new HashMap<>();
                    listOfLists.forEach(list -> list.forEach(d -> next.put(d.deviceId(), d)));
                    devices = Map.copyOf(next);
                }
                return (Void) null;
            });
    }

    void onStateChange(@ObservesAsync StateChangeEvent event) {
        updateDevice(event.after());
    }

    private synchronized void updateDevice(DeviceEntity device) {
        Map<String, DeviceEntity> current = devices;
        Map<String, DeviceEntity> next = new HashMap<>(current);
        next.put(device.deviceId(), device);
        devices = Map.copyOf(next);
    }

    @Override
    public Optional<DeviceEntity> findById(String deviceId) {
        return Optional.ofNullable(devices.get(deviceId));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends DeviceEntity> List<T> findByClass(Class<T> deviceClass) {
        return devices.values().stream()
            .filter(deviceClass::isInstance)
            .map(d -> (T) d)
            .toList();
    }

    @Override
    public List<DeviceEntity> findByTenancyId(String tenancyId) {
        return devices.values().stream()
            .filter(d -> d.tenancyId().equals(tenancyId))
            .toList();
    }

    @Override
    public List<DeviceEntity> findAll() {
        return List.copyOf(devices.values());
    }
}

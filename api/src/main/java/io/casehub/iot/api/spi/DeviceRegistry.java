package io.casehub.iot.api.spi;

import io.casehub.iot.api.DeviceEntity;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Optional;

public interface DeviceRegistry {
    Optional<DeviceEntity> findById(String deviceId);
    <T extends DeviceEntity> List<T> findByClass(Class<T> deviceClass);
    List<DeviceEntity> findByTenancyId(String tenancyId);
    List<DeviceEntity> findAll();
    Uni<Void> refresh();
    Uni<Void> refresh(String providerId);
}

package io.casehub.iot.homeassistant;

import io.casehub.iot.homeassistant.internal.HaAreaDto;
import io.casehub.iot.homeassistant.internal.HaDeviceRegistryDto;
import io.casehub.iot.homeassistant.internal.HaEntityRegistryDto;
import io.casehub.iot.homeassistant.internal.HaFloorDto;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HomeAssistantLocationResolver {

    private HomeAssistantLocationResolver() {}

    public static Map<String, String> resolve(final List<HaAreaDto> areas,
                                               final List<HaFloorDto> floors,
                                               final List<HaEntityRegistryDto> entities,
                                               final List<HaDeviceRegistryDto> devices,
                                               final String prefix) {
        final Map<String, HaAreaDto> areaById = new HashMap<>();
        for (final HaAreaDto area : areas) {
            areaById.put(area.areaId(), area);
        }

        final Map<String, String> floorById = new HashMap<>();
        for (final HaFloorDto floor : floors) {
            floorById.put(floor.floorId(), slugify(floor.name()));
        }

        final Map<String, String> deviceAreaById = new HashMap<>();
        for (final HaDeviceRegistryDto device : devices) {
            if (device.areaId() != null) {
                deviceAreaById.put(device.id(), device.areaId());
            }
        }

        final Map<String, String> result = new HashMap<>();
        for (final HaEntityRegistryDto entity : entities) {
            final String areaId = entity.areaId() != null
                    ? entity.areaId()
                    : (entity.deviceId() != null ? deviceAreaById.get(entity.deviceId()) : null);

            if (areaId == null) {
                continue;
            }

            final HaAreaDto area = areaById.get(areaId);
            if (area == null) {
                continue;
            }

            final String path = buildPath(area, floorById, prefix);
            result.put(entity.entityId(), path);
        }

        return Map.copyOf(result);
    }

    private static String buildPath(final HaAreaDto area, final Map<String, String> floorById,
                                     final String prefix) {
        final StringBuilder sb = new StringBuilder();
        if (prefix != null && !prefix.isEmpty()) {
            sb.append(prefix);
        }
        if (area.floorId() != null) {
            final String floorSlug = floorById.get(area.floorId());
            if (floorSlug != null) {
                if (!sb.isEmpty()) {
                    sb.append('/');
                }
                sb.append(floorSlug);
            }
        }
        if (!sb.isEmpty()) {
            sb.append('/');
        }
        sb.append(slugify(area.name()));
        return sb.toString();
    }

    static String slugify(final String name) {
        return name.toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", "-");
    }
}

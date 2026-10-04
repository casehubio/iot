package io.casehub.iot.openhab;

import io.casehub.iot.openhab.internal.OpenHabItemDto;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class OpenHabLocationResolver {

    private OpenHabLocationResolver() {}

    public static Map<String, String> resolve(final List<OpenHabItemDto> locationItems) {
        final Map<String, String> result = new HashMap<>();
        for (final OpenHabItemDto item : locationItems) {
            walkTree(item, null, result);
        }
        return Map.copyOf(result);
    }

    private static void walkTree(final OpenHabItemDto item, final String parentPath,
                                 final Map<String, String> result) {
        final List<String> tags = item.tags() != null ? item.tags() : List.of();
        final boolean isLocation = tags.stream().anyMatch("Location"::equals);
        final boolean isEquipment = tags.stream().anyMatch("Equipment"::equals);

        if (isLocation) {
            final String currentPath = parentPath != null
                    ? parentPath + "/" + item.label()
                    : item.label();
            final List<OpenHabItemDto> members = item.members() != null ? item.members() : List.of();
            for (final OpenHabItemDto member : members) {
                walkTree(member, currentPath, result);
            }
        } else if (isEquipment && parentPath != null) {
            result.put(item.name(), parentPath);
        }
    }
}

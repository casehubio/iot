package io.casehub.iot.openhab.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenHabChannelDto(
    String uid,
    String id,
    String channelTypeUID,
    String itemType,
    String kind,
    List<String> linkedItems,
    List<String> defaultTags
) {
    public boolean isStateChannel() {
        return !"TRIGGER".equals(kind);
    }

    public boolean isSetpointChannel() {
        if (defaultTags != null && defaultTags.stream().anyMatch(t -> "Setpoint".equalsIgnoreCase(t))) {
            return true;
        }
        String lower = ((channelTypeUID != null ? channelTypeUID : "") + " " + id)
                .toLowerCase(Locale.ROOT);
        return lower.contains("setpoint") || lower.contains("target") || lower.contains("desired");
    }
}

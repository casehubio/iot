package io.casehub.iot.openhab;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * OpenHAB HSBType for color lights.
 * Represents color in HSB (Hue-Saturation-Brightness) color space.
 *
 * @param hue        Hue value (0-360 degrees)
 * @param saturation Saturation percentage (0-100)
 * @param brightness Brightness percentage (0-100)
 */
public record OpenHabHsbType(
        BigDecimal hue,
        BigDecimal saturation,
        BigDecimal brightness
) {
    public OpenHabHsbType {
        Objects.requireNonNull(hue, "hue");
        Objects.requireNonNull(saturation, "saturation");
        Objects.requireNonNull(brightness, "brightness");
    }
}

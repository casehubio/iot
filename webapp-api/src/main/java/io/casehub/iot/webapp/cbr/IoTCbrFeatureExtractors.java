package io.casehub.iot.webapp.cbr;

import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ReadableLayer;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public final class IoTCbrFeatureExtractors {

    private IoTCbrFeatureExtractors() {}

    public static Map<String, Object> extractHvacAnomalyFeatures(CaseContext ctx) {
        var working = ctx.layer("working");
        var features = new LinkedHashMap<String, Object>();
        extractCommonFeatures(features, working);
        putNumericIfPresent(features, "temperatureDelta", working);
        putStringIfPresent(features, "outdoorTemperatureRange", working);
        return Map.copyOf(features);
    }

    public static Map<String, Object> extractSafetyAlertFeatures(CaseContext ctx) {
        var working = ctx.layer("working");
        var features = new LinkedHashMap<String, Object>();
        extractCommonFeatures(features, working);
        putStringIfPresent(features, "alertType", working);
        return Map.copyOf(features);
    }

    public static Map<String, Object> extractSecurityAlertFeatures(CaseContext ctx) {
        var working = ctx.layer("working");
        var features = new LinkedHashMap<String, Object>();
        extractCommonFeatures(features, working);
        putStringIfPresent(features, "entryPoint", working);
        return Map.copyOf(features);
    }

    public static Map<String, Object> extractGenericResponseFeatures(CaseContext ctx) {
        var working = ctx.layer("working");
        var features = new LinkedHashMap<String, Object>();
        extractCommonFeatures(features, working);
        return Map.copyOf(features);
    }

    private static void extractCommonFeatures(Map<String, Object> features, ReadableLayer working) {
        putStringIfPresent(features, "deviceClass", working);
        putStringIfPresent(features, "roomType", working);
        deriveTemporalFeatures(features, working);
    }

    private static void deriveTemporalFeatures(Map<String, Object> features, ReadableLayer working) {
        Object tsRaw = working.get("eventTimestamp");
        if (tsRaw == null) return;

        Instant instant;
        if (tsRaw instanceof Instant i) {
            instant = i;
        } else if (tsRaw instanceof String s) {
            instant = Instant.parse(s);
        } else {
            return;
        }

        ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);
        features.put("hourOfDay", (double) zdt.getHour());

        DayOfWeek dow = zdt.getDayOfWeek();
        features.put("dayType", (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY)
                ? "weekend" : "weekday");

        features.put("season", deriveSeason(zdt.getMonthValue()));
    }

    private static String deriveSeason(int month) {
        return switch (month) {
            case 3, 4, 5 -> "spring";
            case 6, 7, 8 -> "summer";
            case 9, 10, 11 -> "autumn";
            default -> "winter";
        };
    }

    private static void putStringIfPresent(Map<String, Object> features, String key,
                                           ReadableLayer working) {
        Object value = working.get(key);
        if (value instanceof String s && !s.isBlank()) {
            features.put(key, s);
        }
    }

    private static void putNumericIfPresent(Map<String, Object> features, String key,
                                            ReadableLayer working) {
        Object value = working.get(key);
        if (value instanceof Number n) {
            features.put(key, n.doubleValue());
        }
    }
}

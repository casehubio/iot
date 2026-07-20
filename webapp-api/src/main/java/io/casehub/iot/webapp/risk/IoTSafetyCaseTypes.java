package io.casehub.iot.webapp.risk;

import java.util.Set;

public final class IoTSafetyCaseTypes {

    public static final Set<String> SAFETY_CASE_TYPES = Set.of("safety-alert");

    public static final Set<String> SAFETY_SITUATION_IDS = Set.of(
            "smoke-detected", "co-detected", "water-leak-detected");

    private IoTSafetyCaseTypes() {}
}

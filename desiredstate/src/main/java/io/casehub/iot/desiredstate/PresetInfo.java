package io.casehub.iot.desiredstate;

import java.util.List;

public record PresetInfo(String name, List<String> imports, int deviceCount) {
    public PresetInfo {
        imports = imports != null ? List.copyOf(imports) : List.of();
    }
}

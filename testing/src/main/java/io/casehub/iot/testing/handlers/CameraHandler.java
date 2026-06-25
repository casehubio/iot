package io.casehub.iot.testing.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.iot.api.CameraDevice;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.testing.DeviceFixtureDefaults;
import io.casehub.iot.testing.DeviceTypeHandler;

public final class CameraHandler implements DeviceTypeHandler {

    @Override public String typeName() { return "camera"; }
    @Override public DeviceClass deviceClass() { return DeviceClass.CAMERA; }

    @Override
    public DeviceEntity fromYaml(JsonNode node, DeviceFixtureDefaults defaults) {
        CameraDevice.Builder builder = CameraDevice.builder();
        DeviceTypeHandler.applyCommonFields(builder, node, defaults, deviceClass());
        builder.streaming(node.has("streaming") && node.get("streaming").asBoolean());
        return builder.build();
    }
}

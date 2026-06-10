package io.casehub.iot.openhab;

import io.casehub.iot.api.CoverDevice;

import java.util.Map;
import java.util.Optional;

/**
 * OpenHAB supplement for {@link CoverDevice}.
 * Adds OpenHAB-specific field: upDown directional command state.
 */
public class OpenHabRollershutter extends CoverDevice {

    public static final String CAP_UP_DOWN = "upDown";

    private final OpenHabUpDownType upDown;

    protected OpenHabRollershutter(AbstractBuilder<?, ?> builder) {
        super(builder);
        this.upDown = builder.upDown;
    }

    public Optional<OpenHabUpDownType> upDown() {
        return Optional.ofNullable(upDown);
    }

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> caps = super.capabilities();
        caps.put(CAP_UP_DOWN, upDown);
        return caps;
    }

    public Builder toBuilder() {
        return new Builder()
                .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
                .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId())
                .position(position().orElse(null))
                .moving(isMoving())
                .upDown(upDown);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends AbstractBuilder<OpenHabRollershutter, Builder> {
        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public OpenHabRollershutter build() {
            return new OpenHabRollershutter(this);
        }
    }

    public abstract static class AbstractBuilder<T extends OpenHabRollershutter, B extends AbstractBuilder<T, B>>
            extends CoverDevice.AbstractBuilder<T, B> {
        OpenHabUpDownType upDown;

        public B upDown(OpenHabUpDownType upDown) {
            this.upDown = upDown;
            return self();
        }
    }
}

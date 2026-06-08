package io.casehub.iot.api;

import java.util.Map;

public class CoverDevice extends DeviceEntity {

    public static final String CAP_POSITION = "position";
    public static final String CAP_MOVING = "isMoving";

    private final int position;
    private final boolean moving;

    protected CoverDevice(AbstractBuilder<?, ?> builder) {
        super(builder);
        this.position = builder.position;
        this.moving = builder.moving;
    }

    public int position() {
        return position;
    }

    public boolean isMoving() {
        return moving;
    }

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> caps = super.capabilities();
        caps.put(CAP_POSITION, position);
        caps.put(CAP_MOVING, moving);
        return caps;
    }

    public CoverDevice.Builder toBuilder() {
        return new Builder()
            .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
            .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId())
            .position(position).moving(moving);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends AbstractBuilder<CoverDevice, Builder> {
        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public CoverDevice build() {
            return new CoverDevice(this);
        }
    }

    public abstract static class AbstractBuilder<T extends CoverDevice, B extends AbstractBuilder<T, B>>
            extends DeviceEntity.Builder<T, B> {
        int position;
        boolean moving;

        public B position(int position) {
            this.position = position;
            return self();
        }

        public B moving(boolean moving) {
            this.moving = moving;
            return self();
        }
    }
}

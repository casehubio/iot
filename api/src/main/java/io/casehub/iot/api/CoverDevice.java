package io.casehub.iot.api;

import java.util.Map;
import java.util.Optional;

public class CoverDevice extends DeviceEntity {

    public static final String CAP_POSITION = "position";
    public static final String CAP_MOVING = "isMoving";

    private final Integer position;
    private final boolean moving;

    protected CoverDevice(AbstractBuilder<?, ?> builder) {
        super(builder);
        this.position = builder.position;
        this.moving = builder.moving;
    }

    public Optional<Integer> position() {
        return Optional.ofNullable(position);
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
        Integer position;
        boolean moving;

        public B position(Integer position) {
            this.position = position;
            return self();
        }

        public B moving(boolean moving) {
            this.moving = moving;
            return self();
        }
    }
}

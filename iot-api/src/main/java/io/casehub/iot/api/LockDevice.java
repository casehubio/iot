package io.casehub.iot.api;

public class LockDevice extends DeviceEntity {

    public static final String CAP_LOCKED = "isLocked";

    private final boolean locked;

    protected LockDevice(AbstractBuilder<?, ?> builder) {
        super(builder);
        this.locked = builder.locked;
    }

    public boolean isLocked() {
        return locked;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends AbstractBuilder<LockDevice, Builder> {
        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public LockDevice build() {
            return new LockDevice(this);
        }
    }

    public abstract static class AbstractBuilder<T extends LockDevice, B extends AbstractBuilder<T, B>>
            extends DeviceEntity.Builder<T, B> {
        boolean locked;

        public B locked(boolean locked) {
            this.locked = locked;
            return self();
        }
    }
}

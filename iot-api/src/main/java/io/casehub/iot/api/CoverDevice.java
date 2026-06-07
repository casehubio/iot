package io.casehub.iot.api;

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

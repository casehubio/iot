package io.casehub.iot.api;

import java.util.Optional;

public class FanDevice extends DeviceEntity {

    public static final String CAP_ON = "isOn";
    public static final String CAP_SPEED = "speed";

    private final boolean on;
    private final Integer speed;

    private FanDevice(Builder builder) {
        super(builder);
        this.on = builder.on;
        this.speed = builder.speed;
    }

    public boolean isOn() {
        return on;
    }

    public Optional<Integer> speed() {
        return Optional.ofNullable(speed);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends DeviceEntity.Builder<FanDevice, Builder> {
        private boolean on;
        private Integer speed;

        public Builder on(boolean on) {
            this.on = on;
            return this;
        }

        public Builder speed(Integer speed) {
            this.speed = speed;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public FanDevice build() {
            return new FanDevice(this);
        }
    }
}

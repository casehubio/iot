package io.casehub.iot.api;

import java.time.Instant;
import java.util.Objects;

public class PresenceSensor extends DeviceEntity {

    public static final String CAP_PRESENT = "isPresent";
    public static final String CAP_LAST_SEEN = "lastSeen";

    private final boolean present;
    private final Instant lastSeen;

    private PresenceSensor(Builder builder) {
        super(builder);
        this.present = builder.present;
        this.lastSeen = Objects.requireNonNull(builder.lastSeen, "lastSeen");
    }

    public boolean isPresent() {
        return present;
    }

    public Instant lastSeen() {
        return lastSeen;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends DeviceEntity.Builder<PresenceSensor, Builder> {
        private boolean present;
        private Instant lastSeen;

        public Builder present(boolean present) {
            this.present = present;
            return this;
        }

        public Builder lastSeen(Instant lastSeen) {
            this.lastSeen = lastSeen;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public PresenceSensor build() {
            return new PresenceSensor(this);
        }
    }
}

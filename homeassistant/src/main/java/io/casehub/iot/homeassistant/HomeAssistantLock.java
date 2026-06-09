package io.casehub.iot.homeassistant;

import io.casehub.iot.api.LockDevice;

import java.util.Map;
import java.util.Optional;

/**
 * Home Assistant supplement for {@link LockDevice}.
 * Adds HA-specific fields: who changed the lock and which code slot was used.
 */
public class HomeAssistantLock extends LockDevice {

    public static final String CAP_CHANGED_BY = "changedBy";
    public static final String CAP_CODE_SLOT = "codeSlot";

    private final String changedBy;
    private final Integer codeSlot;

    protected HomeAssistantLock(AbstractBuilder<?, ?> builder) {
        super(builder);
        this.changedBy = builder.changedBy;
        this.codeSlot = builder.codeSlot;
    }

    public Optional<String> changedBy() {
        return Optional.ofNullable(changedBy);
    }

    public Optional<Integer> codeSlot() {
        return Optional.ofNullable(codeSlot);
    }

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> caps = super.capabilities();
        caps.put(CAP_CHANGED_BY, changedBy);
        caps.put(CAP_CODE_SLOT, codeSlot);
        return caps;
    }

    public Builder toBuilder() {
        return new Builder()
                .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
                .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId())
                .locked(isLocked())
                .changedBy(changedBy).codeSlot(codeSlot);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends AbstractBuilder<HomeAssistantLock, Builder> {
        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public HomeAssistantLock build() {
            return new HomeAssistantLock(this);
        }
    }

    public abstract static class AbstractBuilder<T extends HomeAssistantLock, B extends AbstractBuilder<T, B>>
            extends LockDevice.AbstractBuilder<T, B> {
        String changedBy;
        Integer codeSlot;

        public B changedBy(String changedBy) {
            this.changedBy = changedBy;
            return self();
        }

        public B codeSlot(Integer codeSlot) {
            this.codeSlot = codeSlot;
            return self();
        }
    }
}

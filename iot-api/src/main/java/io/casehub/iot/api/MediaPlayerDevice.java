package io.casehub.iot.api;

import java.util.Optional;

public class MediaPlayerDevice extends DeviceEntity {

    public static final String CAP_PLAYING = "isPlaying";
    public static final String CAP_VOLUME = "volume";

    private final boolean playing;
    private final Integer volume;

    private MediaPlayerDevice(Builder builder) {
        super(builder);
        this.playing = builder.playing;
        this.volume = builder.volume;
    }

    public boolean isPlaying() {
        return playing;
    }

    public Optional<Integer> volume() {
        return Optional.ofNullable(volume);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends DeviceEntity.Builder<MediaPlayerDevice, Builder> {
        private boolean playing;
        private Integer volume;

        public Builder playing(boolean playing) {
            this.playing = playing;
            return this;
        }

        public Builder volume(Integer volume) {
            this.volume = volume;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public MediaPlayerDevice build() {
            return new MediaPlayerDevice(this);
        }
    }
}

package com.shortscreator;

public record AudioTrack(int streamIndex, int audioIndex, String codec, int channels, String language, String title) {
    public String displayName() {
        StringBuilder builder = new StringBuilder();
        builder.append("Audio ").append(audioIndex + 1)
                .append("  stream ").append(streamIndex);
        if (!language.isBlank()) {
            builder.append("  ").append(language);
        }
        if (!title.isBlank()) {
            builder.append("  ").append(title);
        }
        if (!codec.isBlank()) {
            builder.append("  ").append(codec);
        }
        if (channels > 0) {
            builder.append("  ").append(channels).append("ch");
        }
        return builder.toString();
    }
}

package com.shortscreator;

public record VideoInfo(int width, int height, double durationSeconds) {
    public boolean isWideForShorts() {
        return width / (double) height > 9.0 / 16.0;
    }
}

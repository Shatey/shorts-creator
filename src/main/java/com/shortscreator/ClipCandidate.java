package com.shortscreator;

public record ClipCandidate(int index, double startSeconds, double durationSeconds, double score) {
    public double endSeconds() {
        return startSeconds + durationSeconds;
    }

    public String displayRange() {
        return formatTime(startSeconds) + " - " + formatTime(endSeconds()) + " (" + Math.round(durationSeconds) + "s)";
    }

    public static String formatTime(double seconds) {
        int total = Math.max(0, (int) Math.round(seconds));
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%d:%02d", m, s);
    }
}

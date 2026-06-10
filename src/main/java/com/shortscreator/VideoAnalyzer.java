package com.shortscreator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.DoubleConsumer;

public final class VideoAnalyzer {
    private static final double BUCKET_SECONDS = 0.5;
    public static final int ALL_MOMENTS = 0;
    public static final int MIN_CLIP_SECONDS = 15;

    private final FfmpegService ffmpeg;

    public VideoAnalyzer(FfmpegService ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    public List<ClipCandidate> findCandidates(Path inputFile, int requestedCount, int clipDurationSeconds,
                                              DoubleConsumer progress)
            throws IOException, InterruptedException {
        double duration = ffmpeg.readVideoInfo(inputFile).durationSeconds();
        boolean detectAllMoments = requestedCount == ALL_MOMENTS;
        if (clipDurationSeconds < MIN_CLIP_SECONDS) {
            throw new IOException("Maximum clip length must be at least " + MIN_CLIP_SECONDS + " seconds.");
        }
        if (duration < MIN_CLIP_SECONDS) {
            throw new IOException("Video is too short to create a " + MIN_CLIP_SECONDS + "-second short.");
        }

        List<Double> rms = ffmpeg.readAudioRms(inputFile, BUCKET_SECONDS, seconds -> {
            if (progress != null) {
                progress.accept(Math.min(0.9, seconds / duration));
            }
        });

        if (rms.isEmpty()) {
            throw new IOException("No audio samples found. This MVP needs an audio track for highlight detection.");
        }

        double average = rms.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = rms.stream()
                .mapToDouble(value -> (value - average) * (value - average))
                .average()
                .orElse(0);
        double stdDev = Math.sqrt(variance);
        double activityFloor = Math.max(average + stdDev * 0.15, average * 1.12);
        List<ScoredMoment> moments = new ArrayList<>();
        int maxBuckets = Math.max(1, (int) Math.round(clipDurationSeconds / BUCKET_SECONDS));
        int minBuckets = Math.min(maxBuckets, Math.max(1, (int) Math.round(MIN_CLIP_SECONDS / BUCKET_SECONDS)));
        int contextBuckets = Math.max(1, (int) Math.round(1.5 / BUCKET_SECONDS));

        for (int i = 0; i < rms.size(); i++) {
            int activeFrom = i;
            int activeTo = i + 1;
            while (activeFrom > 0
                    && i - activeFrom < maxBuckets / 2
                    && rms.get(activeFrom - 1) >= activityFloor) {
                activeFrom--;
            }
            while (activeTo < rms.size()
                    && activeTo - i < maxBuckets / 2
                    && rms.get(activeTo) >= activityFloor) {
                activeTo++;
            }

            int from = Math.max(0, activeFrom - contextBuckets);
            int to = Math.min(rms.size(), activeTo + contextBuckets);
            while (to - from < minBuckets && to - from < maxBuckets && (from > 0 || to < rms.size())) {
                if (from > 0) {
                    from--;
                }
                if (to - from >= minBuckets || to - from >= maxBuckets) {
                    break;
                }
                if (to < rms.size()) {
                    to++;
                }
            }
            if (to - from > maxBuckets) {
                int extra = (to - from) - maxBuckets;
                int trimLeft = Math.min(extra / 2, i - from);
                from += trimLeft;
                to -= extra - trimLeft;
            }
            if (to - from < minBuckets) {
                int[] expanded = expandRange(from, to, minBuckets, maxBuckets, rms.size(), i);
                from = expanded[0];
                to = expanded[1];
            }

            double energy = 0;
            double peak = 0;
            for (int j = from; j < to; j++) {
                double value = rms.get(j);
                energy += value;
                peak = Math.max(peak, value);
            }
            double localAverage = energy / Math.max(1, to - from);
            double spike = Math.max(0, peak - average);
            double score = localAverage * 0.7 + spike * 1.3 + ((activeTo - activeFrom) / (double) maxBuckets) * average;
            double clipDuration = Math.min((to - from) * BUCKET_SECONDS, clipDurationSeconds);
            clipDuration = Math.max(MIN_CLIP_SECONDS, clipDuration);
            double start = clamp(from * BUCKET_SECONDS, 0, Math.max(0, duration - clipDuration));
            moments.add(new ScoredMoment(start, clipDuration, score));
        }

        moments.sort(Comparator.comparingDouble(ScoredMoment::score).reversed());
        List<ClipCandidate> selected = new ArrayList<>();

        for (ScoredMoment moment : moments) {
            boolean overlaps = selected.stream()
                    .anyMatch(existing -> rangesOverlap(existing.startSeconds(), existing.endSeconds(),
                            moment.startSeconds(), moment.endSeconds()));
            if (!overlaps) {
                selected.add(new ClipCandidate(selected.size() + 1, moment.startSeconds(), moment.durationSeconds(), moment.score()));
            }
            if (!detectAllMoments && selected.size() >= requestedCount) {
                break;
            }
        }

        selected.sort(Comparator.comparingDouble(ClipCandidate::startSeconds));
        List<ClipCandidate> numbered = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            ClipCandidate clip = selected.get(i);
            numbered.add(new ClipCandidate(i + 1, clip.startSeconds(), clip.durationSeconds(), clip.score()));
        }

        if (progress != null) {
            progress.accept(1.0);
        }
        return numbered;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean rangesOverlap(double aStart, double aEnd, double bStart, double bEnd) {
        double gap = 2.0;
        return aStart < bEnd + gap && bStart < aEnd + gap;
    }

    private int[] expandRange(int from, int to, int minBuckets, int maxBuckets, int totalBuckets, int anchor) {
        while (to - from < minBuckets && to - from < maxBuckets && (from > 0 || to < totalBuckets)) {
            boolean growLeft = from > 0 && (to >= totalBuckets || anchor - from <= to - anchor);
            if (growLeft) {
                from--;
            } else if (to < totalBuckets) {
                to++;
            } else {
                from--;
            }
        }
        return new int[]{from, to};
    }

    private record ScoredMoment(double startSeconds, double durationSeconds, double score) {
        double endSeconds() {
            return startSeconds + durationSeconds;
        }
    }
}

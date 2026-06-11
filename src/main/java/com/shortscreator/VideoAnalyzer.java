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
        return findCandidates(inputFile, requestedCount, MIN_CLIP_SECONDS, clipDurationSeconds, List.of(), progress);
    }

    public List<ClipCandidate> findCandidates(Path inputFile, int requestedCount, int clipDurationSeconds,
                                              List<AudioTrackSelection> audioTrackSelections, DoubleConsumer progress)
            throws IOException, InterruptedException {
        return findCandidates(inputFile, requestedCount, MIN_CLIP_SECONDS, clipDurationSeconds, audioTrackSelections, progress);
    }

    public List<ClipCandidate> findCandidates(Path inputFile, int requestedCount, int clipMinDurationSeconds,
                                              int clipDurationSeconds,
                                              List<AudioTrackSelection> audioTrackSelections, DoubleConsumer progress)
            throws IOException, InterruptedException {
        double duration = ffmpeg.readVideoInfo(inputFile).durationSeconds();
        boolean detectAllMoments = requestedCount == ALL_MOMENTS;
        if (clipMinDurationSeconds < 1) {
            throw new IOException("Minimum clip length must be at least 1 second.");
        }
        if (clipDurationSeconds < clipMinDurationSeconds) {
            throw new IOException("Maximum clip length must be greater than or equal to minimum clip length.");
        }
        if (duration < clipMinDurationSeconds) {
            throw new IOException("Video is too short to create a " + clipMinDurationSeconds + "-second short.");
        }

        List<Double> rms = ffmpeg.readAudioRmsWithSelections(inputFile, BUCKET_SECONDS, audioTrackSelections, seconds -> {
            if (progress != null) {
                progress.accept(Math.min(0.45, seconds / duration * 0.45));
            }
        });

        if (rms.isEmpty()) {
            throw new IOException("No audio samples found. This MVP needs an audio track for highlight detection.");
        }

        double visualBucketSeconds = 2.0;
        List<Double> visualActivity = ffmpeg.readVisualActivity(inputFile, visualBucketSeconds, seconds -> {
            if (progress != null) {
                progress.accept(0.45 + Math.min(0.45, seconds / duration * 0.45));
            }
        });
        List<Double> combined = combineSignals(rms, visualActivity);

        double average = combined.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = combined.stream()
                .mapToDouble(value -> (value - average) * (value - average))
                .average()
                .orElse(0);
        double stdDev = Math.sqrt(variance);
        double activityFloor = Math.max(average + stdDev * 0.18, average * 1.15);
        double keepFloor = Math.max(average + stdDev * 0.06, average * 1.05);
        double peakFloor = Math.max(average + stdDev * 0.45, average * 1.30);
        List<ScoredMoment> moments = new ArrayList<>();
        int maxBuckets = Math.max(1, (int) Math.round(clipDurationSeconds / BUCKET_SECONDS));
        int minBuckets = Math.min(maxBuckets, Math.max(1, (int) Math.round(clipMinDurationSeconds / BUCKET_SECONDS)));
        int contextBuckets = Math.max(2, (int) Math.round(Math.min(4.0, Math.max(2.0, clipMinDurationSeconds * 0.16)) / BUCKET_SECONDS));
        int quietAllowanceBuckets = Math.max(1, (int) Math.round(1.5 / BUCKET_SECONDS));

        for (int i = 0; i < combined.size(); i++) {
            if (!isInterestingPeak(combined, i, peakFloor)) {
                continue;
            }

            int activeFrom = i;
            int activeTo = i + 1;
            activeFrom = expandLeftThroughActivity(combined, activeFrom, i, maxBuckets / 2, activityFloor, keepFloor, quietAllowanceBuckets);
            activeTo = expandRightThroughActivity(combined, activeTo, i, maxBuckets / 2, activityFloor, keepFloor, quietAllowanceBuckets);

            int from = Math.max(0, activeFrom - contextBuckets);
            int to = Math.min(combined.size(), activeTo + contextBuckets);
            while (to - from < minBuckets && to - from < maxBuckets && (from > 0 || to < combined.size())) {
                if (from > 0) {
                    from--;
                }
                if (to - from >= minBuckets || to - from >= maxBuckets) {
                    break;
                }
                if (to < combined.size()) {
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
                int[] expanded = expandRange(from, to, minBuckets, maxBuckets, combined.size(), i);
                from = expanded[0];
                to = expanded[1];
            }

            double score = scoreRange(combined, from, to, average)
                    + scoreRange(normalize(rms), from, Math.min(to, rms.size()), 0) * 0.25
                    + scoreRange(normalize(visualActivity), from, Math.min(to, visualActivity.size()), 0) * 0.35
                    + ((activeTo - activeFrom) / (double) maxBuckets) * average;
            double clipDuration = Math.min((to - from) * BUCKET_SECONDS, clipDurationSeconds);
            clipDuration = Math.max(clipMinDurationSeconds, clipDuration);
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

    private int expandLeftThroughActivity(List<Double> combined, int activeFrom, int anchor, int maxDistance,
                                          double activityFloor, double keepFloor, int quietAllowanceBuckets) {
        int quietBuckets = 0;
        while (activeFrom > 0 && anchor - activeFrom < maxDistance) {
            double value = combined.get(activeFrom - 1);
            if (value >= activityFloor) {
                quietBuckets = 0;
                activeFrom--;
            } else if (value >= keepFloor && quietBuckets < quietAllowanceBuckets) {
                quietBuckets++;
                activeFrom--;
            } else {
                break;
            }
        }
        return activeFrom;
    }

    private int expandRightThroughActivity(List<Double> combined, int activeTo, int anchor, int maxDistance,
                                           double activityFloor, double keepFloor, int quietAllowanceBuckets) {
        int quietBuckets = 0;
        while (activeTo < combined.size() && activeTo - anchor < maxDistance) {
            double value = combined.get(activeTo);
            if (value >= activityFloor) {
                quietBuckets = 0;
                activeTo++;
            } else if (value >= keepFloor && quietBuckets < quietAllowanceBuckets) {
                quietBuckets++;
                activeTo++;
            } else {
                break;
            }
        }
        return activeTo;
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

    private double scoreRange(List<Double> rms, int from, int to, double average) {
        if (to <= from || from >= rms.size()) {
            return 0;
        }
        to = Math.min(to, rms.size());
        double energy = 0;
        double peak = 0;
        for (int i = from; i < to; i++) {
            double value = rms.get(i);
            energy += value;
            peak = Math.max(peak, value);
        }
        double localAverage = energy / Math.max(1, to - from);
        double spike = Math.max(0, peak - average);
        return localAverage * 0.8 + spike * 1.4 + Math.min(0.03, (to - from) * 0.0005);
    }

    private List<Double> combineSignals(List<Double> audio, List<Double> visual) {
        List<Double> normalizedAudio = normalize(audio);
        List<Double> normalizedVisual = normalize(visual);
        int size = normalizedAudio.size();
        List<Double> combined = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            double audioValue = i < normalizedAudio.size() ? normalizedAudio.get(i) : 0;
            double visualValue = valueAtRelativeIndex(normalizedVisual, i, size);
            double synergy = Math.sqrt(Math.max(0, audioValue * visualValue));
            combined.add(audioValue * 0.55 + visualValue * 0.30 + synergy * 0.15);
        }
        return combined;
    }

    private double valueAtRelativeIndex(List<Double> values, int index, int targetSize) {
        if (values.isEmpty()) {
            return 0;
        }
        if (values.size() == 1 || targetSize <= 1) {
            return values.get(0);
        }
        int sourceIndex = (int) Math.round(index * (values.size() - 1) / (double) (targetSize - 1));
        return values.get(Math.max(0, Math.min(values.size() - 1, sourceIndex)));
    }

    private boolean isInterestingPeak(List<Double> combined, int index, double peakFloor) {
        double value = combined.get(index);
        if (value < peakFloor) {
            return false;
        }
        double previous = index > 0 ? combined.get(index - 1) : Double.NEGATIVE_INFINITY;
        double next = index + 1 < combined.size() ? combined.get(index + 1) : Double.NEGATIVE_INFINITY;
        return value >= previous && value >= next;
    }

    private List<Double> normalize(List<Double> values) {
        if (values.isEmpty()) {
            return List.of();
        }
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(min);
        double range = Math.max(0.000001, max - min);
        List<Double> normalized = new ArrayList<>(values.size());
        for (double value : values) {
            normalized.add((value - min) / range);
        }
        return normalized;
    }

    private record ScoredMoment(double startSeconds, double durationSeconds, double score) {
        double endSeconds() {
            return startSeconds + durationSeconds;
        }
    }
}

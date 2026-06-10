package com.shortscreator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.DoubleConsumer;

public final class VideoAnalyzer {
    private static final double BUCKET_SECONDS = 0.5;

    private final FfmpegService ffmpeg;

    public VideoAnalyzer(FfmpegService ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    public List<ClipCandidate> findCandidates(Path inputFile, int requestedCount, int clipDurationSeconds,
                                              DoubleConsumer progress)
            throws IOException, InterruptedException {
        double duration = ffmpeg.readDurationSeconds(inputFile);
        if (duration <= 1) {
            throw new IOException("Video is too short to analyze.");
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
        List<ScoredMoment> moments = new ArrayList<>();
        int windowBuckets = Math.max(1, (int) Math.round(clipDurationSeconds / BUCKET_SECONDS));

        for (int i = 0; i < rms.size(); i++) {
            int from = Math.max(0, i - windowBuckets / 2);
            int to = Math.min(rms.size(), from + windowBuckets);
            double energy = 0;
            double peak = 0;
            for (int j = from; j < to; j++) {
                double value = rms.get(j);
                energy += value;
                peak = Math.max(peak, value);
            }
            double localAverage = energy / Math.max(1, to - from);
            double spike = Math.max(0, peak - average);
            double score = localAverage * 0.7 + spike * 1.3;
            double center = i * BUCKET_SECONDS;
            double start = clamp(center - clipDurationSeconds / 2.0, 0, Math.max(0, duration - clipDurationSeconds));
            moments.add(new ScoredMoment(start, score));
        }

        moments.sort(Comparator.comparingDouble(ScoredMoment::score).reversed());
        List<ClipCandidate> selected = new ArrayList<>();
        double minGap = Math.max(clipDurationSeconds * 0.75, 10);

        for (ScoredMoment moment : moments) {
            boolean overlaps = selected.stream()
                    .anyMatch(existing -> Math.abs(existing.startSeconds() - moment.startSeconds()) < minGap);
            if (!overlaps) {
                selected.add(new ClipCandidate(selected.size() + 1, moment.startSeconds(), clipDurationSeconds, moment.score()));
            }
            if (selected.size() >= requestedCount) {
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

    private record ScoredMoment(double startSeconds, double score) {
    }
}

package com.shortscreator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.DoubleConsumer;

public final class FfmpegService {
    public void verifyInstalled() throws IOException, InterruptedException {
        try {
            ProcessResult ffmpeg = runAndCapture(List.of(ffmpegCommand(), "-version"));
            ProcessResult ffprobe = runAndCapture(List.of(ffprobeCommand(), "-version"));
            if (ffmpeg.isSuccess() && ffprobe.isSuccess()) {
                return;
            }
        } catch (IOException ex) {
            throw missingFfmpegError();
        }
        throw missingFfmpegError();
    }

    public double readDurationSeconds(Path inputFile) throws IOException, InterruptedException {
        ProcessResult result = runAndCapture(List.of(
                ffprobeCommand(),
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                inputFile.toString()
        ));
        if (!result.isSuccess()) {
            throw new IOException("Could not read video duration:\n" + result.output());
        }
        return Double.parseDouble(result.output().trim());
    }

    public List<Double> readAudioRms(Path inputFile, double bucketSeconds, DoubleConsumer progress)
            throws IOException, InterruptedException {
        int sampleRate = 8_000;
        int samplesPerBucket = Math.max(1, (int) Math.round(sampleRate * bucketSeconds));
        List<String> command = List.of(
                ffmpegCommand(),
                "-v", "error",
                "-i", inputFile.toString(),
                "-vn",
                "-ac", "1",
                "-ar", Integer.toString(sampleRate),
                "-f", "s16le",
                "pipe:1"
        );

        ProcessBuilder builder = new ProcessBuilder(command);
        Process process = builder.start();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        Thread errorReader = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                in.transferTo(errors);
            } catch (IOException ignored) {
                // The process result will still surface a non-zero exit code.
            }
        }, "ffmpeg-error-reader");
        errorReader.start();

        List<Double> buckets = new ArrayList<>();
        byte[] buffer = new byte[16_384];
        long sumSquares = 0;
        int samplesInBucket = 0;
        long totalSamples = 0;

        try (InputStream in = process.getInputStream()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                for (int i = 0; i + 1 < read; i += 2) {
                    short sample = (short) ((buffer[i] & 0xff) | (buffer[i + 1] << 8));
                    sumSquares += (long) sample * sample;
                    samplesInBucket++;
                    totalSamples++;

                    if (samplesInBucket >= samplesPerBucket) {
                        buckets.add(Math.sqrt(sumSquares / (double) samplesInBucket) / 32768.0);
                        sumSquares = 0;
                        samplesInBucket = 0;
                        if (progress != null && buckets.size() % 20 == 0) {
                            progress.accept(totalSamples / (double) sampleRate);
                        }
                    }
                }
            }
        }

        int exit = process.waitFor();
        errorReader.join();
        if (exit != 0) {
            String error = errors.toString(StandardCharsets.UTF_8);
            throw new IOException("FFmpeg audio analysis failed:\n" + error);
        }
        if (samplesInBucket > 0) {
            buckets.add(Math.sqrt(sumSquares / (double) samplesInBucket) / 32768.0);
        }
        return buckets;
    }

    public Path exportClip(Path inputFile, Path outputDirectory, ClipCandidate clip, int totalCount)
            throws IOException, InterruptedException {
        Files.createDirectories(outputDirectory);
        String name = String.format(Locale.US, "short_%02d_%s.mp4", clip.index(), safeTime(clip.startSeconds()));
        Path output = outputDirectory.resolve(name);

        List<String> command = List.of(
                ffmpegCommand(),
                "-y",
                "-ss", formatSeconds(clip.startSeconds()),
                "-i", inputFile.toString(),
                "-t", formatSeconds(clip.durationSeconds()),
                "-vf", "scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,setsar=1",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "23",
                "-c:a", "aac",
                "-b:a", "160k",
                "-movflags", "+faststart",
                output.toString()
        );

        ProcessResult result = runAndCapture(command);
        if (!result.isSuccess()) {
            throw new IOException("Clip export failed (" + clip.index() + "/" + totalCount + "):\n" + result.output());
        }
        return output;
    }

    private ProcessResult runAndCapture(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            in.transferTo(out);
        }
        int exit = process.waitFor();
        return new ProcessResult(exit, out.toString(StandardCharsets.UTF_8));
    }

    private String ffmpegCommand() {
        return findTool("ffmpeg");
    }

    private String ffprobeCommand() {
        return findTool("ffprobe");
    }

    private String findTool(String name) {
        String executable = name + ".exe";
        List<Path> localCandidates = List.of(
                Paths.get("ffmpeg", "bin", executable),
                Paths.get("tools", "ffmpeg", "bin", executable),
                Paths.get(executable)
        );
        for (Path candidate : localCandidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }

        Optional<Path> nested = findNestedTool(Paths.get("ffmpeg"), executable)
                .or(() -> findNestedTool(Paths.get("tools", "ffmpeg"), executable));
        if (nested.isPresent()) {
            return nested.get().toString();
        }

        Optional<Path> installed = findInstalledTool(executable);
        if (installed.isPresent()) {
            return installed.get().toString();
        }
        return name;
    }

    private Optional<Path> findNestedTool(Path root, String executable) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (var stream = Files.find(root, 5, (path, attributes) ->
                attributes.isRegularFile() && path.getFileName().toString().equalsIgnoreCase(executable))) {
            return stream
                    .sorted(Comparator.comparing(path -> path.toString().length()))
                    .findFirst();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private Optional<Path> findInstalledTool(String executable) {
        List<String> roots = List.of(
                System.getenv("LOCALAPPDATA"),
                System.getenv("ProgramFiles"),
                System.getenv("ProgramFiles(x86)")
        );

        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }

            Optional<Path> winget = findNestedTool(Paths.get(root, "Microsoft", "WinGet", "Packages"), executable);
            if (winget.isPresent()) {
                return winget;
            }

            Optional<Path> direct = findNestedTool(Paths.get(root, "ffmpeg"), executable);
            if (direct.isPresent()) {
                return direct;
            }
        }
        return Optional.empty();
    }

    private IOException missingFfmpegError() {
        return new IOException("""
                FFmpeg/FFprobe not found.

                Run install-ffmpeg.bat, add FFmpeg to PATH, or unpack FFmpeg into the ffmpeg folder next to the app.
                The app can find ffmpeg\\bin\\ffmpeg.exe and nested folders like ffmpeg\\ffmpeg-...\\bin\\ffmpeg.exe.
                """);
    }

    private String formatSeconds(double seconds) {
        return String.format(Locale.US, "%.3f", Math.max(0, seconds));
    }

    private String safeTime(double seconds) {
        return ClipCandidate.formatTime(seconds).replace(':', '-');
    }
}

package com.shortscreator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;

public final class FfmpegService {
    private static final int FOCUS_SAMPLE_WIDTH = 160;
    private static final int FOCUS_SAMPLE_HEIGHT = 90;
    private final List<Process> runningProcesses = Collections.synchronizedList(new ArrayList<>());

    public void cancelRunningProcesses() {
        List<Process> snapshot;
        synchronized (runningProcesses) {
            snapshot = new ArrayList<>(runningProcesses);
        }
        for (Process process : snapshot) {
            if (process.isAlive()) {
                process.destroy();
            }
        }
    }

    public void verifyInstalled() throws IOException, InterruptedException {
        try {
            ProcessResult ffmpeg = runAndCapture(List.of(ffmpegCommand(), "-version"));
            ProcessResult ffprobe = runAndCapture(List.of(ffprobeCommand(), "-version"));
            ProcessResult ffplay = runAndCapture(List.of(ffplayCommand(), "-version"));
            if (ffmpeg.isSuccess() && ffprobe.isSuccess() && ffplay.isSuccess()) {
                return;
            }
        } catch (IOException ex) {
            throw missingFfmpegError();
        }
        throw missingFfmpegError();
    }

    public double readDurationSeconds(Path inputFile) throws IOException, InterruptedException {
        return readVideoInfo(inputFile).durationSeconds();
    }

    public VideoInfo readVideoInfo(Path inputFile) throws IOException, InterruptedException {
        ProcessResult result = runAndCapture(List.of(
                ffprobeCommand(),
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height:format=duration",
                "-of", "default=noprint_wrappers=1",
                inputFile.toString()
        ));
        if (!result.isSuccess()) {
            throw new IOException("Could not read video info:\n" + result.output());
        }
        int width = 0;
        int height = 0;
        double duration = 0;
        for (String line : result.output().split("\\R")) {
            if (line.startsWith("width=")) {
                width = Integer.parseInt(line.substring("width=".length()).trim());
            } else if (line.startsWith("height=")) {
                height = Integer.parseInt(line.substring("height=".length()).trim());
            } else if (line.startsWith("duration=")) {
                duration = Double.parseDouble(line.substring("duration=".length()).trim());
            }
        }
        if (width <= 0 || height <= 0 || duration <= 0) {
            throw new IOException("Could not read video dimensions/duration:\n" + result.output());
        }
        return new VideoInfo(width, height, duration);
    }

    public List<Double> readAudioRms(Path inputFile, double bucketSeconds, DoubleConsumer progress)
            throws IOException, InterruptedException {
        return readAudioRms(inputFile, bucketSeconds, List.of(), progress);
    }

    public List<Double> readAudioRms(Path inputFile, double bucketSeconds, List<Integer> audioStreamIndexes,
                                     DoubleConsumer progress)
            throws IOException, InterruptedException {
        List<AudioTrackSelection> selections = audioStreamIndexes.stream()
                .map(index -> new AudioTrackSelection(index, 1.0))
                .toList();
        return readAudioRmsWithSelections(inputFile, bucketSeconds, selections, progress);
    }

    public List<Double> readAudioRmsWithSelections(Path inputFile, double bucketSeconds,
                                                   List<AudioTrackSelection> audioTrackSelections,
                                                   DoubleConsumer progress)
            throws IOException, InterruptedException {
        int sampleRate = 8_000;
        int samplesPerBucket = Math.max(1, (int) Math.round(sampleRate * bucketSeconds));
        List<String> command = buildAudioPcmCommand(inputFile, audioTrackSelections, sampleRate);

        ProcessBuilder builder = new ProcessBuilder(command);
        Process process = startManagedProcess(builder);
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

    public List<AudioTrack> readAudioTracks(Path inputFile) throws IOException, InterruptedException {
        ProcessResult result = runAndCapture(List.of(
                ffprobeCommand(),
                "-v", "error",
                "-select_streams", "a",
                "-show_entries", "stream=index,codec_name,channels:stream_tags=language,title",
                "-of", "default=noprint_wrappers=1",
                inputFile.toString()
        ));
        if (!result.isSuccess()) {
            throw new IOException("Could not read audio tracks:\n" + result.output());
        }

        List<AudioTrack> tracks = new ArrayList<>();
        int streamIndex = -1;
        int channels = 0;
        String codec = "";
        String language = "";
        String title = "";

        for (String line : result.output().split("\\R")) {
            if (line.startsWith("index=")) {
                if (streamIndex >= 0) {
                    tracks.add(new AudioTrack(streamIndex, tracks.size(), codec, channels, language, title));
                }
                streamIndex = parseInt(line.substring("index=".length()), -1);
                channels = 0;
                codec = "";
                language = "";
                title = "";
            } else if (line.startsWith("codec_name=")) {
                codec = line.substring("codec_name=".length()).trim();
            } else if (line.startsWith("channels=")) {
                channels = parseInt(line.substring("channels=".length()), 0);
            } else if (line.startsWith("TAG:language=")) {
                language = line.substring("TAG:language=".length()).trim();
            } else if (line.startsWith("TAG:title=")) {
                title = line.substring("TAG:title=".length()).trim();
            }
        }
        if (streamIndex >= 0) {
            tracks.add(new AudioTrack(streamIndex, tracks.size(), codec, channels, language, title));
        }
        return tracks;
    }

    public Process previewAudioTrack(Path inputFile, AudioTrack track) throws IOException {
        return startManagedProcess(new ProcessBuilder(
                ffplayCommand(),
                "-autoexit",
                "-nodisp",
                "-loglevel", "error",
                "-ast", "a:" + track.audioIndex(),
                inputFile.toString()
        ));
    }

    public PreviewSession previewAudioTracks(Path inputFile, List<AudioTrackSelection> selections) throws IOException {
        return previewAudioTracks(inputFile, selections, 0);
    }

    public PreviewSession previewAudioTracks(Path inputFile, List<AudioTrackSelection> selections, double startSeconds) throws IOException {
        if (selections == null || selections.isEmpty()) {
            throw new IOException("Select at least one audio track to preview.");
        }

        ProcessBuilder ffmpegBuilder = new ProcessBuilder(buildAudioPreviewCommand(inputFile, selections, startSeconds));
        ffmpegBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process ffmpeg = startManagedProcess(ffmpegBuilder);

        ProcessBuilder ffplayBuilder = new ProcessBuilder(
                ffplayCommand(),
                "-autoexit",
                "-nodisp",
                "-loglevel", "error",
                "-i", "pipe:0"
        );
        ffplayBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process ffplay = startManagedProcess(ffplayBuilder);

        CompletableFuture<Void> pipeFuture = CompletableFuture.runAsync(() -> {
            try (InputStream in = ffmpeg.getInputStream(); var out = ffplay.getOutputStream()) {
                in.transferTo(out);
            } catch (IOException ignored) {
                // Closing either process while stopping preview is expected.
            }
        });
        CompletableFuture<Void> exitFuture = ffplay.onExit()
                .thenCombine(ffmpeg.onExit(), (left, right) -> null)
                .thenCombine(pipeFuture.exceptionally(ex -> null), (left, right) -> null);
        return new PreviewSession(ffmpeg, ffplay, exitFuture);
    }

    public Path exportClip(Path inputFile, Path outputDirectory, ClipCandidate clip, int totalCount,
                           List<Integer> audioStreamIndexes)
            throws IOException, InterruptedException {
        List<AudioTrackSelection> selections = audioStreamIndexes.stream()
                .map(index -> new AudioTrackSelection(index, 1.0))
                .toList();
        return exportClipWithSelections(inputFile, outputDirectory, clip, totalCount, selections);
    }

    public Path exportClipWithSelections(Path inputFile, Path outputDirectory, ClipCandidate clip, int totalCount,
                                         List<AudioTrackSelection> audioTrackSelections)
            throws IOException, InterruptedException {
        Files.createDirectories(outputDirectory);
        String name = String.format(Locale.US, "short_%02d_%s.mp4", clip.index(), safeTime(clip.startSeconds()));
        Path output = outputDirectory.resolve(name);
        VideoInfo info = readVideoInfo(inputFile);
        double focusX = estimateVisualFocusX(inputFile, clip);

        List<String> command = buildExportCommand(inputFile, output, clip, buildShortsFilter(info, focusX), audioTrackSelections);

        ProcessResult result = runAndCapture(command);
        if (!result.isSuccess()) {
            throw new IOException("Clip export failed (" + clip.index() + "/" + totalCount + "):\n" + result.output());
        }
        return output;
    }

    public Path exportClip(Path inputFile, Path outputDirectory, ClipCandidate clip, int totalCount)
            throws IOException, InterruptedException {
        return exportClip(inputFile, outputDirectory, clip, totalCount, List.of());
    }

    private double estimateVisualFocusX(Path inputFile, ClipCandidate clip) throws IOException, InterruptedException {
        List<String> command = List.of(
                ffmpegCommand(),
                "-v", "error",
                "-threads", "1",
                "-ss", formatSeconds(clip.startSeconds()),
                "-i", inputFile.toString(),
                "-t", formatSeconds(Math.min(clip.durationSeconds(), 20)),
                "-vf", "fps=2,scale=" + FOCUS_SAMPLE_WIDTH + ":" + FOCUS_SAMPLE_HEIGHT + ":force_original_aspect_ratio=decrease,"
                        + "pad=" + FOCUS_SAMPLE_WIDTH + ":" + FOCUS_SAMPLE_HEIGHT + ":(ow-iw)/2:(oh-ih)/2",
                "-an",
                "-f", "rawvideo",
                "-pix_fmt", "rgb24",
                "pipe:1"
        );

        ProcessBuilder builder = new ProcessBuilder(command);
        Process process = startManagedProcess(builder);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        Thread errorReader = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                in.transferTo(errors);
            } catch (IOException ignored) {
                // A failed process is handled after waitFor().
            }
        }, "ffmpeg-focus-error-reader");
        errorReader.start();

        int frameSize = FOCUS_SAMPLE_WIDTH * FOCUS_SAMPLE_HEIGHT * 3;
        byte[] frame = new byte[frameSize];
        byte[] previousGray = null;
        double[] columnScores = new double[FOCUS_SAMPLE_WIDTH];
        int frames = 0;

        try (InputStream in = process.getInputStream()) {
            while (readFully(in, frame)) {
                byte[] gray = new byte[FOCUS_SAMPLE_WIDTH * FOCUS_SAMPLE_HEIGHT];
                for (int y = 0; y < FOCUS_SAMPLE_HEIGHT; y++) {
                    for (int x = 0; x < FOCUS_SAMPLE_WIDTH; x++) {
                        int pixel = (y * FOCUS_SAMPLE_WIDTH + x) * 3;
                        int r = frame[pixel] & 0xff;
                        int g = frame[pixel + 1] & 0xff;
                        int b = frame[pixel + 2] & 0xff;
                        int value = (r * 30 + g * 59 + b * 11) / 100;
                        gray[y * FOCUS_SAMPLE_WIDTH + x] = (byte) value;
                    }
                }

                for (int y = 1; y < FOCUS_SAMPLE_HEIGHT - 1; y++) {
                    for (int x = 1; x < FOCUS_SAMPLE_WIDTH - 1; x++) {
                        int index = y * FOCUS_SAMPLE_WIDTH + x;
                        int current = gray[index] & 0xff;
                        int contrast = Math.abs(current - (gray[index - 1] & 0xff))
                                + Math.abs(current - (gray[index + 1] & 0xff))
                                + Math.abs(current - (gray[index - FOCUS_SAMPLE_WIDTH] & 0xff))
                                + Math.abs(current - (gray[index + FOCUS_SAMPLE_WIDTH] & 0xff));
                        int motion = previousGray == null ? 0 : Math.abs(current - (previousGray[index] & 0xff));
                        columnScores[x] += contrast * 0.35 + motion * 1.65;
                    }
                }
                previousGray = gray;
                frames++;
            }
        }

        int exit = process.waitFor();
        errorReader.join();
        if (exit != 0 || frames == 0) {
            return 0.5;
        }

        smooth(columnScores);
        int bestX = bestColumnForShortsWindow(columnScores);
        return bestX / (double) Math.max(1, FOCUS_SAMPLE_WIDTH - 1);
    }

    public List<Double> readVisualActivity(Path inputFile, double bucketSeconds, DoubleConsumer progress)
            throws IOException, InterruptedException {
        double fps = 1.0 / bucketSeconds;
        List<String> command = List.of(
                ffmpegCommand(),
                "-v", "error",
                "-threads", "1",
                "-i", inputFile.toString(),
                "-vf", "fps=" + formatSeconds(fps) + ",scale=" + FOCUS_SAMPLE_WIDTH + ":" + FOCUS_SAMPLE_HEIGHT
                        + ":force_original_aspect_ratio=decrease,pad=" + FOCUS_SAMPLE_WIDTH + ":" + FOCUS_SAMPLE_HEIGHT
                        + ":(ow-iw)/2:(oh-ih)/2",
                "-an",
                "-f", "rawvideo",
                "-pix_fmt", "rgb24",
                "pipe:1"
        );

        ProcessBuilder builder = new ProcessBuilder(command);
        Process process = startManagedProcess(builder);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        Thread errorReader = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                in.transferTo(errors);
            } catch (IOException ignored) {
                // A failed process is handled after waitFor().
            }
        }, "ffmpeg-visual-error-reader");
        errorReader.start();

        int frameSize = FOCUS_SAMPLE_WIDTH * FOCUS_SAMPLE_HEIGHT * 3;
        byte[] frame = new byte[frameSize];
        byte[] previousGray = null;
        List<Double> activity = new ArrayList<>();
        int frames = 0;

        try (InputStream in = process.getInputStream()) {
            while (readFully(in, frame)) {
                byte[] gray = toGray(frame);
                double contrast = frameContrast(gray);
                double motion = previousGray == null ? 0 : frameMotion(gray, previousGray);
                activity.add(motion * 0.75 + contrast * 0.25);
                previousGray = gray;
                frames++;
                if (progress != null && frames % 20 == 0) {
                    progress.accept(frames * bucketSeconds);
                }
            }
        }

        int exit = process.waitFor();
        errorReader.join();
        if (exit != 0) {
            String error = errors.toString(StandardCharsets.UTF_8);
            throw new IOException("FFmpeg visual analysis failed:\n" + error);
        }
        return activity;
    }

    private boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read == -1) {
                return offset == 0 ? false : false;
            }
            offset += read;
        }
        return true;
    }

    private void smooth(double[] scores) {
        double[] copy = scores.clone();
        for (int i = 0; i < scores.length; i++) {
            double sum = 0;
            int count = 0;
            for (int j = Math.max(0, i - 4); j <= Math.min(scores.length - 1, i + 4); j++) {
                sum += copy[j];
                count++;
            }
            scores[i] = sum / count;
        }
    }

    private byte[] toGray(byte[] frame) {
        byte[] gray = new byte[FOCUS_SAMPLE_WIDTH * FOCUS_SAMPLE_HEIGHT];
        for (int y = 0; y < FOCUS_SAMPLE_HEIGHT; y++) {
            for (int x = 0; x < FOCUS_SAMPLE_WIDTH; x++) {
                int pixel = (y * FOCUS_SAMPLE_WIDTH + x) * 3;
                int r = frame[pixel] & 0xff;
                int g = frame[pixel + 1] & 0xff;
                int b = frame[pixel + 2] & 0xff;
                gray[y * FOCUS_SAMPLE_WIDTH + x] = (byte) ((r * 30 + g * 59 + b * 11) / 100);
            }
        }
        return gray;
    }

    private double frameMotion(byte[] current, byte[] previous) {
        long sum = 0;
        for (int i = 0; i < current.length; i++) {
            sum += Math.abs((current[i] & 0xff) - (previous[i] & 0xff));
        }
        return sum / (double) (current.length * 255);
    }

    private double frameContrast(byte[] gray) {
        long sum = 0;
        int count = 0;
        for (int y = 1; y < FOCUS_SAMPLE_HEIGHT - 1; y++) {
            for (int x = 1; x < FOCUS_SAMPLE_WIDTH - 1; x++) {
                int index = y * FOCUS_SAMPLE_WIDTH + x;
                int current = gray[index] & 0xff;
                sum += Math.abs(current - (gray[index - 1] & 0xff));
                sum += Math.abs(current - (gray[index + 1] & 0xff));
                sum += Math.abs(current - (gray[index - FOCUS_SAMPLE_WIDTH] & 0xff));
                sum += Math.abs(current - (gray[index + FOCUS_SAMPLE_WIDTH] & 0xff));
                count += 4;
            }
        }
        return sum / (double) (Math.max(1, count) * 255);
    }

    private int bestColumnForShortsWindow(double[] scores) {
        int shortsWidth = Math.max(1, (int) Math.round(FOCUS_SAMPLE_HEIGHT * 9.0 / 16.0));
        int bestStart = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int start = 0; start <= scores.length - shortsWidth; start++) {
            double score = 0;
            for (int x = start; x < start + shortsWidth; x++) {
                score += scores[x];
            }
            if (score > bestScore) {
                bestScore = score;
                bestStart = start;
            }
        }
        return bestStart + shortsWidth / 2;
    }

    private String buildShortsFilter(VideoInfo info, double focusX) {
        double targetAspect = 9.0 / 16.0;
        if (info.isWideForShorts()) {
            int cropWidth = even((int) Math.round(info.height() * targetAspect));
            int maxX = Math.max(0, info.width() - cropWidth);
            int x = even((int) Math.round(focusX * info.width() - cropWidth / 2.0));
            x = Math.max(0, Math.min(maxX, x));
            return "crop=" + cropWidth + ":" + info.height() + ":" + x + ":0,scale=1080:1920,setsar=1";
        }

        int cropHeight = even((int) Math.round(info.width() / targetAspect));
        cropHeight = Math.min(cropHeight, info.height());
        int y = Math.max(0, (info.height() - cropHeight) / 2);
        return "crop=" + info.width() + ":" + cropHeight + ":0:" + y + ",scale=1080:1920,setsar=1";
    }

    private int even(int value) {
        return Math.max(2, value - Math.floorMod(value, 2));
    }

    private List<String> buildAudioPcmCommand(Path inputFile, List<AudioTrackSelection> audioTrackSelections, int sampleRate) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegCommand());
        command.add("-v");
        command.add("error");
        command.add("-threads");
        command.add("1");
        command.add("-i");
        command.add(inputFile.toString());

        if (audioTrackSelections == null || audioTrackSelections.isEmpty()) {
            command.add("-vn");
            command.add("-ac");
            command.add("1");
            command.add("-ar");
            command.add(Integer.toString(sampleRate));
        } else {
            command.add("-filter_complex");
            command.add(buildAudioOutputFilter(audioTrackSelections, true));
            command.add("-map");
            command.add("[aout]");
            command.add("-ar");
            command.add(Integer.toString(sampleRate));
        }

        command.add("-f");
        command.add("s16le");
        command.add("pipe:1");
        return command;
    }

    private List<String> buildAudioPreviewCommand(Path inputFile, List<AudioTrackSelection> selections, double startSeconds) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegCommand());
        command.add("-v");
        command.add("error");
        if (startSeconds > 0) {
            command.add("-ss");
            command.add(formatSeconds(startSeconds));
        }
        command.add("-i");
        command.add(inputFile.toString());
        command.add("-filter_complex");
        command.add(buildAudioOutputFilter(selections, true));
        command.add("-map");
        command.add("[aout]");
        command.add("-f");
        command.add("wav");
        command.add("pipe:1");
        return command;
    }

    private List<String> buildExportCommand(Path inputFile, Path output, ClipCandidate clip, String videoFilter,
                                            List<AudioTrackSelection> audioTrackSelections) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegCommand());
        command.add("-y");
        command.add("-ss");
        command.add(formatSeconds(clip.startSeconds()));
        command.add("-i");
        command.add(inputFile.toString());
        command.add("-t");
        command.add(formatSeconds(clip.durationSeconds()));

        if (audioTrackSelections == null || audioTrackSelections.isEmpty()) {
            command.add("-map");
            command.add("0:v:0");
            command.add("-map");
            command.add("0:a:0?");
            command.add("-vf");
            command.add(videoFilter);
        } else {
            command.add("-filter_complex");
            command.add("[0:v:0]" + videoFilter + "[vout];" + buildAudioOutputFilter(audioTrackSelections, false));
            command.add("-map");
            command.add("[vout]");
            command.add("-map");
            command.add("[aout]");
        }

        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");
        command.add("-crf");
        command.add("23");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("160k");
        command.add("-movflags");
        command.add("+faststart");
        command.add(output.toString());
        return command;
    }

    private String buildAudioOutputFilter(List<AudioTrackSelection> selections, boolean pcmOutput) {
        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < selections.size(); i++) {
            AudioTrackSelection selection = selections.get(i);
            filter.append("[0:")
                    .append(selection.streamIndex())
                    .append("]volume=")
                    .append(formatSeconds(selection.volume()))
                    .append(",aformat=sample_fmts=fltp:channel_layouts=mono[a")
                    .append(i)
                    .append("];");
        }
        if (selections.size() == 1) {
            filter.append("[a0]anull");
        } else {
            for (int i = 0; i < selections.size(); i++) {
                filter.append("[a").append(i).append("]");
            }
            filter.append("amix=inputs=")
                    .append(selections.size())
                    .append(":duration=longest:normalize=0");
        }
        filter.append("[aout]");
        return filter.toString();
    }

    private String buildAudioMixFilter(List<Integer> audioStreamIndexes, boolean pcmOutput) {
        List<AudioTrackSelection> selections = audioStreamIndexes.stream()
                .map(index -> new AudioTrackSelection(index, 1.0))
                .toList();
        return buildAudioOutputFilter(selections, pcmOutput);
    }

    private ProcessResult runAndCapture(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = startManagedProcess(builder);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            in.transferTo(out);
        }
        int exit = process.waitFor();
        return new ProcessResult(exit, out.toString(StandardCharsets.UTF_8));
    }

    private Process startManagedProcess(ProcessBuilder builder) throws IOException {
        Process process = builder.start();
        runningProcesses.add(process);
        process.onExit().thenRun(() -> runningProcesses.remove(process));
        return process;
    }

    private String ffmpegCommand() {
        return findTool("ffmpeg");
    }

    private String ffprobeCommand() {
        return findTool("ffprobe");
    }

    private String ffplayCommand() {
        return findTool("ffplay");
    }

    private String findTool(String name) {
        String executable = name + ".exe";
        for (Path baseDirectory : appBaseDirectories()) {
            List<Path> localCandidates = List.of(
                    baseDirectory.resolve(Paths.get("ffmpeg", "bin", executable)),
                    baseDirectory.resolve(Paths.get("tools", "ffmpeg", "bin", executable)),
                    baseDirectory.resolve(executable)
            );
            for (Path candidate : localCandidates) {
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }

            Optional<Path> nested = findNestedTool(baseDirectory.resolve("ffmpeg"), executable)
                    .or(() -> findNestedTool(baseDirectory.resolve(Paths.get("tools", "ffmpeg")), executable));
            if (nested.isPresent()) {
                return nested.get().toString();
            }
        }

        Optional<Path> installed = findInstalledTool(executable);
        if (installed.isPresent()) {
            return installed.get().toString();
        }
        return name;
    }

    private List<Path> appBaseDirectories() {
        List<Path> directories = new ArrayList<>();
        directories.add(Paths.get("").toAbsolutePath());
        applicationDirectory().ifPresent(directories::add);
        Path javaHome = Paths.get(System.getProperty("java.home", ".")).toAbsolutePath();
        Path runtimeParent = javaHome.getParent();
        if (runtimeParent != null) {
            directories.add(runtimeParent);
            directories.add(runtimeParent.resolve("app"));
        }
        return directories.stream().distinct().toList();
    }

    private Optional<Path> applicationDirectory() {
        try {
            Path location = Path.of(FfmpegService.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            return Optional.of(Files.isRegularFile(location) ? location.getParent() : location);
        } catch (URISyntaxException | IllegalArgumentException ex) {
            return Optional.empty();
        }
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
                FFmpeg/FFprobe/FFplay not found.

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

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}

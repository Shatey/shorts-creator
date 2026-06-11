package com.shortscreator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PythonAnalyzer {
    private static final Pattern CLIP_PATTERN = Pattern.compile("\\{[^{}]*}");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\"%s\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");

    private final List<Process> runningProcesses = new ArrayList<>();

    public boolean isAvailable() {
        return Files.isRegularFile(scriptPath()) && pythonCommand().isPresent();
    }

    public List<ClipCandidate> findCandidates(Path inputFile, int requestedCount, int clipMinDurationSeconds,
                                              int clipDurationSeconds,
                                              List<AudioTrackSelection> audioTrackSelections)
            throws IOException, InterruptedException {
        Path script = scriptPath();
        Optional<String> python = pythonCommand();
        if (!Files.isRegularFile(script) || python.isEmpty()) {
            throw new IOException("Python analyzer is not available.");
        }

        List<String> command = new ArrayList<>();
        command.add(python.get());
        command.add(script.toString());
        command.add("--input");
        command.add(inputFile.toString());
        command.add("--count");
        command.add(Integer.toString(requestedCount));
        command.add("--min-duration");
        command.add(Integer.toString(clipMinDurationSeconds));
        command.add("--max-duration");
        command.add(Integer.toString(clipDurationSeconds));
        for (AudioTrackSelection selection : audioTrackSelections) {
            command.add("--track");
            command.add(selection.streamIndex() + ":" + String.format(Locale.US, "%.3f", selection.volume()));
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        synchronized (runningProcesses) {
            runningProcesses.add(process);
        }
        process.onExit().thenRun(() -> {
            synchronized (runningProcesses) {
                runningProcesses.remove(process);
            }
        });

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            in.transferTo(out);
        }
        int exit = process.waitFor();
        String output = out.toString(StandardCharsets.UTF_8);
        if (exit != 0) {
            throw new IOException(output.isBlank() ? "Python analyzer failed." : output);
        }
        return parseCandidates(output);
    }

    public void cancelRunningProcesses() {
        List<Process> snapshot;
        synchronized (runningProcesses) {
            snapshot = new ArrayList<>(runningProcesses);
        }
        for (Process process : snapshot) {
            if (process.isAlive()) {
                destroyProcessTree(process);
            }
        }
    }

    private void destroyProcessTree(Process process) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            try {
                new ProcessBuilder("taskkill", "/PID", Long.toString(process.pid()), "/T", "/F")
                        .redirectErrorStream(true)
                        .start()
                        .waitFor();
                return;
            } catch (IOException ex) {
                // Fall back to the Java process API.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        process.destroyForcibly();
    }

    private List<ClipCandidate> parseCandidates(String json) throws IOException {
        List<ClipCandidate> clips = new ArrayList<>();
        Matcher matcher = CLIP_PATTERN.matcher(json);
        while (matcher.find()) {
            String object = matcher.group();
            double start = readNumber(object, "start");
            double duration = readNumber(object, "duration");
            double score = readNumber(object, "score");
            clips.add(new ClipCandidate(clips.size() + 1, start, duration, score));
        }
        if (clips.isEmpty()) {
            throw new IOException("Python analyzer returned no clips.");
        }
        return clips;
    }

    private double readNumber(String object, String key) throws IOException {
        Pattern pattern = Pattern.compile(String.format(NUMBER_PATTERN.pattern(), Pattern.quote(key)));
        Matcher matcher = pattern.matcher(object);
        if (!matcher.find()) {
            throw new IOException("Python analyzer returned invalid clip JSON.");
        }
        return Double.parseDouble(matcher.group(1));
    }

    private Optional<String> pythonCommand() {
        for (String candidate : List.of("python", "py")) {
            try {
                Process process = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true)
                        .start();
                if (process.waitFor() == 0) {
                    return Optional.of(candidate);
                }
            } catch (IOException ex) {
                // Try the next command.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Path scriptPath() {
        return Path.of("analysis", "analyze.py");
    }
}

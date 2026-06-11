package com.shortscreator;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.prefs.Preferences;

public final class ShortsCreatorApp {
    private static final String LAST_VIDEO_DIRECTORY = "lastVideoDirectory";
    private static final String LANGUAGE = "language";

    private final Preferences preferences = Preferences.userNodeForPackage(ShortsCreatorApp.class);
    private final I18n i18n = new I18n(initialLanguage());
    private final JFrame frame = new JFrame();
    private final JTextField inputField = new JTextField();
    private final JTextField outputField = new JTextField();
    private final JTextField gameTitleField = new JTextField();
    private final JTextField partNumberField = new JTextField();
    private final JLabel videoLabel = new JLabel();
    private final JLabel outputLabel = new JLabel();
    private final JLabel gameTitleLabel = new JLabel();
    private final JLabel partNumberLabel = new JLabel();
    private final JLabel audioTracksLabel = new JLabel();
    private final JLabel shortsLabel = new JLabel();
    private final JLabel lengthLabel = new JLabel();
    private final JLabel minLengthLabel = new JLabel();
    private final JLabel maxLengthLabel = new JLabel();
    private final JLabel languageLabel = new JLabel();
    private final JLabel analysisEngineLabel = new JLabel();
    private final JSpinner countSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 100, 1));
    private final JCheckBox allMomentsCheckBox = new JCheckBox();
    private final JSpinner minDurationSpinner = new JSpinner(new SpinnerNumberModel(60, 5, 60, 1));
    private final JSpinner durationSpinner = new JSpinner(new SpinnerNumberModel(60, 5, 60, 1));
    private final JButton browseInputButton = new JButton();
    private final JButton browseOutputButton = new JButton();
    private final JButton previewMixButton = new JButton();
    private final JButton analyzeButton = new JButton();
    private final JButton exportButton = new JButton();
    private final JButton thumbnailPromptButton = new JButton();
    private final JButton cancelButton = new JButton();
    private final JComboBox<LanguageOption> languageComboBox = new JComboBox<>(new LanguageOption[]{
            new LanguageOption("en", "English"),
            new LanguageOption("ru", "Русский")
    });
    private final JComboBox<AnalysisEngineOption> analysisEngineComboBox = new JComboBox<>();
    private final JComboBox<VideoPartTypeOption> partTypeComboBox = new JComboBox<>();
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JTextArea logArea = new JTextArea();
    private final DefaultListModel<ClipCandidate> clipModel = new DefaultListModel<>();
    private final JList<ClipCandidate> clipList = new JList<>(clipModel);
    private final JPanel audioTracksPanel = new JPanel();
    private final TitledBorder clipsBorder = BorderFactory.createTitledBorder("");
    private final TitledBorder logsBorder = BorderFactory.createTitledBorder("");
    private final List<AudioTrack> audioTracks = new ArrayList<>();
    private final List<JCheckBox> audioTrackCheckBoxes = new ArrayList<>();
    private final List<JSlider> audioTrackVolumeSliders = new ArrayList<>();

    private final FfmpegService ffmpeg = new FfmpegService();
    private final VideoAnalyzer analyzer = new VideoAnalyzer(ffmpeg);
    private final PythonAnalyzer pythonAnalyzer = new PythonAnalyzer();
    private Path currentInput;
    private Path currentOutputDirectory;
    private List<AudioTrackSelection> currentAudioTrackSelections = List.of();
    private PreviewSession activePreviewSession;
    private SwingWorker<?, ?> activeWorker;
    private Timer previewRestartTimer;
    private long previewStartedAtMillis;
    private double previewStartOffsetSeconds;

    public void show() {
        setSystemLookAndFeel();
        configureFrame();
        applyTexts();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void configureFrame() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancelCurrentWork();
                stopPreview();
            }
        });
        frame.setMinimumSize(new Dimension(980, 620));
        frame.setLayout(new BorderLayout(12, 12));
        frame.add(buildControls(), BorderLayout.NORTH);
        frame.add(buildCenter(), BorderLayout.CENTER);
        frame.add(buildBottom(), BorderLayout.SOUTH);
        frame.pack();
    }

    private JPanel buildControls() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        addLabel(panel, c, videoLabel, 0, 0);
        addField(panel, c, inputField, 1, 0);
        browseInputButton.addActionListener(e -> chooseInput());
        addButton(panel, c, browseInputButton, 2, 0);

        addLabel(panel, c, outputLabel, 0, 1);
        addField(panel, c, outputField, 1, 1);
        browseOutputButton.addActionListener(e -> chooseOutput());
        addButton(panel, c, browseOutputButton, 2, 1);

        addLabel(panel, c, gameTitleLabel, 0, 2);
        addField(panel, c, gameTitleField, 1, 2);
        JPanel partPanel = new JPanel(new BorderLayout(8, 0));
        partPanel.add(partNumberLabel, BorderLayout.WEST);
        partPanel.add(partNumberField, BorderLayout.CENTER);
        partPanel.add(partTypeComboBox, BorderLayout.EAST);
        addButton(panel, c, partPanel, 2, 2);

        addLabel(panel, c, audioTracksLabel, 0, 3);
        audioTracksPanel.setLayout(new javax.swing.BoxLayout(audioTracksPanel, javax.swing.BoxLayout.Y_AXIS));
        JScrollPane audioScroll = new JScrollPane(audioTracksPanel);
        audioScroll.setPreferredSize(new Dimension(640, 124));
        audioScroll.setMinimumSize(new Dimension(420, 112));
        addAudioScroll(panel, c, audioScroll, 1, 3);
        previewMixButton.addActionListener(e -> toggleAudioPreviewMix());
        addButton(panel, c, previewMixButton, 2, 3);

        addLabel(panel, c, shortsLabel, 0, 4);
        JPanel countPanel = new JPanel(new BorderLayout(8, 0));
        countPanel.add(countSpinner, BorderLayout.WEST);
        countPanel.add(allMomentsCheckBox, BorderLayout.CENTER);
        allMomentsCheckBox.addActionListener(e -> countSpinner.setEnabled(!allMomentsCheckBox.isSelected()));
        addButton(panel, c, countPanel, 1, 4);

        addLabel(panel, c, lengthLabel, 0, 5);
        JPanel durationPanel = new JPanel(new BorderLayout(8, 0));
        durationPanel.add(minLengthLabel, BorderLayout.WEST);
        durationPanel.add(minDurationSpinner, BorderLayout.CENTER);
        durationPanel.add(maxLengthLabel, BorderLayout.EAST);
        JPanel maxDurationPanel = new JPanel(new BorderLayout(4, 0));
        maxDurationPanel.add(durationPanel, BorderLayout.WEST);
        maxDurationPanel.add(durationSpinner, BorderLayout.CENTER);
        addButton(panel, c, maxDurationPanel, 1, 5);

        analyzeButton.addActionListener(e -> analyze());
        exportButton.addActionListener(e -> export());
        thumbnailPromptButton.addActionListener(e -> generateThumbnailPrompts());
        cancelButton.addActionListener(e -> cancelCurrentWork());
        exportButton.setEnabled(false);
        thumbnailPromptButton.setEnabled(false);
        cancelButton.setEnabled(false);

        JPanel languagePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        languageComboBox.addActionListener(e -> changeLanguageFromCombo());
        languagePanel.add(languageLabel);
        languagePanel.add(languageComboBox);
        JPanel enginePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        enginePanel.add(analysisEngineLabel);
        enginePanel.add(analysisEngineComboBox);
        JPanel settingsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        settingsPanel.add(languagePanel);
        settingsPanel.add(enginePanel);
        addButton(panel, c, settingsPanel, 1, 6);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(analyzeButton);
        actions.add(exportButton);
        actions.add(thumbnailPromptButton);
        actions.add(cancelButton);
        c.gridx = 2;
        c.gridy = 6;
        c.weightx = 0;
        panel.add(actions, c);
        return panel;
    }

    private JPanel buildCenter() {
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));

        clipList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        clipList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = new JLabel(String.format("%02d  %s  score %.4f",
                    value.index(), value.displayRange(), value.score()));
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            label.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);

        JScrollPane clips = new JScrollPane(clipList);
        clips.setBorder(clipsBorder);
        JScrollPane logs = new JScrollPane(logArea);
        logs.setBorder(logsBorder);
        logs.setPreferredSize(new Dimension(320, 240));

        panel.add(clips, BorderLayout.CENTER);
        panel.add(logs, BorderLayout.EAST);
        return panel;
    }

    private JPanel buildBottom() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
        progressBar.setStringPainted(true);
        panel.add(progressBar, BorderLayout.CENTER);
        return panel;
    }

    private void analyze() {
        ExportSettings settings = readSettings();
        if (settings == null) {
            return;
        }
        currentInput = settings.inputFile();
        setBusy(true);
        clipModel.clear();
        currentInput = null;
        currentOutputDirectory = null;
        currentAudioTrackSelections = List.of();
        log(i18n.text("log.checkingTools"));

        SwingWorker<List<ClipCandidate>, String> worker = new SwingWorker<>() {
            @Override
            protected List<ClipCandidate> doInBackground() throws Exception {
                ffmpeg.verifyInstalled();
                Path input = settings.inputFile();
                Path output = settings.outputDirectory();
                currentInput = input;
                currentOutputDirectory = output;
                currentAudioTrackSelections = settings.audioTrackSelections();
                publish(i18n.text("log.analyzing"));
                int requestedCount = settings.detectAllMoments() ? VideoAnalyzer.ALL_MOMENTS : settings.clipCount();
                return findCandidates(settings, input, requestedCount,
                        progress -> setProgress((int) Math.round(progress * 100)));
            }

            @Override
            protected void process(List<String> chunks) {
                chunks.forEach(ShortsCreatorApp.this::log);
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled()) {
                        log(i18n.text("log.analysisCancelled"));
                        return;
                    }
                    List<ClipCandidate> clips = get();
                    clips.forEach(clipModel::addElement);
                    if (!clips.isEmpty()) {
                        clipList.setSelectionInterval(0, clips.size() - 1);
                    }
                    exportButton.setEnabled(!clips.isEmpty());
                    thumbnailPromptButton.setEnabled(!clips.isEmpty());
                    log(i18n.format("log.foundClips", clips.size()));
                } catch (Exception ex) {
                    showError(ex);
                } finally {
                    activeWorker = null;
                    setBusy(false);
                    if (!isCancelled()) {
                        progressBar.setValue(100);
                    }
                }
            }
        };
        activeWorker = worker;
        bindProgress(worker);
        worker.execute();
    }

    private void export() {
        if (currentInput == null || currentOutputDirectory == null) {
            JOptionPane.showMessageDialog(frame, i18n.text("dialog.analyzeFirst"));
            return;
        }

        List<ClipCandidate> clips = clipList.getSelectedValuesList();
        if (clips.isEmpty()) {
            JOptionPane.showMessageDialog(frame, i18n.text("dialog.selectClip"));
            return;
        }

        setBusy(true);
        log(i18n.format("log.exportingMany", clips.size()));

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                for (int i = 0; i < clips.size(); i++) {
                    if (isCancelled()) {
                        return null;
                    }
                    ClipCandidate clip = clips.get(i);
                    publish(i18n.format("log.exportingClip", clip.displayRange()));
                    Path output = ffmpeg.exportClipWithSelections(currentInput, currentOutputDirectory, clip, clips.size(), currentAudioTrackSelections);
                    if (isCancelled()) {
                        return null;
                    }
                    publish(i18n.format("log.saved", output));
                    setProgress((int) Math.round(((i + 1) / (double) clips.size()) * 100));
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                chunks.forEach(ShortsCreatorApp.this::log);
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled()) {
                        log(i18n.text("log.exportCancelled"));
                        return;
                    }
                    get();
                    log(i18n.text("log.exportComplete"));
                } catch (Exception ex) {
                    showError(ex);
                } finally {
                    activeWorker = null;
                    setBusy(false);
                    if (!isCancelled()) {
                        progressBar.setValue(100);
                    }
                }
            }
        };
        activeWorker = worker;
        bindProgress(worker);
        worker.execute();
    }

    private void generateThumbnailPrompts() {
        if (currentInput == null || currentOutputDirectory == null) {
            JOptionPane.showMessageDialog(frame, i18n.text("dialog.analyzeFirst"));
            return;
        }

        List<ClipCandidate> clips = clipList.getSelectedValuesList();
        if (clips.isEmpty()) {
            JOptionPane.showMessageDialog(frame, i18n.text("dialog.selectClip"));
            return;
        }

        setBusy(true);
        log(i18n.format("log.thumbnailGenerating", clips.size()));
        String gameTitle = blankFallback(gameTitleField.getText(), "[GAME TITLE]");
        String partNumber = blankFallback(partNumberField.getText(), "[PART NUMBER]");
        String partMarker = partMarker(partNumber);

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                for (int i = 0; i < clips.size(); i++) {
                    if (isCancelled()) {
                        return null;
                    }
                    ClipCandidate clip = clips.get(i);
                    Path directory = currentOutputDirectory.resolve("thumbnail_prompts")
                            .resolve(String.format("short_%02d", clip.index()));
                    List<Path> frames = extractThumbnailFrames(clip, directory);
                    Path prompt = writeThumbnailPrompt(clip, directory, frames, gameTitle, partMarker);
                    publish(i18n.format("log.thumbnailSaved", prompt));
                    setProgress((int) Math.round(((i + 1) / (double) clips.size()) * 100));
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                chunks.forEach(ShortsCreatorApp.this::log);
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled()) {
                        log(i18n.text("log.thumbnailCancelled"));
                        return;
                    }
                    get();
                    log(i18n.text("log.thumbnailComplete"));
                } catch (Exception ex) {
                    showError(ex);
                } finally {
                    activeWorker = null;
                    setBusy(false);
                    if (!isCancelled()) {
                        progressBar.setValue(100);
                    }
                }
            }
        };
        activeWorker = worker;
        bindProgress(worker);
        worker.execute();
    }

    private List<Path> extractThumbnailFrames(ClipCandidate clip, Path directory) throws IOException, InterruptedException {
        List<Path> frames = new ArrayList<>();
        double[] offsets = {
                Math.max(0.2, clip.durationSeconds() * 0.20),
                Math.max(0.2, clip.durationSeconds() * 0.50),
                Math.max(0.2, clip.durationSeconds() * 0.80)
        };
        for (int i = 0; i < offsets.length; i++) {
            frames.add(ffmpeg.extractThumbnailFrame(currentInput, directory, clip, i + 1, offsets[i]));
        }
        return frames;
    }

    private Path writeThumbnailPrompt(ClipCandidate clip, Path directory, List<Path> frames,
                                      String gameTitle, String partMarker) throws IOException {
        Files.createDirectories(directory);
        Path prompt = directory.resolve(String.format("short_%02d_thumbnail_prompt.txt", clip.index()));
        String content = """
                I need a YouTube Shorts title and a thumbnail.

                Look at the attached video frames and infer what is happening in this specific moment.
                Generate titles based on the visible event, mood, action, surprise, failure, reaction, or funny situation in the frames.
                Do not use generic template titles.

                First, generate title ideas:
                - 20 Russian YouTube Shorts title options
                - 10 English YouTube Shorts title options
                - Titles should be emotional, funny, intriguing, and clickable
                - Include the game context naturally
                - Use this exact title format for Russian title options: %s — %s | <title>
                - Use this exact title format for English title options too, keeping the game title and part marker unchanged
                - Do not make the titles too long
                - Avoid generic titles like "Funny moment" or "Gameplay highlight"

                Then create a YouTube thumbnail in 16:9 format, 1280x720.

                Use the attached video frames as the base context.
                I will also attach photos of my cat. Add my cat as a meaningful part of the thumbnail story, not just as a random sticker.

                Required elements:
                - Game logo for: %s
                - Part marker: %s
                - My cat integrated into the scene as a funny/dramatic reaction character
                - One short Russian text phrase
                - One short English text phrase
                - Big readable typography, YouTube-style
                - Bright, high contrast, clickable composition
                - Keep the final image in strict 16:9 landscape format

                Suggested text direction:
                - Russian text: make it emotional, funny, or intriguing
                - English text: short punchy phrase, not a direct boring translation

                Avoid:
                - vertical format
                - tiny unreadable text
                - too much text
                - hiding the game logo
                - making the cat look pasted randomly

                Clip info:
                - Range: %s
                - Score: %.4f

                Attached frames:
                %s
                """.formatted(
                gameTitle,
                partMarker,
                gameTitle,
                partMarker,
                clip.displayRange(),
                clip.score(),
                frameListText(frames)
        );
        Files.writeString(prompt, content);
        return prompt;
    }

    private String partMarker(String partNumber) {
        String number = blankFallback(partNumber, "?").replaceFirst("^#+", "");
        VideoPartTypeOption option = (VideoPartTypeOption) partTypeComboBox.getSelectedItem();
        if (option != null && option.stream()) {
            return "стрим #" + number;
        }
        return "#" + number;
    }

    private String frameListText(List<Path> frames) {
        StringBuilder builder = new StringBuilder();
        for (Path frame : frames) {
            builder.append("- ").append(frame.getFileName()).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private String blankFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private ExportSettings readSettings() {
        if (inputField.getText().isBlank()) {
            JOptionPane.showMessageDialog(frame, i18n.text("dialog.chooseInput"));
            return null;
        }
        if (outputField.getText().isBlank()) {
            JOptionPane.showMessageDialog(frame, i18n.text("dialog.chooseOutput"));
            return null;
        }
        Path input = Path.of(inputField.getText());
        if (!Files.isRegularFile(input)) {
            JOptionPane.showMessageDialog(frame, i18n.text("dialog.inputMissing"));
            return null;
        }
        List<AudioTrackSelection> selectedAudioTracks = selectedAudioTrackSelections();
        if (selectedAudioTracks.isEmpty()) {
            JOptionPane.showMessageDialog(frame, i18n.text("dialog.selectAudio"));
            return null;
        }
        int minDuration = (Integer) minDurationSpinner.getValue();
        int maxDuration = (Integer) durationSpinner.getValue();
        if (minDuration > maxDuration) {
            JOptionPane.showMessageDialog(frame, i18n.text("dialog.lengthInvalid"));
            return null;
        }
        Path output = Path.of(outputField.getText());
        output = outputDirectoryFor(input, output);
        return new ExportSettings(
                input,
                output,
                (Integer) countSpinner.getValue(),
                minDuration,
                maxDuration,
                allMomentsCheckBox.isSelected(),
                selectedAudioTracks,
                selectedAnalysisEngine()
        );
    }

    private List<ClipCandidate> findCandidates(ExportSettings settings, Path input, int requestedCount,
                                               DoubleConsumer progress)
            throws IOException, InterruptedException {
        if (settings.analysisEngine() == AnalysisEngine.PYTHON || settings.analysisEngine() == AnalysisEngine.AUTO) {
            try {
                if (pythonAnalyzer.isAvailable()) {
                    logFromWorker(i18n.text("log.analysisPython"));
                    return pythonAnalyzer.findCandidates(input, requestedCount,
                            settings.clipMinDurationSeconds(),
                            settings.clipDurationSeconds(),
                            settings.audioTrackSelections());
                }
            } catch (Exception ignored) {
                // Python analysis is optional; Java remains the reliable fallback.
            }
        }

        logFromWorker(i18n.text("log.analysisJava"));
        return analyzer.findCandidates(input, requestedCount,
                settings.clipMinDurationSeconds(),
                settings.clipDurationSeconds(),
                settings.audioTrackSelections(),
                progress);
    }

    private void chooseInput() {
        JFileChooser chooser = new JFileChooser(lastVideoDirectory().toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("Video files", "mkv", "mp4", "mov", "webm", "avi"));
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            Path input = chooser.getSelectedFile().toPath();
            stopPreview();
            inputField.setText(input.toString());
            saveLastVideoDirectory(input.getParent());
            outputField.setText(defaultOutputDirectory(input).toString());
            loadAudioTracks(input);
        }
    }

    private void chooseOutput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            Path selected = chooser.getSelectedFile().toPath();
            if (!inputField.getText().isBlank()) {
                selected = outputDirectoryFor(Path.of(inputField.getText()), selected);
            }
            outputField.setText(selected.toString());
        }
    }

    private Path defaultOutputDirectory(Path input) {
        return input.getParent().resolve(videoBaseName(input));
    }

    private Path outputDirectoryFor(Path input, Path selectedDirectory) {
        String expectedName = videoBaseName(input);
        Path fileName = selectedDirectory.getFileName();
        if (fileName != null && fileName.toString().equalsIgnoreCase(expectedName)) {
            return selectedDirectory;
        }
        return selectedDirectory.resolve(expectedName);
    }

    private String videoBaseName(Path input) {
        String fileName = input.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            return fileName.substring(0, dot);
        }
        return fileName;
    }

    private Path lastVideoDirectory() {
        String saved = preferences.get(LAST_VIDEO_DIRECTORY, "");
        if (!saved.isBlank()) {
            Path path = Path.of(saved);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return Path.of(System.getProperty("user.home"));
    }

    private void saveLastVideoDirectory(Path directory) {
        if (directory != null && Files.isDirectory(directory)) {
            preferences.put(LAST_VIDEO_DIRECTORY, directory.toString());
        }
    }

    private void loadAudioTracks(Path input) {
        audioTracks.clear();
        audioTrackCheckBoxes.clear();
        audioTrackVolumeSliders.clear();
        audioTracksPanel.removeAll();
        try {
            audioTracks.addAll(ffmpeg.readAudioTracks(input));
            for (int i = 0; i < audioTracks.size(); i++) {
                AudioTrack track = audioTracks.get(i);
                JPanel row = new JPanel(new BorderLayout(8, 0));
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
                JCheckBox checkBox = new JCheckBox(track.displayName(), i == 0);
                JSlider volumeSlider = new JSlider(0, 200, 100);
                JLabel volumeLabel = new JLabel("100%");
                volumeSlider.setPreferredSize(new Dimension(180, 24));
                volumeSlider.addChangeListener(e -> {
                    volumeLabel.setText(volumeSlider.getValue() + "%");
                    schedulePreviewRefresh();
                });
                checkBox.addActionListener(e -> schedulePreviewRefresh());
                audioTrackCheckBoxes.add(checkBox);
                audioTrackVolumeSliders.add(volumeSlider);
                JPanel volumePanel = new JPanel(new BorderLayout(4, 0));
                volumePanel.add(volumeSlider, BorderLayout.CENTER);
                volumePanel.add(volumeLabel, BorderLayout.EAST);
                row.add(checkBox, BorderLayout.CENTER);
                row.add(volumePanel, BorderLayout.EAST);
                audioTracksPanel.add(row);
            }
            if (audioTracks.isEmpty()) {
                audioTracksPanel.add(new JLabel(i18n.text("audio.noTracks")));
            }
            log(i18n.format("log.foundAudio", audioTracks.size()));
        } catch (Exception ex) {
            showError(ex);
        }
        audioTracksPanel.revalidate();
        audioTracksPanel.repaint();
    }

    private List<AudioTrackSelection> selectedAudioTrackSelections() {
        List<AudioTrackSelection> selected = new ArrayList<>();
        for (int i = 0; i < audioTrackCheckBoxes.size() && i < audioTracks.size(); i++) {
            if (audioTrackCheckBoxes.get(i).isSelected()) {
                double volume = audioTrackVolumeSliders.get(i).getValue() / 100.0;
                selected.add(new AudioTrackSelection(audioTracks.get(i).streamIndex(), volume));
            }
        }
        return selected;
    }

    private void toggleAudioPreviewMix() {
        if (activePreviewSession != null && activePreviewSession.isAlive()) {
            stopPreview();
            return;
        }

        try {
            List<AudioTrackSelection> selected = selectedAudioTrackSelections();
            if (selected.isEmpty()) {
                JOptionPane.showMessageDialog(frame, i18n.text("dialog.selectAudio"));
                return;
            }
            startPreview(selected, 0);
            log(i18n.text("log.previewing"));
        } catch (Exception ex) {
            clearPreviewButton();
            showError(ex);
        }
    }

    private void startPreview(List<AudioTrackSelection> selected, double startOffsetSeconds) throws Exception {
        activePreviewSession = ffmpeg.previewAudioTracks(Path.of(inputField.getText()), selected, startOffsetSeconds);
        PreviewSession session = activePreviewSession;
        previewMixButton.setText(i18n.text("button.stop"));
        previewStartedAtMillis = System.currentTimeMillis();
        previewStartOffsetSeconds = startOffsetSeconds;
        session.onExit().thenRun(() -> javax.swing.SwingUtilities.invokeLater(() -> {
            if (activePreviewSession == session && !session.isAlive()) {
                clearPreviewButton();
            }
        }));
    }

    private void schedulePreviewRefresh() {
        if (activePreviewSession == null || !activePreviewSession.isAlive()) {
            return;
        }
        if (previewRestartTimer == null) {
            previewRestartTimer = new Timer(220, e -> refreshPreviewMix());
            previewRestartTimer.setRepeats(false);
        }
        previewRestartTimer.restart();
    }

    private void refreshPreviewMix() {
        if (activePreviewSession == null || !activePreviewSession.isAlive()) {
            return;
        }
        List<AudioTrackSelection> selected = selectedAudioTrackSelections();
        if (selected.isEmpty()) {
            stopPreview();
            return;
        }
        double elapsed = (System.currentTimeMillis() - previewStartedAtMillis) / 1000.0;
        double nextOffset = Math.max(0, previewStartOffsetSeconds + elapsed);
        activePreviewSession.destroy();
        try {
            startPreview(selected, nextOffset);
            log(i18n.text("log.previewUpdated"));
        } catch (Exception ex) {
            clearPreviewButton();
            showError(ex);
        }
    }

    private void stopPreview() {
        if (previewRestartTimer != null) {
            previewRestartTimer.stop();
        }
        if (activePreviewSession != null && activePreviewSession.isAlive()) {
            activePreviewSession.destroy();
        }
        clearPreviewButton();
    }

    private void clearPreviewButton() {
        previewMixButton.setText(i18n.text("button.previewMix"));
        activePreviewSession = null;
        previewStartedAtMillis = 0;
        previewStartOffsetSeconds = 0;
    }

    private void addLabel(JPanel panel, GridBagConstraints c, JLabel label, int x, int y) {
        c.gridx = x;
        c.gridy = y;
        c.gridwidth = 1;
        c.weightx = 0;
        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(label, c);
    }

    private void addField(JPanel panel, GridBagConstraints c, JTextField field, int x, int y) {
        c.gridx = x;
        c.gridy = y;
        c.gridwidth = 1;
        c.weightx = 1;
        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, c);
    }

    private void addButton(JPanel panel, GridBagConstraints c, java.awt.Component component, int x, int y) {
        c.gridx = x;
        c.gridy = y;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weighty = 0;
        panel.add(component, c);
    }

    private void addAudioScroll(JPanel panel, GridBagConstraints c, java.awt.Component component, int x, int y) {
        c.gridx = x;
        c.gridy = y;
        c.gridwidth = 1;
        c.weightx = 1;
        c.weighty = 0;
        c.fill = GridBagConstraints.BOTH;
        panel.add(component, c);
    }

    private void setBusy(boolean busy) {
        analyzeButton.setEnabled(!busy);
        exportButton.setEnabled(!busy && !clipModel.isEmpty());
        thumbnailPromptButton.setEnabled(!busy && !clipModel.isEmpty());
        cancelButton.setEnabled(busy);
        if (busy) {
            progressBar.setValue(0);
        }
    }

    private void cancelCurrentWork() {
        SwingWorker<?, ?> worker = activeWorker;
        if (worker == null || worker.isDone()) {
            return;
        }
        worker.cancel(true);
        pythonAnalyzer.cancelRunningProcesses();
        ffmpeg.cancelRunningProcesses();
        log(i18n.text("log.cancellationRequested"));
    }

    private void bindProgress(SwingWorker<?, ?> worker) {
        worker.addPropertyChangeListener(event -> {
            if ("progress".equals(event.getPropertyName())) {
                progressBar.setValue((Integer) event.getNewValue());
            }
        });
    }

    private void log(String message) {
        logArea.append(message + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void logFromWorker(String message) {
        javax.swing.SwingUtilities.invokeLater(() -> log(message));
    }

    private void showError(Exception ex) {
        if (ex instanceof CancellationException) {
            log(i18n.text("log.cancelled"));
            return;
        }
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        log(i18n.format("log.error", cause.getMessage()));
        JOptionPane.showMessageDialog(frame, cause.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }

    private void applyTexts() {
        frame.setTitle(i18n.text("app.title"));
        videoLabel.setText(i18n.text("label.video"));
        outputLabel.setText(i18n.text("label.output"));
        gameTitleLabel.setText(i18n.text("label.gameTitle"));
        partNumberLabel.setText(i18n.text("label.partNumber"));
        audioTracksLabel.setText(i18n.text("label.audioTracks"));
        shortsLabel.setText(i18n.text("label.shorts"));
        lengthLabel.setText(i18n.text("label.length"));
        minLengthLabel.setText(i18n.text("label.min"));
        maxLengthLabel.setText(i18n.text("label.max"));
        languageLabel.setText(i18n.text("label.language"));
        analysisEngineLabel.setText(i18n.text("label.analysisEngine"));
        browseInputButton.setText(i18n.text("button.browse"));
        browseOutputButton.setText(i18n.text("button.browse"));
        if (activePreviewSession != null && activePreviewSession.isAlive()) {
            previewMixButton.setText(i18n.text("button.stop"));
        } else {
            previewMixButton.setText(i18n.text("button.previewMix"));
        }
        analyzeButton.setText(i18n.text("button.analyze"));
        exportButton.setText(i18n.text("button.export"));
        thumbnailPromptButton.setText(i18n.text("button.thumbnailPrompt"));
        cancelButton.setText(i18n.text("button.cancel"));
        allMomentsCheckBox.setText(i18n.text("checkbox.allMoments"));
        clipsBorder.setTitle(i18n.text("border.detectedMoments"));
        logsBorder.setTitle(i18n.text("border.log"));
        refreshAnalysisEngineOptions();
        refreshPartTypeOptions();
        selectLanguageOption();
        frame.revalidate();
        frame.repaint();
    }

    private void changeLanguageFromCombo() {
        LanguageOption option = (LanguageOption) languageComboBox.getSelectedItem();
        if (option == null || option.code().equals(i18n.languageCode())) {
            return;
        }
        i18n.setLanguage(option.code());
        preferences.put(LANGUAGE, option.code());
        applyTexts();
    }

    private void selectLanguageOption() {
        for (int i = 0; i < languageComboBox.getItemCount(); i++) {
            LanguageOption option = languageComboBox.getItemAt(i);
            if (option.code().equals(i18n.languageCode())) {
                languageComboBox.setSelectedIndex(i);
                return;
            }
        }
    }

    private String initialLanguage() {
        String saved = preferences.get(LANGUAGE, "");
        if (!saved.isBlank()) {
            return saved;
        }
        return "ru".equalsIgnoreCase(System.getProperty("user.language")) ? "ru" : "en";
    }

    private AnalysisEngine selectedAnalysisEngine() {
        AnalysisEngineOption option = (AnalysisEngineOption) analysisEngineComboBox.getSelectedItem();
        return option == null ? AnalysisEngine.AUTO : option.engine();
    }

    private void refreshPartTypeOptions() {
        boolean stream = selectedPartTypeIsStream();
        partTypeComboBox.removeAllItems();
        partTypeComboBox.addItem(new VideoPartTypeOption(false, i18n.text("partType.part")));
        partTypeComboBox.addItem(new VideoPartTypeOption(true, i18n.text("partType.stream")));
        partTypeComboBox.setSelectedIndex(stream ? 1 : 0);
    }

    private boolean selectedPartTypeIsStream() {
        VideoPartTypeOption option = (VideoPartTypeOption) partTypeComboBox.getSelectedItem();
        return option != null && option.stream();
    }

    private void refreshAnalysisEngineOptions() {
        AnalysisEngine selected = selectedAnalysisEngine();
        analysisEngineComboBox.removeAllItems();
        analysisEngineComboBox.addItem(new AnalysisEngineOption(AnalysisEngine.AUTO, i18n.text("engine.auto")));
        analysisEngineComboBox.addItem(new AnalysisEngineOption(AnalysisEngine.JAVA, i18n.text("engine.java")));
        analysisEngineComboBox.addItem(new AnalysisEngineOption(AnalysisEngine.PYTHON, i18n.text("engine.python")));
        for (int i = 0; i < analysisEngineComboBox.getItemCount(); i++) {
            if (analysisEngineComboBox.getItemAt(i).engine() == selected) {
                analysisEngineComboBox.setSelectedIndex(i);
                return;
            }
        }
    }

    private void setSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Swing will use the default look and feel.
        }
    }

    private record LanguageOption(String code, String displayName) {
        @Override
        public String toString() {
            return displayName;
        }
    }

    private record AnalysisEngineOption(AnalysisEngine engine, String displayName) {
        @Override
        public String toString() {
            return displayName;
        }
    }

    private record VideoPartTypeOption(boolean stream, String displayName) {
        @Override
        public String toString() {
            return displayName;
        }
    }
}

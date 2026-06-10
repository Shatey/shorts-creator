package com.shortscreator;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ShortsCreatorApp {
    private final JFrame frame = new JFrame("Shorts Creator");
    private final JTextField inputField = new JTextField();
    private final JTextField outputField = new JTextField();
    private final JSpinner countSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 20, 1));
    private final JSpinner durationSpinner = new JSpinner(new SpinnerNumberModel(45, 10, 60, 5));
    private final JButton analyzeButton = new JButton("Analyze");
    private final JButton exportButton = new JButton("Export");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JTextArea logArea = new JTextArea();
    private final DefaultListModel<ClipCandidate> clipModel = new DefaultListModel<>();
    private final JList<ClipCandidate> clipList = new JList<>(clipModel);

    private final FfmpegService ffmpeg = new FfmpegService();
    private final VideoAnalyzer analyzer = new VideoAnalyzer(ffmpeg);
    private Path currentInput;

    public void show() {
        setSystemLookAndFeel();
        configureFrame();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void configureFrame() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(860, 560));
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

        addLabel(panel, c, "Video", 0, 0);
        addField(panel, c, inputField, 1, 0);
        JButton browseInput = new JButton("Browse");
        browseInput.addActionListener(e -> chooseInput());
        addButton(panel, c, browseInput, 2, 0);

        addLabel(panel, c, "Output", 0, 1);
        addField(panel, c, outputField, 1, 1);
        JButton browseOutput = new JButton("Browse");
        browseOutput.addActionListener(e -> chooseOutput());
        addButton(panel, c, browseOutput, 2, 1);

        addLabel(panel, c, "Shorts", 0, 2);
        addButton(panel, c, countSpinner, 1, 2);

        addLabel(panel, c, "Length, sec", 0, 3);
        addButton(panel, c, durationSpinner, 1, 3);

        analyzeButton.addActionListener(e -> analyze());
        exportButton.addActionListener(e -> export());
        exportButton.setEnabled(false);

        JPanel actions = new JPanel();
        actions.add(analyzeButton);
        actions.add(exportButton);
        c.gridx = 2;
        c.gridy = 3;
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
        clips.setBorder(BorderFactory.createTitledBorder("Detected moments"));
        JScrollPane logs = new JScrollPane(logArea);
        logs.setBorder(BorderFactory.createTitledBorder("Log"));
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
        log("Checking FFmpeg...");

        SwingWorker<List<ClipCandidate>, String> worker = new SwingWorker<>() {
            @Override
            protected List<ClipCandidate> doInBackground() throws Exception {
                ffmpeg.verifyInstalled();
                publish("Analyzing audio activity...");
                return analyzer.findCandidates(
                        settings.inputFile(),
                        settings.clipCount(),
                        settings.clipDurationSeconds(),
                        progress -> setProgress((int) Math.round(progress * 100))
                );
            }

            @Override
            protected void process(List<String> chunks) {
                chunks.forEach(ShortsCreatorApp.this::log);
            }

            @Override
            protected void done() {
                try {
                    List<ClipCandidate> clips = get();
                    clips.forEach(clipModel::addElement);
                    if (!clips.isEmpty()) {
                        clipList.setSelectionInterval(0, clips.size() - 1);
                    }
                    exportButton.setEnabled(!clips.isEmpty());
                    log("Found " + clips.size() + " candidate clips.");
                } catch (Exception ex) {
                    showError(ex);
                } finally {
                    setBusy(false);
                    progressBar.setValue(100);
                }
            }
        };
        bindProgress(worker);
        worker.execute();
    }

    private void export() {
        ExportSettings settings = readSettings();
        if (settings == null) {
            return;
        }

        List<ClipCandidate> clips = clipList.getSelectedValuesList();
        if (clips.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Select at least one detected moment to export.");
            return;
        }

        setBusy(true);
        log("Exporting " + clips.size() + " shorts...");

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                for (int i = 0; i < clips.size(); i++) {
                    ClipCandidate clip = clips.get(i);
                    publish("Exporting " + clip.displayRange() + "...");
                    Path output = ffmpeg.exportClip(settings.inputFile(), settings.outputDirectory(), clip, clips.size());
                    publish("Saved " + output);
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
                    get();
                    log("Export complete.");
                } catch (Exception ex) {
                    showError(ex);
                } finally {
                    setBusy(false);
                    progressBar.setValue(100);
                }
            }
        };
        bindProgress(worker);
        worker.execute();
    }

    private ExportSettings readSettings() {
        if (inputField.getText().isBlank()) {
            JOptionPane.showMessageDialog(frame, "Choose an input video.");
            return null;
        }
        if (outputField.getText().isBlank()) {
            JOptionPane.showMessageDialog(frame, "Choose an output folder.");
            return null;
        }
        Path input = Path.of(inputField.getText());
        if (!Files.isRegularFile(input)) {
            JOptionPane.showMessageDialog(frame, "Input video does not exist.");
            return null;
        }
        return new ExportSettings(
                input,
                Path.of(outputField.getText()),
                (Integer) countSpinner.getValue(),
                (Integer) durationSpinner.getValue()
        );
    }

    private void chooseInput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Video files", "mkv", "mp4", "mov", "webm", "avi"));
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            inputField.setText(chooser.getSelectedFile().toPath().toString());
            if (outputField.getText().isBlank()) {
                outputField.setText(chooser.getSelectedFile().toPath().getParent().resolve("shorts").toString());
            }
        }
    }

    private void chooseOutput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            outputField.setText(chooser.getSelectedFile().toPath().toString());
        }
    }

    private void addLabel(JPanel panel, GridBagConstraints c, String text, int x, int y) {
        c.gridx = x;
        c.gridy = y;
        c.weightx = 0;
        panel.add(new JLabel(text), c);
    }

    private void addField(JPanel panel, GridBagConstraints c, JTextField field, int x, int y) {
        c.gridx = x;
        c.gridy = y;
        c.weightx = 1;
        panel.add(field, c);
    }

    private void addButton(JPanel panel, GridBagConstraints c, java.awt.Component component, int x, int y) {
        c.gridx = x;
        c.gridy = y;
        c.weightx = 0;
        panel.add(component, c);
    }

    private void setBusy(boolean busy) {
        analyzeButton.setEnabled(!busy);
        exportButton.setEnabled(!busy && !clipModel.isEmpty());
        progressBar.setValue(0);
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

    private void showError(Exception ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        log("Error: " + cause.getMessage());
        JOptionPane.showMessageDialog(frame, cause.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }

    private void setSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Swing will use the default look and feel.
        }
    }
}

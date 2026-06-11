package com.shortscreator;

import java.nio.file.Path;
import java.util.List;

public record ExportSettings(
        Path inputFile,
        Path outputDirectory,
        int clipCount,
        int clipMinDurationSeconds,
        int clipDurationSeconds,
        boolean detectAllMoments,
        List<AudioTrackSelection> audioTrackSelections,
        AnalysisEngine analysisEngine
) {
}

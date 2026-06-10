package com.shortscreator;

import java.nio.file.Path;

public record ExportSettings(
        Path inputFile,
        Path outputDirectory,
        int clipCount,
        int clipDurationSeconds
) {
}

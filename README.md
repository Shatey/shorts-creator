# Shorts Creator

Windows Java app for cutting YouTube Shorts from MKV/video files.

The app uses FFmpeg for media work and a simple audio-activity heuristic to find candidate moments:

- reads a video file, including `.mkv`
- analyzes audio loudness peaks
- suggests non-overlapping clip ranges
- exports vertical 1080x1920 `.mp4` files for YouTube Shorts

## Requirements

- Java 17+
- FFmpeg installed and available in `PATH`, or unpacked into `ffmpeg\bin` next to the app
  - `ffmpeg`
  - `ffprobe`

To install FFmpeg on Windows, run:

```bat
install-ffmpeg.bat
```

If you install FFmpeg manually, either add its `bin` folder to `PATH` or unpack it into a local `ffmpeg` folder. The app searches common layouts such as:

```text
ffmpeg\bin\ffmpeg.exe
ffmpeg\ffmpeg-...\bin\ffmpeg.exe
tools\ffmpeg\bin\ffmpeg.exe
tools\ffmpeg\ffmpeg-...\bin\ffmpeg.exe
```

## Run

Double-click:

```bat
run.bat
```

Or from PowerShell:

```powershell
.\run.bat
```

The script compiles sources into `out\classes` and starts the Swing app.

## Notes

Automatic highlight detection is heuristic. Loud laughter, reactions, shouting, music hits, and high-energy sections usually score higher. For precise editorial control, review the generated clips and adjust the number/length of shorts before exporting again.

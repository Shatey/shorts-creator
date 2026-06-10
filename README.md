# Shorts Creator

Windows Java app for cutting YouTube Shorts from MKV/video files.

The app uses FFmpeg for media work and a simple audio-activity heuristic to find candidate moments:

- reads a video file, including `.mkv`
- analyzes audio loudness peaks
- suggests non-overlapping clip ranges from 15 seconds up to the selected maximum length
- lets you choose a target number of shorts or automatically select all detected moments
- estimates the most active horizontal area of each clip instead of always center-cropping
- exports vertical 1080x1920 `.mp4` files for YouTube Shorts
- saves clips into an output folder named after the source video file

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

Double-click without a command prompt:

```text
ShortsCreator.vbs
```

For a console/debug launch:

```bat
run.bat
```

Or from PowerShell:

```powershell
.\run.bat
```

The script compiles sources into `out\classes` and starts the Swing app. Choose a local video file, analyze it, then export selected moments.

## Notes

Automatic highlight detection is heuristic. Loud laughter, reactions, shouting, music hits, and high-energy sections usually score higher. The crop focus is also heuristic: it samples frames and prefers areas with more motion and visual detail. For precise editorial control, review the generated clips and adjust the number/max length of shorts before exporting again. Generated clips are at least 15 seconds long unless the source video itself is too short.

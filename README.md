# Shorts Creator

Windows Java app for cutting YouTube Shorts from MKV/video files.

The app uses FFmpeg for media work and audio/visual heuristics to find candidate moments:

- reads a video file, including `.mkv`
- analyzes audio loudness peaks
- analyzes visual motion and frame contrast
- suggests non-overlapping clip ranges between the selected minimum and maximum length
- lets you choose a target number of shorts or automatically select all detected moments
- lets you select, preview, drop, volume-adjust, or mix audio tracks
- lets you cancel long analysis/export jobs without closing the app
- supports English and Russian, with language switching inside the app
- can optionally use `analysis\analyze.py` for Python-based analysis, with automatic Java fallback
- generates ready-to-copy title/thumbnail prompts and 16:9 reference frames for ChatGPT
- estimates the most active horizontal area of each clip instead of always center-cropping
- exports vertical 1080x1920 `.mp4` files for YouTube Shorts
- saves clips into an output folder named after the source video file

## Requirements

- Java 17+
- FFmpeg installed and available in `PATH`, or unpacked into `ffmpeg\bin` next to the app
  - `ffmpeg`
  - `ffprobe`
  - `ffplay`
- Python is optional and only needed for the Python analysis engine

## Full Startup Steps

1. Install Java 17 or newer.

   Check it from PowerShell:

   ```powershell
   java -version
   javac -version
   ```

   Both commands should work. The app is compiled on launch, so `javac` is required, not only `java`.

2. Install FFmpeg.

   From the project folder, run:

   ```powershell
   .\install-ffmpeg.bat
   ```

   If Windows PowerShell says the script is not recognized, make sure you include `.\` before the file name.

3. Close and reopen PowerShell after FFmpeg installation.

   This lets Windows refresh `PATH`. The app also searches common WinGet install folders, but reopening the shell avoids confusion.

4. Open the project folder.

   Example:

   ```powershell
   cd "D:\projects\shorts creator"
   ```

5. Start the app.

   For the normal/debug launch with a console window:

   ```powershell
   .\run.bat
   ```

   For launch without a visible command prompt, double-click:

   ```text
   ShortsCreator.vbs
   ```

6. Choose a video file.

   Click `Browse` next to `Video` and select a local `.mkv`, `.mp4`, `.mov`, `.webm`, or `.avi` file.

7. Check the output folder.

   The app automatically suggests an output folder named after the source video file.

8. Select audio tracks.

   Choose which tracks to use. If you select multiple tracks, the exported short will mix them together. Use the volume sliders to set each track level.

9. Optional: preview the audio mix.

   Click `Preview mix` to listen to the selected tracks. Click `Stop` to stop playback.

10. Choose clip settings.

   Set:

   - number of shorts, or `All moments`
   - minimum duration
   - maximum duration
   - analysis engine: `Auto`, `Java`, or `Python`

   You can also fill:

   - game title
   - part number
   - type: regular part or stream

11. Click `Analyze`.

   The log will show either:

   ```text
   Analysis via Java
   ```

   or:

   ```text
   Analysis via Python
   ```

12. Review detected moments.

   Select the moments you want to export. By default, all found moments are selected.

13. Click `Export`.

   The app exports separate vertical 1080x1920 `.mp4` shorts into the output folder.

14. Optional: create title and thumbnail prompts.

   Select one or more detected moments and click `Title + thumbnail prompt`.

   For each selected moment, the app saves:

   - three 16:9 reference frames
   - a ready-to-copy prompt for ChatGPT with title and thumbnail instructions

   The prompt asks ChatGPT to generate 20 Russian titles based on the attached frames and visible events, using this format:

   ```text
   Game title — #part | title
   Game title — stream #part | title
   ```

   Files are saved under:

   ```text
   <output folder>\thumbnail_prompts\short_XX
   ```

   Attach the saved frames and your cat photos to ChatGPT, then paste the generated prompt.

15. Stop long work if needed.

   Use `Cancel` to stop analysis or export without closing the app.

## FFmpeg

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

The script compiles sources into `out\classes` and starts the Swing app. Choose a local video file, analyze it, then export selected moments as separate vertical shorts.

To only check that the app compiles:

```powershell
.\run.bat --build-only
```

The analysis engine selector has three modes:

- `Auto`: tries Python analysis first, then uses Java if Python is unavailable
- `Java`: always uses the built-in Java analysis
- `Python`: tries Python analysis, with Java fallback if it cannot run

## Notes

Automatic highlight detection is heuristic. Loud laughter, reactions, shouting, music hits, high-motion scenes, cuts, and visually busy sections usually score higher. When you request a fixed number of shorts, the app picks the highest-scoring non-overlapping candidates first. All Moments still requires a local audio/motion peak above the interest threshold, so it should not simply tile the whole video. The crop focus is also heuristic: it samples frames and prefers areas with more motion and visual detail. For precise editorial control, review the generated clips and adjust the number/min/max length of shorts before exporting again. Generated clips respect the selected minimum length unless the source video itself is too short.

When a file has multiple audio tracks, the first one is selected by default. Select one track to export it, or select several tracks to mix them into one AAC track. Use the percentage sliders to adjust track levels before analysis/export. Use Preview mix/Stop to listen to all currently selected tracks together with the current volume sliders; slider changes during preview are applied automatically.

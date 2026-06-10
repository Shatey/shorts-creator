@echo off
setlocal

where winget >nul 2>nul
if errorlevel 1 (
  echo winget was not found.
  echo.
  echo Install FFmpeg manually, then either:
  echo   1. Add ffmpeg\bin to PATH
  echo   2. Or unpack FFmpeg into the ffmpeg folder next to this app
  echo.
  pause
  exit /b 1
)

echo Installing FFmpeg with winget...
winget install --id Gyan.FFmpeg --exact --source winget
if errorlevel 1 (
  echo.
  echo FFmpeg install failed.
  pause
  exit /b 1
)

echo.
echo FFmpeg installed. Close and reopen the app if it was already running.
pause

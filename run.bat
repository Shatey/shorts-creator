@echo off
setlocal EnableDelayedExpansion

if not exist out\classes mkdir out\classes

if exist out\sources.txt del out\sources.txt
for /r src\main\java %%f in (*.java) do (
  set "source=%%f"
  echo "!source:\=/!" >> out\sources.txt
)
javac -encoding UTF-8 -d out\classes @out\sources.txt
if errorlevel 1 (
  echo.
  echo Build failed.
  pause
  exit /b 1
)

if "%~1"=="--build-only" (
  echo Build succeeded.
  exit /b 0
)

start "" javaw -cp out\classes com.shortscreator.Main
endlocal

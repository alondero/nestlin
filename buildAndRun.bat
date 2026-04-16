@echo off
REM Build and run Nestlin NES emulator
REM Usage: buildAndRun.bat [path-to-rom]

echo Building Nestlin...
call gradlew.bat build --quiet

if errorlevel 1 (
    echo Build failed!
    exit /b 1
)

if not "%1"=="" (
    echo Running emulator with: %1
    call gradlew.bat run --args="%1"
) else (
    echo Running emulator (no ROM specified, use File menu to load one)
    call gradlew.bat run
)
@echo off
REM Build and run Nestlin NES emulator
REM Usage: nestlin.bat <path-to-rom>

if "%1"=="" (
    echo Usage: %0 ^<path-to-rom^>
    exit /b 1
)

set ROM_PATH=%~1

echo Building Nestlin...
call gradlew.bat build --quiet

if errorlevel 1 (
    echo Build failed!
    exit /b 1
)

echo Running emulator with: %ROM_PATH%
call gradlew.bat run --args="%ROM_PATH%"
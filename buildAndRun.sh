#!/bin/bash
# Build and run Nestlin NES emulator
# Usage: ./buildAndRun.sh [path-to-rom]

set -e

echo "Building Nestlin..."
./gradlew build --quiet

if [ -z "$1" ]; then
    echo "Running emulator (no ROM specified, use File menu to load one)"
    ./gradlew run
else
    echo "Running emulator with: $1"
    ./gradlew run --args="$1"
fi
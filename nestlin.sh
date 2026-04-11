#!/bin/bash
# Build and run Nestlin NES emulator
# Usage: ./nestlin.sh <path-to-rom>

set -e

if [ -z "$1" ]; then
    echo "Usage: $0 <path-to-rom>"
    exit 1
fi

ROM_PATH="$1"

echo "Building Nestlin..."
./gradlew build --quiet

echo "Running emulator with: $ROM_PATH"
./gradlew run --args="$ROM_PATH"
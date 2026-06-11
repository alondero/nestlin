#!/usr/bin/env bash
# Thin wrapper so the diagnostic loop works from bash too.
# All logic lives in run-diag.ps1 — see its header for why this exists.
# Usage: tools/run-diag.sh <TestClass> [MethodName] [--mesen]
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARGS=(-TestClass "$1")
shift || true
for a in "$@"; do
  if [ "$a" = "--mesen" ]; then ARGS+=(-Mesen); else ARGS+=(-Method "$a"); fi
done
exec powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$SCRIPT_DIR/run-diag.ps1" "${ARGS[@]}"

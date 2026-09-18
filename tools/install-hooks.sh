#!/usr/bin/env bash
# Opt in to the repository's cross-platform Git hooks.

set -euo pipefail
git config core.hooksPath .githooks
echo "Installed repository hooks via core.hooksPath=.githooks"

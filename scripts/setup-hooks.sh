#!/bin/sh
# Point git at the repo's hooks (run once after clone).
cd "$(dirname "$0")/.." && git config core.hooksPath .githooks && echo "hooks installed"

#!/usr/bin/env bash
# Compatibility entry point. The supported build is the Termux proot recipe, which includes
# the Android SIGSYS/seccomp handling required by this app.
set -euo pipefail
cd "$(dirname "$0")"
exec tools/build-proot.sh "$@"

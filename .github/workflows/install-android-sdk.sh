#!/usr/bin/env bash
# Installs Android SDK packages on a GitHub-hosted Ubuntu runner.
#
# The runner image ships an Android SDK (ANDROID_HOME) but does not put `sdkmanager` on PATH, and
# its bundled command-line tools can be too old to know minor-API platforms such as
# "platforms;android-37.2". So: find the bundled sdkmanager, use it to install a pinned, recent
# command-line tools version, then install the requested packages with that one.
#
# Usage: install-android-sdk.sh "platforms;android-37.2" "build-tools;36.0.0"
set -euo pipefail

CMDLINE_TOOLS="${CMDLINE_TOOLS_VERSION:-23.0}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" || ! -d "$SDK" ]]; then
    echo "::error::ANDROID_HOME is not set or missing on this runner"
    exit 1
fi

bootstrap=""
for candidate in "$SDK/cmdline-tools/latest/bin/sdkmanager" "$SDK"/cmdline-tools/*/bin/sdkmanager; do
    if [[ -x "$candidate" ]]; then
        bootstrap="$candidate"
        break
    fi
done
if [[ -z "$bootstrap" ]]; then
    echo "::error::No sdkmanager found under $SDK/cmdline-tools"
    ls -la "$SDK" "$SDK/cmdline-tools" || true
    exit 1
fi

# `yes` dies of SIGPIPE once sdkmanager stops reading; don't let pipefail treat that as a failure.
accept_licenses() { (yes || true) | "$1" --sdk_root="$SDK" --licenses > /dev/null; }

accept_licenses "$bootstrap"
"$bootstrap" --sdk_root="$SDK" --install "cmdline-tools;$CMDLINE_TOOLS"

sdkmanager="$SDK/cmdline-tools/$CMDLINE_TOOLS/bin/sdkmanager"
accept_licenses "$sdkmanager"
"$sdkmanager" --sdk_root="$SDK" --install platform-tools "$@"

# Later steps (and the emulator script's adb) find the tools on PATH.
if [[ -n "${GITHUB_PATH:-}" ]]; then
    echo "$SDK/cmdline-tools/$CMDLINE_TOOLS/bin" >> "$GITHUB_PATH"
    echo "$SDK/platform-tools" >> "$GITHUB_PATH"
fi

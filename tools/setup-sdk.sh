#!/usr/bin/env bash
# Installs the Android SDK command-line tools and the packages this project needs on a Linux machine
# without Android Studio (CI runners, containers). Safe to run again: it skips what is already installed.
#
#   tools/setup-sdk.sh            # installs into $ANDROID_HOME, or ~/android-sdk
#
# Afterwards: export ANDROID_HOME=~/android-sdk (the script also writes local.properties).
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
TOOLS_ZIP="commandlinetools-linux-16111833_latest.zip"
PLATFORM="platforms;android-37.0"
BUILD_TOOLS="build-tools;37.0.0"

mkdir -p "$SDK"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp="$(mktemp -d)"
  curl -fsSL "https://dl.google.com/android/repository/$TOOLS_ZIP" -o "$tmp/tools.zip"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$SDK" --install "platform-tools" "$PLATFORM" "$BUILD_TOOLS" >/dev/null

repo="$(cd "$(dirname "$0")/.." && pwd)"
echo "sdk.dir=$SDK" > "$repo/local.properties"
echo "Android SDK ready in $SDK (local.properties written)."

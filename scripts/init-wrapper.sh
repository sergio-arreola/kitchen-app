#!/usr/bin/env bash
set -euo pipefail

if ! command -v gradle >/dev/null 2>&1; then
  echo "A one-time Gradle installation is required to generate the standard wrapper files."
  echo "Install Gradle 9.4.1 (or a compatible Gradle), then rerun this script."
  echo "On macOS, SDKMAN or Homebrew are both reasonable temporary bootstrap options."
  exit 1
fi

gradle wrapper --gradle-version 9.4.1 --distribution-type bin
./gradlew --version

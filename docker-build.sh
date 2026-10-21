#!/usr/bin/env bash
set -euo pipefail
if [[ ! -f ./gradlew ]]; then
  echo "Android Gradle project not created yet. Docker toolchain is ready; create the Kotlin project before building an APK."
  exit 2
fi
bash ./gradlew --no-daemon assembleDebug

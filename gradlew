#!/bin/sh
# Minimal wrapper: prefers checked-in gradle, falls back to system gradle.
# CI (.github/workflows/android-debug.yml) uses system Gradle 8.7 directly,
# so this script is only for local convenience.
if [ -f "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -jar "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" "$@"
else
  exec gradle "$@"
fi

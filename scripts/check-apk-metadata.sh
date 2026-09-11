#!/usr/bin/env bash

set -euo pipefail

apk="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$apk" ]]; then
    printf '%s\n' "Debug APK not found; run ./gradlew assembleDebug first." >&2
    exit 1
fi

min_sdk="$(apkanalyzer manifest min-sdk "$apk")"
if [[ "$min_sdk" != "26" ]]; then
    printf 'Expected min SDK 26, found %s.\n' "$min_sdk" >&2
    exit 1
fi

printf 'APK metadata is correct: min SDK %s.\n' "$min_sdk"

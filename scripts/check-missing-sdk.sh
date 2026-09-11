#!/usr/bin/env bash

set -euo pipefail

fake_sdk="$(mktemp -d "${TMPDIR:-/tmp}/hermes-relay-android-sdk.XXXXXX")"
trap 'rm -rf "$fake_sdk"' EXIT

if output="$(
    ANDROID_HOME="$fake_sdk" \
    ANDROID_SDK_ROOT="$fake_sdk" \
    ./gradlew :app:assembleDebug 2>&1
)"; then
    printf '%s\n' "Expected the build to fail when the SDK is unavailable." >&2
    exit 1
fi

if [[ "$output" != *"SDK location not found"* \
    && "$output" != *"failed to find target"* \
    && "$output" != *licen*accepted* \
    && "$output" != *"install the missing components"* ]]; then
    printf '%s\n' "The missing-SDK failure was not explicit:" >&2
    printf '%s\n' "$output" >&2
    exit 1
fi

printf '%s\n' "Missing SDK failure is explicit."

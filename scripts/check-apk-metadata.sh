#!/usr/bin/env bash

# Verifies that a built APK carries the metadata we expect. Defaults to the
# debug APK for CI; pass a path to check a release build instead.
#
# Set EXPECTED_SIGNER_SHA256 to the release signing certificate's SHA-256
# digest (uppercase hex, colon-separated) to also assert the APK was signed
# with that key. Without it the signing check is skipped and reported as such.

set -euo pipefail

apk="${1:-app/build/outputs/apk/debug/app-debug.apk}"

if [[ ! -f "$apk" ]]; then
    printf 'APK not found at %s; build it first.\n' "$apk" >&2
    exit 1
fi

min_sdk="$(apkanalyzer manifest min-sdk "$apk")"
if [[ "$min_sdk" != "26" ]]; then
    printf 'Expected min SDK 26, found %s.\n' "$min_sdk" >&2
    exit 1
fi

# versionCode is derived from the version name in app/build.gradle.kts. Recompute
# it here so a regression to a frozen code is caught at build time rather than by
# a user whose upgrade silently fails.
version_name_source="$(sed -n 's/.*appVersionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
if [[ -z "$version_name_source" ]]; then
    printf '%s\n' "Could not read appVersionName from app/build.gradle.kts." >&2
    exit 1
fi

IFS='.' read -r major minor patch <<<"${version_name_source%%-*}"
expected_code=$((10#$major * 10000 + 10#$minor * 100 + 10#$patch))

version_code="$(apkanalyzer manifest version-code "$apk")"
version_name="$(apkanalyzer manifest version-name "$apk")"

if [[ "$version_name" != "$version_name_source" ]]; then
    printf 'APK version name %s does not match build.gradle.kts %s.\n' \
        "$version_name" "$version_name_source" >&2
    exit 1
fi

if [[ "$version_code" != "$expected_code" ]]; then
    printf 'Expected version code %s for version %s, found %s.\n' \
        "$expected_code" "$version_name_source" "$version_code" >&2
    exit 1
fi

signing_status="skipped (EXPECTED_SIGNER_SHA256 unset)"
if [[ -n "${EXPECTED_SIGNER_SHA256:-}" ]]; then
    apksigner="apksigner"
    if [[ -n "${ANDROID_HOME:-}" && -n "${ANDROID_BUILD_TOOLS:-}" ]]; then
        apksigner="${ANDROID_HOME}/build-tools/${ANDROID_BUILD_TOOLS}/apksigner"
    fi

    actual_digest="$("$apksigner" verify --print-certs "$apk" |
        sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)"

    expected_digest="$(printf '%s' "$EXPECTED_SIGNER_SHA256" |
        tr -d ': ' | tr '[:upper:]' '[:lower:]')"
    normalised_actual="$(printf '%s' "$actual_digest" |
        tr -d ': ' | tr '[:upper:]' '[:lower:]')"

    if [[ -z "$normalised_actual" ]]; then
        printf '%s\n' "Could not read a signing certificate from $apk." >&2
        exit 1
    fi

    if [[ "$normalised_actual" != "$expected_digest" ]]; then
        printf 'APK signed with the wrong key.\n  expected %s\n  found    %s\n' \
            "$expected_digest" "$normalised_actual" >&2
        exit 1
    fi

    signing_status="verified"
fi

printf 'APK metadata is correct: min SDK %s, version %s (%s), signing %s.\n' \
    "$min_sdk" "$version_name" "$version_code" "$signing_status"

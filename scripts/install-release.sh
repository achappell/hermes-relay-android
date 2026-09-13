#!/usr/bin/env bash
#
# Install a release-signed build on an attached device.
#
# The debug key cannot upgrade a release-signed install: Android refuses it with
# INSTALL_FAILED_UPDATE_INCOMPATIBLE, and the only way through is to uninstall,
# which destroys the Keystore-backed relay credential, the saved profiles, and
# Local History. Signing locally with the real keystore upgrades in place and
# keeps all of it.
#
# Secrets come from 1Password rather than a dotfile. Keeping the keystore
# password next to the keystore means one leaked directory gives away both.
#
# Usage:
#   scripts/install-release.sh [additional gradle args]

set -euo pipefail

cd "$(dirname "$0")/.."

readonly ITEM="${HERMES_SIGNING_ITEM:-op://Personal/hermes-relay-android signing}"
readonly KEYSTORE="${RELEASE_KEYSTORE_FILE:-release.jks}"

if ! command -v op >/dev/null 2>&1; then
    echo "error: the 1Password CLI (op) is not installed." >&2
    echo "       brew install 1password-cli, or set RELEASE_KEYSTORE_PASSWORD" >&2
    echo "       and RELEASE_KEY_PASSWORD in the environment yourself." >&2
    exit 1
fi

# Fail here with something readable rather than letting Gradle report a
# confusing keystore error three minutes into a build.
if ! op account get >/dev/null 2>&1; then
    echo "error: 1Password CLI is not signed in. Run 'eval \$(op signin)'." >&2
    exit 1
fi

if [[ ! -f "$KEYSTORE" ]]; then
    echo "error: keystore '$KEYSTORE' not found in $(pwd)." >&2
    echo "       Losing it means future releases are signed with a different" >&2
    echo "       key and installed builds can no longer upgrade. Restore it" >&2
    echo "       from backup rather than generating a new one." >&2
    exit 1
fi

if ! adb get-state >/dev/null 2>&1; then
    echo "error: no device attached. Connect one and enable USB debugging." >&2
    exit 1
fi

read_field() {
    local value
    value="$(op read "$ITEM/$1")"
    if [[ -z "$value" ]]; then
        echo "error: '$ITEM/$1' resolved to an empty value." >&2
        exit 1
    fi
    printf '%s' "$value"
}

RELEASE_KEYSTORE_FILE="$KEYSTORE" \
RELEASE_KEY_ALIAS="$(read_field key_alias)" \
RELEASE_KEYSTORE_PASSWORD="$(read_field keystore_password)" \
RELEASE_KEY_PASSWORD="$(read_field key_password)" \
    ./gradlew installRelease --no-daemon "$@"

#!/usr/bin/env bash

# Creates the release keystore and uploads the four signing secrets that
# .github/workflows/release.yml expects. Run once, from the repository root.
#
# The keystore password is generated here and printed at the end. Save it, and
# back up release.jks somewhere outside the repository: losing either means
# future releases are signed with a different key, and installed builds can no
# longer upgrade in place.

set -euo pipefail

keystore="release.jks"
alias_name="hermes-relay"

if [[ -f "$keystore" ]]; then
    printf '%s\n' "$keystore already exists; refusing to overwrite it." >&2
    printf '%s\n' "Delete it deliberately if you really want a new key." >&2
    exit 1
fi

for tool in gh base64 openssl; do
    command -v "$tool" >/dev/null || {
        printf 'Required tool not found: %s\n' "$tool" >&2
        exit 1
    }
done

# macOS ships a /usr/bin/keytool stub that fails without a JDK behind it, so
# resolve a real JDK rather than trusting the name to be on PATH.
if [[ -z "${JAVA_HOME:-}" ]]; then
    for candidate in \
        /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
        /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
        /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home; do
        if [[ -x "$candidate/bin/keytool" ]]; then
            JAVA_HOME="$candidate"
            break
        fi
    done
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/keytool" ]]; then
    printf '%s\n' "No JDK found. Set JAVA_HOME to a JDK and re-run." >&2
    exit 1
fi

keytool_bin="${JAVA_HOME}/bin/keytool"

# 128 bits of hex. Deliberately not piped through `head`, which closes the pipe
# early and trips SIGPIPE under `pipefail`.
password="$(openssl rand -hex 16)"

"$keytool_bin" -genkeypair \
    -keystore "$keystore" \
    -storetype PKCS12 \
    -alias "$alias_name" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -storepass "$password" \
    -keypass "$password" \
    -dname "CN=Hermes Relay Android, O=Amanda Chappell, C=US"

base64 -i "$keystore" | gh secret set RELEASE_KEYSTORE_BASE64
gh secret set RELEASE_KEYSTORE_PASSWORD --body "$password"
gh secret set RELEASE_KEY_ALIAS --body "$alias_name"
gh secret set RELEASE_KEY_PASSWORD --body "$password"

printf '\nKeystore created and secrets uploaded.\n\n'
printf 'Save this password now (1Password), then back up %s:\n\n' "$keystore"
printf '  %s\n\n' "$password"

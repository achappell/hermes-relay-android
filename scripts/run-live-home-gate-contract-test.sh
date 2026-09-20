#!/usr/bin/env bash

set -euo pipefail
umask 077

script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
project_root="$(cd -- "$script_dir/.." && pwd)"
python_bin="${PYTHON_BIN:-python3}"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/hermes-live-home-contract.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

fail() {
    printf '%s\n' "LIVE_HOME_GATE_CONTRACT=FAIL:$1" >&2
    exit 1
}

[[ -x "$project_root/scripts/run-live-home-gate.sh" ]] || fail WRAPPER_NOT_EXECUTABLE
bash -n "$project_root/scripts/run-live-home-gate.sh" || fail WRAPPER_SYNTAX

dispatch_output="$tmp_dir/dispatch.out"
if ! bash "$project_root/scripts/verify-story-dispatch.sh" >"$dispatch_output" 2>"$tmp_dir/dispatch.err"; then
    fail DISPATCH
fi
[[ "$(cat "$dispatch_output")" == "DISPATCH=PASS" ]] || fail DISPATCH_OUTPUT

# Schema 3 records counts, never command names; booleans are not counts.
"$python_bin" -B - "$project_root/scripts/live-home-safe-record.py" <<'PYTEST'
import runpy, sys
module = runpy.run_path(sys.argv[1])
validate = module["validate_capabilities"]
invalid = module["Invalid"]
base = {"heartbeat": True, "timing": "absent", "interrupt": True, "audio": True}
for count in (0, 271):
    validate({**base, "command_count": count})
for count in (-1, True, 1.5, "271", None):
    try:
        validate({**base, "command_count": count})
    except invalid:
        pass
    else:
        raise AssertionError("INVALID_COMMAND_COUNT_ACCEPTED")
try:
    validate({**base, "command_count": 1, "commands": ["private-command"]})
except invalid:
    pass
else:
    raise AssertionError("COMMAND_NAMES_ACCEPTED_IN_SAFE_EVIDENCE")
PYTEST

provider="$tmp_dir/provider"
provider_called="$tmp_dir/provider-called"
"$python_bin" - "$provider" "$provider_called" <<'PY'
import os
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
sentinel = pathlib.Path(sys.argv[2])
path.write_text(
    "#!/usr/bin/env python3\n"
    "import base64, pathlib, sys\n"
    f"pathlib.Path({str(sentinel)!r}).write_text('called')\n"
    "print(base64.urlsafe_b64encode(bytes(32)).decode().rstrip('='))\n",
    encoding="utf-8",
)
os.chmod(path, 0o700)
PY

trace_fetch="$tmp_dir/trace-fetch"
"$python_bin" - "$trace_fetch" <<'PY'
import os
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
path.write_text("#!/usr/bin/env python3\nraise SystemExit(0)\n", encoding="utf-8")
os.chmod(path, 0o700)
PY

credential="$($python_bin - <<'PY'
import base64
print(base64.urlsafe_b64encode(bytes(32)).decode().rstrip('='))
PY
)"
export LIVE_HOME_ROUTE='wss://home.example'
export LIVE_HOME_PROFILE_ID='contract-profile'
export LIVE_DEVICE_CREDENTIAL="$credential"
export LIVE_HOME_HANDLE_PROVIDER="$provider"
export LIVE_HOME_CLAIM_DEVICE_ID='android-live-device'
export LIVE_HOME_CLAIM_MAPPING_ID='android-live-mapping'
export LIVE_HOME_CLAIM_CONFIGURATION_REVISION='1'
export LIVE_HOME_CLAIM_SSH_TARGET='tester@home'
prompt_prefix='contract'
prompt_suffix='probe'
export LIVE_TYPED_PROMPT="${prompt_prefix} typed ${prompt_suffix}"
export LIVE_INTERRUPT_PROMPT="${prompt_prefix} interrupt ${prompt_suffix}"
export LIVE_RECONNECT_PROMPT="${prompt_prefix} reconnect ${prompt_suffix}"
export LIVE_HOME_RUN_ID='contract-inputs'
export LIVE_HOME_TRACE_FETCH_HOOK="$trace_fetch"

if ! "$python_bin" "$project_root/scripts/validate-live-home-inputs.py" \
    >"$tmp_dir/valid-inputs.out" 2>"$tmp_dir/valid-inputs.err"; then
    fail VALID_INPUTS
fi
[[ "$(cat "$tmp_dir/valid-inputs.out")" == "INPUTS=PASS" ]] || fail VALID_INPUT_OUTPUT

if LIVE_HOME_CLAIM_CONFIGURATION_REVISION='01' \
    "$python_bin" "$project_root/scripts/validate-live-home-inputs.py" \
    >"$tmp_dir/invalid-revision.out" 2>"$tmp_dir/invalid-revision.err"; then
    fail INVALID_REVISION_ACCEPTED
fi
[[ "$(cat "$tmp_dir/invalid-revision.out")" == "INPUTS=FAIL:INVALID_BINDING" ]] || fail INVALID_REVISION_OUTPUT

if LIVE_HOME_ROUTE='wss://home.example:notaport' \
    "$python_bin" "$project_root/scripts/validate-live-home-inputs.py" \
    >"$tmp_dir/invalid-port.out" 2>"$tmp_dir/invalid-port.err"; then
    fail INVALID_PORT_ACCEPTED
fi
[[ "$(cat "$tmp_dir/invalid-port.out")" == "INPUTS=FAIL:INVALID_BINDING" ]] || fail INVALID_PORT_OUTPUT

# Provider transport failures must remain diagnostic without echoing child output.
"$python_bin" - "$project_root/scripts/issue-live-home-wake-claim.py" <<'PY_PROVIDER'
import contextlib
import importlib.util
import io
import subprocess
import sys
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("claim_provider", sys.argv[1])
provider = importlib.util.module_from_spec(spec)
spec.loader.exec_module(provider)
sys.argv = ["claim_provider", "--scenario", "handshake", "--run-id", "contract-inputs"]
cases = [
    ({"side_effect": subprocess.TimeoutExpired("ssh", 65)}, "SSH_TIMEOUT"),
    ({"side_effect": OSError()}, "SSH_START"),
    ({"return_value": subprocess.CompletedProcess([], 255, "private child output", "private error")}, "SSH_EXIT"),
    ({"return_value": subprocess.CompletedProcess([], 0, "invalid child output", "private error")}, "INVALID_RESPONSE"),
]
for behavior, reason in cases:
    output, error = io.StringIO(), io.StringIO()
    with patch.object(provider.subprocess, "run", **behavior), contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
        code = provider.main()
    assert code == 20 and output.getvalue() == ""
    assert error.getvalue() == f"HANDLE_PROVIDER=FAIL:{reason}\n"
PY_PROVIDER

state="$tmp_dir/state.json"
safe="$tmp_dir/safe-validation-record.json"
handoff="$tmp_dir/handshake.json"
"$python_bin" "$project_root/scripts/live-home-safe-record.py" \
    --state "$state" --write "$safe" --run-id contract-record \
    >"$tmp_dir/record-init.out" 2>"$tmp_dir/record-init.err"
"$python_bin" - "$handoff" <<'PY'
import json
import pathlib
import sys

record = {
    "schema_version": 3,
    "run_id": "contract-record",
    "scenario": "handshake",
    "status": "pass",
    "reason": None,
    "evidence": {
        "available": True,
        "request_counts": {"observed": True, "prompt_submit": 0, "interrupt": 0},
        "facts": {
            "connection_ready": True,
            "response_id_matched": True,
            "unresolved_turn": False,
            "route": {"class": "home", "id": "contract-route"},
            "capabilities": {
                "heartbeat": True,
                "timing": "absent",
                "command_count": 271,
                "interrupt": False,
                "audio": False,
            },
        },
    },
}
pathlib.Path(sys.argv[1]).write_text(json.dumps(record), encoding="utf-8")
PY
"$python_bin" "$project_root/scripts/live-home-safe-record.py" \
    --state "$state" --write "$safe" --run-id contract-record \
    --set-branch handshake --branch-file "$handoff" \
    >"$tmp_dir/record-branch.out" 2>"$tmp_dir/record-branch.err"
"$python_bin" "$project_root/scripts/live-home-safe-record.py" \
    --state "$state" --write "$safe" --run-id contract-record \
    --branch-status handshake --branch-execution started --branch-reason HOME_PROVENANCE \
    >"$tmp_dir/record-preserve.out" 2>"$tmp_dir/record-preserve.err"
"$python_bin" - "$state" <<'PY'
import json
import pathlib
import sys

record = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
branch = record["branches"]["handshake"]
if branch["status"] != "inconclusive" or not branch["evidence"]["available"]:
    raise SystemExit(1)
if branch["evidence"]["request_counts"]["prompt_submit"] != 0:
    raise SystemExit(1)
PY

no_proof="$tmp_dir/no-proof.json"
"$python_bin" - "$handoff" "$no_proof" <<'PY'
import json
import pathlib
import sys

record = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
record["evidence"]["available"] = False
record["evidence"]["facts"] = None
pathlib.Path(sys.argv[2]).write_text(json.dumps(record), encoding="utf-8")
PY
if "$python_bin" "$project_root/scripts/live-home-safe-record.py" \
    --state "$state" --write "$safe" --run-id contract-record \
    --set-branch handshake --branch-file "$no_proof" \
    >"$tmp_dir/no-proof.out" 2>"$tmp_dir/no-proof.err"; then
    fail PROOFLESS_PASS_ACCEPTED
fi

clean_capture="$tmp_dir/clean-capture"
mkdir "$clean_capture"
printf '%s\n' 'safe' >"$clean_capture/safe.txt"
if ! "$python_bin" "$project_root/scripts/scan-live-home-artifacts.py" \
    "$project_root" "$clean_capture" >"$tmp_dir/clean-scan.out" 2>"$tmp_dir/clean-scan.err"; then
    fail CLEAN_SCAN
fi

printf '\200\377' >"$clean_capture/device-info.pb"
if ! "$python_bin" "$project_root/scripts/scan-live-home-artifacts.py" \
    "$project_root" "$clean_capture" >"$tmp_dir/device-info-scan.out" 2>"$tmp_dir/device-info-scan.err"; then
    fail DEVICE_INFO_METADATA_SCAN
fi
printf '\200\377%s' "$LIVE_TYPED_PROMPT" >"$clean_capture/device-info.pb"
if "$python_bin" "$project_root/scripts/scan-live-home-artifacts.py" \
    "$project_root" "$clean_capture" >"$tmp_dir/device-info-protected-scan.out" 2>"$tmp_dir/device-info-protected-scan.err"; then
    fail DEVICE_INFO_PROTECTED_ACCEPTED
fi
rm -f "$clean_capture/device-info.pb"

printf '%s\n' 'RAW_FRAME=' >"$clean_capture/marker.txt"
if "$python_bin" "$project_root/scripts/scan-live-home-artifacts.py" \
    "$project_root" "$clean_capture" >"$tmp_dir/marker-scan.out" 2>"$tmp_dir/marker-scan.err"; then
    fail MARKER_ACCEPTED
fi
rm -f "$clean_capture/marker.txt"
printf '%s' "$LIVE_TYPED_PROMPT" >"$clean_capture/protected.txt"
if "$python_bin" "$project_root/scripts/scan-live-home-artifacts.py" \
    "$project_root" "$clean_capture" >"$tmp_dir/protected-scan.out" 2>"$tmp_dir/protected-scan.err"; then
    fail PROTECTED_ACCEPTED
fi
rm -f "$clean_capture/protected.txt"
"$python_bin" - "$clean_capture/encoded.txt" <<'PYENCODE'
import base64, os, pathlib, sys
pathlib.Path(sys.argv[1]).write_bytes(base64.urlsafe_b64encode(os.environ["LIVE_TYPED_PROMPT"].encode()).rstrip(b"="))
PYENCODE
if "$python_bin" "$project_root/scripts/scan-live-home-artifacts.py" \
    "$project_root" "$clean_capture" >"$tmp_dir/encoded-scan.out" 2>"$tmp_dir/encoded-scan.err"; then
    fail ENCODED_PROMPT_ACCEPTED
fi
rm -f "$clean_capture/encoded.txt"
printf '%s' 'binary' >"$clean_capture/capture.pcm"
if "$python_bin" "$project_root/scripts/scan-live-home-artifacts.py" \
    "$project_root" "$clean_capture" >"$tmp_dir/media-scan.out" 2>"$tmp_dir/media-scan.err"; then
    fail MEDIA_ACCEPTED
fi

staged_repo="$tmp_dir/staged-repo"
mkdir "$staged_repo"
git -C "$staged_repo" init -q
git -C "$staged_repo" config user.email contract@example.invalid
git -C "$staged_repo" config user.name contract
printf '%s' "$LIVE_TYPED_PROMPT" >"$staged_repo/staged.txt"
git -C "$staged_repo" add staged.txt
staged_capture="$tmp_dir/staged-capture"
mkdir "$staged_capture"
if "$python_bin" "$project_root/scripts/scan-live-home-artifacts.py" \
    "$staged_repo" "$staged_capture" >"$tmp_dir/staged-scan.out" 2>"$tmp_dir/staged-scan.err"; then
    fail STAGED_PROTECTED_ACCEPTED
fi

duplicate_allowlist="$tmp_dir/duplicate-allowlist.json"
public_key="$tmp_dir/public-key"
"$python_bin" - "$duplicate_allowlist" "$public_key" <<'PY'
import base64
import json
import os
import pathlib
import sys

allowlist = {
    "entries": [
        {"signer_key_id": "contract-signer", "fingerprint": "0" * 64},
        {"signer_key_id": "contract-signer", "fingerprint": "1" * 64},
    ]
}
allowlist_path = pathlib.Path(sys.argv[1])
allowlist_path.write_text(json.dumps(allowlist), encoding="utf-8")
os.chmod(allowlist_path, 0o600)
key_path = pathlib.Path(sys.argv[2])
key_path.write_text(base64.urlsafe_b64encode(bytes(32)).decode().rstrip("="), encoding="ascii")
os.chmod(key_path, 0o600)
PY
if "$python_bin" "$project_root/scripts/verify-home-attestation.py" \
    --kind deployment \
    --attestation-file "$tmp_dir/missing-attestation" \
    --public-key-file "$public_key" \
    --allowlist-file "$duplicate_allowlist" \
    >"$tmp_dir/duplicate-allowlist.out" 2>"$tmp_dir/duplicate-allowlist.err"; then
    fail DUPLICATE_ALLOWLIST_ACCEPTED
fi
[[ "$(cat "$tmp_dir/duplicate-allowlist.out")" == "ATTESTATION=FAIL:INVALID" ]] || fail DUPLICATE_ALLOWLIST_OUTPUT

fake_adb="$tmp_dir/fake-adb"
"$python_bin" - "$fake_adb" "$tmp_dir/adb-called" <<'PY'
import os
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
sentinel = pathlib.Path(sys.argv[2])
path.write_text(
    "#!/usr/bin/env python3\n"
    "import pathlib\n"
    f"pathlib.Path({str(sentinel)!r}).write_text('called')\n"
    "raise SystemExit(1)\n",
    encoding="utf-8",
)
os.chmod(path, 0o700)
PY
export LIVE_HOME_ATTESTATION_FILE="$tmp_dir/missing-deployment"
export LIVE_HOME_TRACE_ATTESTATION_FILE="$tmp_dir/missing-trace"
export LIVE_HOME_ATTESTATION_PUBLIC_KEY_FILE="$tmp_dir/missing-key"
export LIVE_HOME_ATTESTATION_KEY_ALLOWLIST_FILE="$tmp_dir/missing-allowlist"
export LIVE_HOME_RUN_ID='contract-provenance'
export LIVE_HOME_RUN_BASE_DIR="$tmp_dir/runs"
export ADB_BIN="$fake_adb"
export GRADLE_BIN="$tmp_dir/fake-gradle"
wrapper_output="$tmp_dir/wrapper.out"
wrapper_error="$tmp_dir/wrapper.err"
if "$project_root/scripts/run-live-home-gate.sh" >"$wrapper_output" 2>"$wrapper_error"; then
    fail PROVENANCE_ACCEPTED
fi
[[ "$(cat "$wrapper_output")" == $'LIVE_HOME_GATE=NOT_RUN\nEXIT_CODE=20' ]] || fail WRAPPER_OUTPUT
[[ ! -e "$tmp_dir/adb-called" ]] || fail ADB_STARTED_BEFORE_PROVENANCE
[[ ! -e "$provider_called" ]] || fail HANDLE_ISSUED_BEFORE_PROVENANCE

invalid_args_output="$tmp_dir/invalid-args.out"
if "$project_root/scripts/run-live-home-gate.sh" unsupported \
    >"$invalid_args_output" 2>"$tmp_dir/invalid-args.err"; then
    fail UNSUPPORTED_ARGS_ACCEPTED
fi
[[ "$(cat "$invalid_args_output")" == $'LIVE_HOME_GATE=INVALID\nEXIT_CODE=64' ]] || fail UNSUPPORTED_ARGS_OUTPUT

printf '%s\n' 'LIVE_HOME_GATE_CONTRACT=PASS'

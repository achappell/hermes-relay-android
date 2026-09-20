#!/usr/bin/env bash

set -euo pipefail
umask 077

script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
project_root="$(cd -- "$script_dir/.." && pwd)"
python_bin="${PYTHON_BIN:-python3}"
adb_bin="${ADB_BIN:-adb}"
gradle_bin="${GRADLE_BIN:-$project_root/gradlew}"
package_name="com.achappell.hermesrelay"
test_package="${LIVE_HOME_TEST_PACKAGE:-$package_name.test}"
test_runner="${LIVE_HOME_TEST_RUNNER:-androidx.test.runner.AndroidJUnitRunner}"
live_class="com.achappell.hermesrelay.LiveRelayHandshakeTest"
preflight_class="com.achappell.hermesrelay.AudioOutputPreflightTest#verifyPhysicalAudioOutput"
safe_record="$project_root/scripts/live-home-safe-record.py"
input_validator="$project_root/scripts/validate-live-home-inputs.py"
attestation_verifier="$project_root/scripts/verify-home-attestation.py"
dispatch_verifier="$project_root/scripts/verify-story-dispatch.sh"
artifact_scanner="$project_root/scripts/scan-live-home-artifacts.py"
report_root="$project_root/app/build/outputs/androidTest-results/connected"

requested_exit=20
trap_active=true
run_id="${LIVE_HOME_RUN_ID:-preflight-$(date +%s)-$$}"
if [[ ! "$run_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]]; then
    run_id="preflight-$(date +%s)-$$"
fi
run_base="${LIVE_HOME_RUN_BASE_DIR:-$project_root/.bmad-loop/live-home-runs}"
run_dir="$run_base/hermes-live-home-$run_id"
capture_dir=""
state_file=""
safe_record_file=""
device_serial="${LIVE_DEVICE_SERIAL:-}"
serial_fingerprint=""
device_model=""
device_api=""
observed_route_class=""
observed_route_id=""
default_non_live_count=""
default_live_count=""
live_report_count=""

scenario_names=(handshake typed_audio interrupt reconnect)

safe_output() {
    # Keep all child output in the owner-only temporary capture directory.
    # The only wrapper stdout is the final allowlisted result token.
    :
}

initialiseRun() {
    mkdir -p "$run_base"
    if [[ -e "$run_dir" ]]; then
        requested_exit=20
        return 1
    fi
    mkdir "$run_dir"
    chmod 700 "$run_dir"
    capture_dir="$(mktemp -d "${TMPDIR:-/tmp}/hermes-live-home.XXXXXX")"
    chmod 700 "$capture_dir"
    state_file="$run_dir/state.json"
    safe_record_file="$run_dir/safe-validation-record.json"
    "$python_bin" "$safe_record" \
        --state "$state_file" \
        --write "$safe_record_file" \
        --run-id "$run_id" \
        >"$capture_dir/initial-record.out" 2>"$capture_dir/initial-record.err"
}

recordCommand() {
    local command_name="$1"
    local status="$2"
    local exit_code="$3"
    local report_count="${4:-}"
    local args=(
        --state "$state_file" --write "$safe_record_file"
        --command "$command_name" --command-status "$status" --command-exit "$exit_code"
    )
    if [[ "$command_name" != audio_preflight ]]; then
        args+=(--command-report-count "${report_count:-0}")
    fi
    "$python_bin" "$safe_record" "${args[@]}" \
        >"$capture_dir/record-command-${command_name}.out" \
        2>"$capture_dir/record-command-${command_name}.err" || requested_exit=30
}

recordBranchStatus() {
    local scenario="$1"
    local execution="$2"
    local reason="$3"
    "$python_bin" "$safe_record" \
        --state "$state_file" --write "$safe_record_file" \
        --branch-status "$scenario" --branch-execution "$execution" \
        --branch-reason "$reason" \
        >"$capture_dir/record-branch-${scenario}.out" \
        2>"$capture_dir/record-branch-${scenario}.err" || requested_exit=30
}

recordAllBranches() {
    local execution="$1"
    local reason="$2"
    local scenario
    for scenario in "${scenario_names[@]}"; do
        recordBranchStatus "$scenario" "$execution" "$reason"
    done
}

markHarnessFailure() {
    local scenario="${1:-}"
    if [[ -n "$scenario" ]]; then
        recordBranchStatus "$scenario" started HARNESS_FAILURE
    fi
    recordCommand live fail "${2:-30}" "${live_report_count:-0}"
    requested_exit=30
}

validateLiveArguments() {
    if [[ -z "${LIVE_HOME_ATTESTATION_FILE:-}" ||
        -z "${LIVE_HOME_TRACE_ATTESTATION_FILE:-}" ||
        -z "${LIVE_HOME_ATTESTATION_PUBLIC_KEY_FILE:-}" ||
        -z "${LIVE_HOME_ATTESTATION_KEY_ALLOWLIST_FILE:-}" ||
        -z "${LIVE_HOME_TRACE_FETCH_HOOK:-}" ]]; then
        recordAllBranches not-started HOME_PROVENANCE
        return 20
    fi
    if ! "$python_bin" "$input_validator" \
        >"$capture_dir/input-validator.out" \
        2>"$capture_dir/input-validator.err"; then
        local result=20
        if grep -q '^INPUTS=FAIL:MISSING_PAIRING$' "$capture_dir/input-validator.out"; then
            recordAllBranches not-started MISSING_PAIRING
        else
            recordAllBranches not-started INVALID_BINDING
        fi
        return "$result"
    fi
    # The deployment envelope is available before traffic. The trace envelope
    # is intentionally a post-run input and may not exist yet.
    for path in \
        "$LIVE_HOME_ATTESTATION_FILE" \
        "$LIVE_HOME_ATTESTATION_PUBLIC_KEY_FILE" \
        "$LIVE_HOME_ATTESTATION_KEY_ALLOWLIST_FILE"; do
        if [[ ! -f "$path" ]]; then
            recordAllBranches not-started HOME_PROVENANCE
            return 20
        fi
    done
    if [[ -e "$LIVE_HOME_TRACE_ATTESTATION_FILE" ]]; then
        recordAllBranches not-started HOME_PROVENANCE
        return 20
    fi
    return 0
}

verifyDeploymentAttestation() {
    mkdir -p "$run_dir/provenance"
    if "$python_bin" "$attestation_verifier" \
        --kind deployment \
        --attestation-file "$LIVE_HOME_ATTESTATION_FILE" \
        --public-key-file "$LIVE_HOME_ATTESTATION_PUBLIC_KEY_FILE" \
        --allowlist-file "$LIVE_HOME_ATTESTATION_KEY_ALLOWLIST_FILE" \
        --projection-file "$run_dir/provenance/deployment.json" \
        >"$capture_dir/deployment-attestation.out" \
        2>"$capture_dir/deployment-attestation.err"; then
        if "$python_bin" "$safe_record" \
            --state "$state_file" --write "$safe_record_file" \
            --deployment-attested true \
            >"$capture_dir/record-deployment-attested.out" \
            2>"$capture_dir/record-deployment-attested.err"; then
            return 0
        fi
        recordAllBranches not-started HARNESS_FAILURE
        requested_exit=30
        return 30
    fi
    recordAllBranches not-started HOME_PROVENANCE
    return 20
}

verifyDispatchHash() {
    if bash "$dispatch_verifier" \
        >"$capture_dir/dispatch.out" \
        2>"$capture_dir/dispatch.err"; then
        return 0
    fi
    recordAllBranches not-started HARNESS_FAILURE
    requested_exit=64
    return 64
}

serialFingerprint() {
    "$python_bin" - "$device_serial" <<'PY'
import hashlib
import sys
print(hashlib.sha256(sys.argv[1].encode("utf-8")).hexdigest())
PY
}

recordDevice() {
    local args=(
        --state "$state_file" --write "$safe_record_file"
        --device-status observed
        --device-model "$device_model"
        --device-api "$device_api"
        --device-physical true
        --device-serial-fingerprint "$serial_fingerprint"
    )
    "$python_bin" "$safe_record" "${args[@]}" \
        >"$capture_dir/record-device.out" 2>"$capture_dir/record-device.err" || requested_exit=30
}

markDeviceNotRun() {
    local reason="$1"
    recordAllBranches not-started "$reason"
    requested_exit=20
}

selectDevice() {
    if [[ -z "$device_serial" ]]; then
        markDeviceNotRun DEVICE_SELECTION
        return 20
    fi
    if ! "$adb_bin" devices -l \
        >"$capture_dir/adb-devices.out" \
        2>"$capture_dir/adb-devices.err"; then
        markDeviceNotRun DEVICE_SELECTION
        return 20
    fi
    local device_state
    device_state="$($python_bin - "$device_serial" "$capture_dir/adb-devices.out" <<'PY'
import pathlib
import sys

serial = sys.argv[1]
path = pathlib.Path(sys.argv[2])
matches = []
for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
    fields = line.split()
    if fields and fields[0] == serial:
        matches.append(fields[1] if len(fields) > 1 else "")
if len(matches) != 1:
    print("invalid")
elif matches[0] == "device":
    print("device")
else:
    print("unavailable")
PY
    )"
    if [[ "$device_state" != device ]]; then
        markDeviceNotRun DEVICE_SELECTION
        return 20
    fi

    local model api qemu_kernel qemu_boot
    if ! model="$($adb_bin -s "$device_serial" shell getprop ro.product.model 2>"$capture_dir/getprop-model.err")"; then
        markDeviceNotRun DEVICE_SELECTION
        return 20
    fi
    if ! api="$($adb_bin -s "$device_serial" shell getprop ro.build.version.sdk 2>"$capture_dir/getprop-api.err")"; then
        markDeviceNotRun DEVICE_SELECTION
        return 20
    fi
    if ! qemu_kernel="$($adb_bin -s "$device_serial" shell getprop ro.kernel.qemu 2>"$capture_dir/getprop-qemu-kernel.err")" ||
        ! qemu_boot="$($adb_bin -s "$device_serial" shell getprop ro.boot.qemu 2>"$capture_dir/getprop-qemu-boot.err")"; then
        markDeviceNotRun DEVICE_SELECTION
        return 20
    fi
    model="${model//$'\r'/}"
    model="${model//$'\n'/}"
    api="${api//$'\r'/}"
    api="${api//$'\n'/}"
    qemu_kernel="${qemu_kernel//$'\r'/}"
    qemu_kernel="${qemu_kernel//$'\n'/}"
    qemu_boot="${qemu_boot//$'\r'/}"
    qemu_boot="${qemu_boot//$'\n'/}"
    if [[ "$model" != "Pixel 6a" || ( -n "$qemu_kernel" && "$qemu_kernel" != 0 ) ||
        ( -n "$qemu_boot" && "$qemu_boot" != 0 ) || ! "$api" =~ ^[0-9]+$ ]]; then
        markDeviceNotRun WRONG_DEVICE
        return 20
    fi
    device_model="$model"
    device_api="$api"
    serial_fingerprint="$(serialFingerprint)"
    recordDevice
    return 0
}

devicePathAbsent() {
    local relative_path="$1"
    "$adb_bin" -s "$device_serial" shell run-as "$package_name" test ! -e "$relative_path" \
        >"$capture_dir/device-path.out" 2>"$capture_dir/device-path.err"
}

deviceRunDirectoryAbsent() {
    "$adb_bin" -s "$device_serial" shell run-as "$package_name" test ! -e \
        "cache/hermes-live-home/$run_id" \
        >"$capture_dir/device-run-directory.out" \
        2>"$capture_dir/device-run-directory.err"
}

isolateReports() {
    local label="$1"
    local destination="$run_dir/reports/$label"
    mkdir -p "$run_dir/reports"
    if [[ -e "$report_root" ]]; then
        mv "$report_root" "$run_dir/reports/stale-$label"
    fi
    mkdir -p "$report_root"
    mkdir -p "$destination"
}

parseReports() {
    local root="$1"
    "$python_bin" - "$root" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1])
files = sorted(root.rglob("*.xml"))
total = 0
live = 0
for path in files:
    tree = ET.parse(path)
    for case in tree.getroot().iter("testcase"):
        total += 1
        if "LiveRelay" in case.attrib.get("classname", ""):
            live += 1
print(f"REPORT_FILES={len(files)}")
print(f"REPORT_TOTAL={total}")
print(f"REPORT_LIVE={live}")
PY
}

readReportCounts() {
    local root="$1"
    local output
    output="$(parseReports "$root" 2>"$capture_dir/parse-reports.err")" || return 1
    report_files="$(printf '%s\n' "$output" | awk -F= '$1=="REPORT_FILES"{print $2}')"
    report_total="$(printf '%s\n' "$output" | awk -F= '$1=="REPORT_TOTAL"{print $2}')"
    report_live="$(printf '%s\n' "$output" | awk -F= '$1=="REPORT_LIVE"{print $2}')"
    [[ "$report_files" =~ ^[1-9][0-9]*$  && "$report_total" =~ ^[1-9][0-9]*$ &&
        "$report_live" =~ ^[0-9]+$  ]]
}

runGradleCaptured() {
    local label="$1"
    shift
    local output="$capture_dir/gradle-$label.out"
    local error="$capture_dir/gradle-$label.err"
    # Keep the app and test APK available for the direct audio preflight
    # and run-as handoff collection after each connected test invocation.
    env ANDROID_SERIAL="$device_serial" "$gradle_bin" "$@" \
        -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
        >"$output" 2>"$error"
}

runDefaultIsolation() {
    isolateReports default
    local status=0
    if runGradleCaptured default connectedDebugAndroidTest --no-daemon; then
        status=0
    else
        status=$?
    fi
    if [[ "$status" != 0 ]] || ! readReportCounts "$report_root" ||
        [[ "$report_total" == 0 || "$report_live" != 0 ||
            $((report_total - report_live)) -le 0 ]]; then
        recordCommand default fail "$status" "${report_total:-0}"
        requested_exit=30
        return 30
    fi
    default_non_live_count=$((report_total - report_live))
    default_live_count="$report_live"
    recordCommand default pass 0 "$report_total"
    rm -rf "$run_dir/reports/default"
    mv "$report_root" "$run_dir/reports/default"
    mkdir -p "$report_root"
    return 0
}

preflightToken() {
    local output="$1"
    "$python_bin" - "$output" <<'PY'
import pathlib
import sys

text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
tokens = []
malformed = False
for raw_line in text.splitlines():
    line = raw_line.strip()
    if line.startswith("INSTRUMENTATION_STATUS:"):
        line = line[len("INSTRUMENTATION_STATUS:"):].strip()
    if "AUDIO_PREFLIGHT=" in line:
        if line.startswith("AUDIO_PREFLIGHT="):
            tokens.append(line)
        else:
            malformed = True
if not malformed and tokens == ["AUDIO_PREFLIGHT=PASS"]:
    print("PREFLIGHT=PASS")
elif not malformed and tokens == ["AUDIO_PREFLIGHT=FAIL:AUDIO_OUTPUT"]:
    print("PREFLIGHT=FAIL:AUDIO_OUTPUT")
else:
    print("PREFLIGHT=INVALID")
PY
}

runInstrumentWithTimeout() {
    local output="$1"
    local error="$2"
    shift 2
    "$python_bin" - "$output" "$error" "$@" <<'PY'
import os
import pathlib
import signal
import subprocess
import sys

output = pathlib.Path(sys.argv[1])
error = pathlib.Path(sys.argv[2])
command = sys.argv[3:]
if not command:
    raise SystemExit(64)
with output.open("wb") as output_stream, error.open("wb") as error_stream:
    process = subprocess.Popen(
        command,
        stdin=subprocess.DEVNULL,
        stdout=output_stream,
        stderr=error_stream,
        start_new_session=True,
    )
    try:
        status = process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
        raise SystemExit(124)
raise SystemExit(status)
PY
}

runDeviceAudioPreflight() {
    local output="$capture_dir/audio-preflight.out"
    local error="$capture_dir/audio-preflight.err"
    local status=0
    if runInstrumentWithTimeout "$output" "$error" "$adb_bin" -s "$device_serial" shell am instrument -w -r \
        -e notAnnotation org.junit.Ignore \
        -e class "$preflight_class" \
        "$test_package/$test_runner"; then
        status=0
    else
        status=$?
    fi
    if [[ "$status" != 0 ]]; then
        recordCommand audio_preflight fail "$status"
        recordAllBranches not-started HARNESS_FAILURE
        requested_exit=30
        return 30
    fi
    local token
    token="$(preflightToken "$output" 2>"$capture_dir/audio-preflight-parse.err")" || token=PREFLIGHT=INVALID
    case "$token" in
        PREFLIGHT=PASS)
            recordCommand audio_preflight pass 0
            "$python_bin" "$safe_record" \
                --state "$state_file" --write "$safe_record_file" \
                --device-status observed --device-audio-output true \
                >"$capture_dir/record-audio-device.out" \
                2>"$capture_dir/record-audio-device.err" || requested_exit=30
            return 0
            ;;
        PREFLIGHT=FAIL:AUDIO_OUTPUT)
            recordCommand audio_preflight inconclusive 0
            if ! "$python_bin" "$safe_record" \
                --state "$state_file" --write "$safe_record_file" \
                --device-status observed --device-audio-output false \
                >"$capture_dir/record-audio-device-failure.out" \
                2>"$capture_dir/record-audio-device-failure.err"; then
                requested_exit=30
                return 30
            fi
            recordAllBranches not-started AUDIO_OUTPUT
            requested_exit=20
            return 20
            ;;
        *)
            recordCommand audio_preflight fail "$status"
            recordAllBranches not-started HARNESS_FAILURE
            requested_exit=30
            return 30
            ;;
    esac
}

scenarioResultPath() {
    printf '%s\n' "cache/hermes-live-home/$run_id/$1.json"
}

validateHandoffFile() {
    local scenario="$1"
    local path="$2"
    "$python_bin" "$safe_record" \
        --state "$state_file" --write "$safe_record_file" \
        --run-id "$run_id" --set-branch "$scenario" --branch-file "$path" \
        >"$capture_dir/record-handoff-$scenario.out" \
        2>"$capture_dir/record-handoff-$scenario.err"
}

readObservedRoute() {
    "$python_bin" - "$run_dir/handoffs/handshake.json" <<'PY'
import json
import pathlib
import sys

record = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
route = record.get("evidence", {}).get("facts", {}).get("route")
if not isinstance(route, dict) or set(route) != {"class", "id"}:
    raise SystemExit(1)
if not isinstance(route.get("class"), str) or not isinstance(route.get("id"), str):
    raise SystemExit(1)
print(f"ROUTE_CLASS={route['class']}")
print(f"ROUTE_ID={route['id']}")
PY
}

collectHandoff() {
    local scenario="$1"
    local relative_path
    relative_path="$(scenarioResultPath "$scenario")"
    local temporary="$capture_dir/handoff-$scenario.json"
    if ! "$adb_bin" -s "$device_serial" exec-out run-as "$package_name" cat "$relative_path" \
        >"$temporary" 2>"$capture_dir/handoff-$scenario.err"; then
        markHarnessFailure "$scenario" 30
        return 30
    fi
    if ! validateHandoffFile "$scenario" "$temporary"; then
        markHarnessFailure "$scenario" 30
        return 30
    fi
    mkdir -p "$run_dir/handoffs"
    cp "$temporary" "$run_dir/handoffs/$scenario.json"
    if ! "$adb_bin" -s "$device_serial" shell run-as "$package_name" rm "$relative_path" \
        >"$capture_dir/remove-handoff-$scenario.out" \
        2>"$capture_dir/remove-handoff-$scenario.err"; then
        markHarnessFailure "$scenario" 30
        return 30
    fi
    if ! devicePathAbsent "$relative_path"; then
        markHarnessFailure "$scenario" 30
        return 30
    fi
    return 0
}

# Encode in memory so spaces and shell punctuation remain one ADB argument.
encodedPrompt() {
    "$python_bin" - "$1" <<'PYENCODE'
import base64, os, sys
print(base64.urlsafe_b64encode(os.environ[sys.argv[1]].encode("utf-8")).decode("ascii").rstrip("="))
PYENCODE
}

runLiveScenario() {
    local scenario="$1"
    local handle_arg
    if ! handle_arg="$("$LIVE_HOME_HANDLE_PROVIDER" --scenario "$scenario" --run-id "$run_id" \
        2>"$capture_dir/handle-provider-$scenario.err")"; then
        # Publish only the helper's fixed diagnostic tokens, never SSH output.
        grep -E '^HANDLE_PROVIDER=FAIL:(SSH_TIMEOUT|SSH_START|SSH_EXIT|INVALID_RESPONSE)$' \
            "$capture_dir/handle-provider-$scenario.err" \
            >"$run_dir/handle-provider-$scenario.txt" || true
        recordBranchStatus "$scenario" not-started HOME_UNAVAILABLE
        return 20
    fi
    if [[ ! "$handle_arg" =~ ^[A-Za-z0-9_-]{43}$ ]]; then
        recordBranchStatus "$scenario" not-started INVALID_BINDING
        return 20
    fi
    local relative_path
    relative_path="$(scenarioResultPath "$scenario")"
    if ! devicePathAbsent "$relative_path"; then
        markHarnessFailure "$scenario" 30
        return 30
    fi

    local gradle_args=(
        connectedDebugAndroidTest --no-daemon --no-configuration-cache
        -Pandroid.testInstrumentationRunnerArguments.notAnnotation=org.junit.Ignore
        -Pandroid.testInstrumentationRunnerArguments.class="$live_class"
        -Pandroid.testInstrumentationRunnerArguments.scenario="$scenario"
        -Pandroid.testInstrumentationRunnerArguments.homeProfileId="$LIVE_HOME_PROFILE_ID"
        -Pandroid.testInstrumentationRunnerArguments.homeRoute="$LIVE_HOME_ROUTE"
        -Pandroid.testInstrumentationRunnerArguments.homeCredential="$LIVE_DEVICE_CREDENTIAL"
        -Pandroid.testInstrumentationRunnerArguments.homeConversationHandle="$handle_arg"
        -Pandroid.testInstrumentationRunnerArguments.liveRunId="$run_id"
        -Pandroid.testInstrumentationRunnerArguments.liveDeviceSerialFingerprint="$serial_fingerprint"
        -Pandroid.testInstrumentationRunnerArguments.relayClientId="android-live-gate"
        -Pandroid.testInstrumentationRunnerArguments.relayDeviceId="android-live-device"
    )
    case "$scenario" in
        typed_audio)
            gradle_args+=("-Pandroid.testInstrumentationRunnerArguments.typedPromptBase64=$(encodedPrompt LIVE_TYPED_PROMPT)")
            ;;
        interrupt)
            gradle_args+=("-Pandroid.testInstrumentationRunnerArguments.interruptPromptBase64=$(encodedPrompt LIVE_INTERRUPT_PROMPT)")
            ;;
        reconnect)
            gradle_args+=("-Pandroid.testInstrumentationRunnerArguments.reconnectPromptBase64=$(encodedPrompt LIVE_RECONNECT_PROMPT)")
            ;;
    esac
    local status=0
    if runGradleCaptured "live-$scenario" "${gradle_args[@]}"; then
        status=0
    else
        status="$?"
    fi
    if [[ "$status" != 0 ]]; then
        markHarnessFailure "$scenario" "$status"
        return 30
    fi
    if ! readReportCounts "$report_root" || [[ "$report_total" != 1 || "$report_live" != 1 ]]; then
        markHarnessFailure "$scenario" 30
        return 30
    fi
    local report_destination="$run_dir/reports/live-$scenario"
    rm -rf "$report_destination"
    mv "$report_root" "$report_destination"
    mkdir -p "$report_root"
    if ! collectHandoff "$scenario"; then
        return 30
    fi
    live_report_count=$((live_report_count + report_total))
    if [[ "$scenario" == handshake ]]; then
        if ! "$python_bin" - "$run_dir/handoffs/handshake.json" <<'PY'
import json
import pathlib
import sys

record = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
raise SystemExit(0 if record.get("status") == "pass" else 1)
PY
        then
            return 0
        fi
        local route_output
        route_output="$(readObservedRoute 2>"$capture_dir/read-route.err")" || {
            markHarnessFailure "$scenario" 30
            return 30
        }
        observed_route_class="$(printf '%s\n' "$route_output" | awk -F= '$1=="ROUTE_CLASS"{print $2}')"
        observed_route_id="$(printf '%s\n' "$route_output" | awk -F= '$1=="ROUTE_ID"{print $2}')"
        if [[ -z "$observed_route_class" || -z "$observed_route_id" ]]; then
            markHarnessFailure "$scenario" 30
            return 30
        fi
    fi
    return 0
}

runLiveScenarios() {
    live_report_count=0
    if ! deviceRunDirectoryAbsent; then
        recordAllBranches not-started INVALID_BINDING
        requested_exit=20
        return 20
    fi
    local scenario
    local status
    for scenario in handshake typed_audio interrupt reconnect; do
        if runLiveScenario "$scenario"; then
            :
        else
            status="$?"
            return "$status"
        fi
    done
    recordCommand live pass 0 "$live_report_count"
    return 0
}

fetchRunTrace() {
    "$LIVE_HOME_TRACE_FETCH_HOOK" --run-id "$run_id" \
        --output "$LIVE_HOME_TRACE_ATTESTATION_FILE" \
        >"$capture_dir/trace-fetch.out" 2>"$capture_dir/trace-fetch.err"
}

verifyRunTrace() {
    if [[ -z "$observed_route_class" || -z "$observed_route_id" || -z "$serial_fingerprint" ]]; then
        return 1
    fi
    "$python_bin" "$attestation_verifier" \
        --kind run-trace \
        --attestation-file "$LIVE_HOME_TRACE_ATTESTATION_FILE" \
        --public-key-file "$LIVE_HOME_ATTESTATION_PUBLIC_KEY_FILE" \
        --allowlist-file "$LIVE_HOME_ATTESTATION_KEY_ALLOWLIST_FILE" \
        --run-id "$run_id" \
        --device-serial-fingerprint "$serial_fingerprint" \
        --route-class "$observed_route_class" \
        --route-id "$observed_route_id" \
        --projection-file "$run_dir/provenance/trace.json" \
        >"$capture_dir/trace-attestation.out" \
        2>"$capture_dir/trace-attestation.err"
}

makeHomeProjection() {
    "$python_bin" - "$run_dir/provenance/deployment.json" "$run_dir/provenance/trace.json" \
        "$run_dir/provenance/home.json" <<'PY'
import json
import os
import pathlib
import sys

deployment = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
trace = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
fields = ("deployment_id", "deployment_revision", "adapter", "signer_key_id", "route_class", "route_id")
if any(deployment.get(key) != trace.get(key) for key in fields):
    raise SystemExit(1)
home = dict(deployment)
for key in ("trace_run_id", "trace_digest", "device_serial_fingerprint"):
    home[key] = trace[key]
if set(home) != set(fields) | {"trace_run_id", "trace_digest", "device_serial_fingerprint"}:
    raise SystemExit(1)
destination = pathlib.Path(sys.argv[3])
temporary = pathlib.Path(str(destination) + ".tmp")
temporary.write_text(json.dumps(home, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
os.chmod(temporary, 0o600)
os.replace(temporary, destination)
PY
}

scanProtectedArtifacts() {
    if ! "$python_bin" "$artifact_scanner" "$project_root" "$run_dir" \
        >"$capture_dir/scan-run.out" 2>"$capture_dir/scan-run.err"; then
        return 1
    fi
    if ! "$python_bin" "$artifact_scanner" "$project_root" "$capture_dir" \
        >"$capture_dir/scan-capture.out" 2>"$capture_dir/scan-capture.err"; then
        return 1
    fi
}

writeSafeValidationRecord() {
    # The safe-record utility has already atomically maintained this file after
    # each state transition. Re-run validation before publication.
    "$python_bin" - "$safe_record_file" <<'PY'
import json
import pathlib
import sys

record = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if set(record) != {
    "schema_version", "status", "exit_code", "run_id", "device", "provenance",
    "home", "branches", "commands", "overall_rule",
}:
    raise SystemExit(1)
if record["exit_code"] not in (0, 20, 30, 64):
    raise SystemExit(1)
print("SAFE_RECORD=VALID")
PY
}

publishSafeResult() {
    if [[ ! -f "$safe_record_file" ]]; then
        return 1
    fi
    writeSafeValidationRecord >"$capture_dir/write-safe-record.out" 2>"$capture_dir/write-safe-record.err"
    cp "$safe_record_file" "$run_dir/validation-safe.json"
}

currentStatus() {
    "$python_bin" - "$state_file" <<'PY'
import json
import pathlib
import sys
print(json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))["status"])
PY
}

onExit() {
    local status="$?"
    if [[ "$trap_active" != true ]]; then
        exit "$status"
    fi
    trap_active=false
    if [[ -n "$capture_dir" && -d "$capture_dir" ]]; then
        if ! scanProtectedArtifacts; then
            requested_exit=30
            recordAllBranches started HARNESS_FAILURE || true
            recordCommand live fail 30 "${live_report_count:-0}" || true
        fi
    fi
    if [[ -n "$state_file" && -f "$state_file" ]]; then
        if ! publishSafeResult; then
            requested_exit=30
        fi
    fi
    local final_code="$requested_exit"
    if [[ "$requested_exit" != 64 && "$requested_exit" != 30 ]]; then
        case "$(currentStatus 2>/dev/null || true)" in
            failed) final_code=30 ;;
            live-pass) final_code=0 ;;
            *) final_code=20 ;;
        esac
    fi
    if [[ -n "$capture_dir" && -d "$capture_dir" ]]; then
        rm -rf "$capture_dir"
    fi
    printf '%s\n' "LIVE_HOME_GATE=$(if [[ "$final_code" == 0 ]]; then printf PASS; elif [[ "$final_code" == 64 ]]; then printf INVALID; elif [[ "$final_code" == 30 ]]; then printf FAIL; else printf NOT_RUN; fi)"
    printf '%s\n' "EXIT_CODE=$final_code"
    exit "$final_code"
}

trap onExit EXIT

main() {
    if (( $# != 0 )); then
        requested_exit=64
        return 64
    fi
    if ! initialiseRun; then
        requested_exit=20
        return 20
    fi
    if validateLiveArguments; then
        :
    else
        requested_exit=$?
        return "$requested_exit"
    fi
    if verifyDeploymentAttestation; then
        :
    else
        requested_exit="$?"
        return "$requested_exit"
    fi
    if ! verifyDispatchHash; then
        requested_exit=64
        return 64
    fi
    if selectDevice; then
        :
    else
        requested_exit=$?
        return "$requested_exit"
    fi

    local build_status=0
    if runGradleCaptured build assembleDebug assembleDebugAndroidTest --no-daemon; then
        build_status=0
    else
        build_status="$?"
    fi
    if [[ "$build_status" != 0 ]]; then
        recordCommand default fail "$build_status" 0
        requested_exit=30
        return 30
    fi

    if ! runDefaultIsolation; then
        return 30
    fi
    "$python_bin" "$safe_record" \
        --state "$state_file" --write "$safe_record_file" \
        --device-status observed \
        --default-non-live-count "$default_non_live_count" \
        --default-live-count "$default_live_count" \
        >"$capture_dir/record-default-device.out" \
        2>"$capture_dir/record-default-device.err" || {
            requested_exit=30
            return 30
        }

    if runDeviceAudioPreflight; then
        :
    else
        requested_exit=$?
        return "$requested_exit"
    fi
    if runLiveScenarios; then
        :
    else
        requested_exit=$?
        return "$requested_exit"
    fi

    if ! fetchRunTrace; then
        local scenario
        for scenario in "${scenario_names[@]}"; do
            recordBranchStatus "$scenario" started HOME_PROVENANCE
        done
        requested_exit=20
        return 20
    fi

    if ! verifyRunTrace; then
        case "$(currentStatus 2>/dev/null || true)" in
            failed)
                requested_exit=30
                ;;
            *)
                local scenario
                for scenario in "${scenario_names[@]}"; do
                    recordBranchStatus "$scenario" started HOME_PROVENANCE
                done
                requested_exit=20
                ;;
        esac
        return "$requested_exit"
    fi
    if ! makeHomeProjection; then
        markHarnessFailure "" 30
        return 30
    fi
    if ! "$python_bin" "$safe_record" \
        --state "$state_file" --write "$safe_record_file" \
        --deployment-attested true --trace-attested true \
        --home-file "$run_dir/provenance/home.json" \
        >"$capture_dir/record-provenance.out" \
        2>"$capture_dir/record-provenance.err"; then
        requested_exit=30
        return 30
    fi
    requested_exit=0
    return 0
}

main "$@"

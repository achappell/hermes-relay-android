#!/usr/bin/env python3
"""Issue one disposable Home conversation handle for a live gate branch."""

from __future__ import annotations

import base64
import json
import os
import re
import subprocess
import sys


SCENARIOS = {"handshake", "typed_audio", "interrupt", "reconnect"}
TOKEN = re.compile(r"[A-Za-z0-9_-]{43}\Z")
IDENTIFIER = re.compile(r"[A-Za-z0-9._:-]{1,128}\Z")
SSH_TARGET = re.compile(r"[A-Za-z0-9._-]+@[A-Za-z0-9.-]+\Z")
RUN_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")

POWERSHELL = r"""
$ErrorActionPreference = 'Stop'
$Error.Clear()
try {
    $request = [Console]::In.ReadToEnd() | ConvertFrom-Json
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while ([DateTime]::UtcNow -lt $deadline) {
        $claim = @{
            schema = 1
            claim_id = 'android-live-' + [Guid]::NewGuid().ToString('N')
            device_id = [string] $request.device_id
            wake_mapping_id = [string] $request.mapping_id
            configuration_revision = [int] $request.revision
            observation = @{
                source = 'android_live_gate'
                run_id = [string] $request.run_id
                scenario = [string] $request.scenario
            }
            acoustic_evidence = @{ value = 1.0; basis = 'operator_test' }
            availability = 'ready'
        }
        $body = $claim | ConvertTo-Json -Depth 6 -Compress
        try {
            $response = Invoke-RestMethod `
                -Method Post `
                -Uri 'http://127.0.0.1:8780/api/v1/wake-claims' `
                -Headers @{ Authorization = ('Device ' + [string] $request.credential) } `
                -ContentType 'application/json' `
                -Body $body `
                -TimeoutSec 15
            if ($response.decision -ne 'granted' -or
                [string] $response.conversation_handle -notmatch '^[A-Za-z0-9_-]{43}$') {
                exit 4
            }
            [Console]::Out.Write([string] $response.conversation_handle)
            exit 0
        }
        catch {
            $status = 0
            try { $status = [int] $_.Exception.Response.StatusCode } catch {}
            if ($status -ne 409) { exit 4 }
            Start-Sleep -Seconds 2
        }
    }
    exit 5
}
catch {
    exit 4
}
"""


def _powershell_argument() -> str:
    encoded = POWERSHELL.encode("utf-16le")
    return base64.b64encode(encoded).decode("ascii")


def main() -> int:
    if len(sys.argv) != 5 or sys.argv[1] != "--scenario" or sys.argv[3] != "--run-id":
        return 64
    scenario, run_id = sys.argv[2], sys.argv[4]
    if scenario not in SCENARIOS or not RUN_ID.fullmatch(run_id):
        return 64

    credential = os.environ.get("LIVE_DEVICE_CREDENTIAL", "")
    device_id = os.environ.get("LIVE_HOME_CLAIM_DEVICE_ID", "")
    mapping_id = os.environ.get("LIVE_HOME_CLAIM_MAPPING_ID", "")
    revision = os.environ.get("LIVE_HOME_CLAIM_CONFIGURATION_REVISION", "")
    target = os.environ.get("LIVE_HOME_CLAIM_SSH_TARGET", "")
    if (
        not TOKEN.fullmatch(credential)
        or not IDENTIFIER.fullmatch(device_id)
        or not IDENTIFIER.fullmatch(mapping_id)
        or not revision.isdecimal()
        or str(int(revision)) != revision
        or not SSH_TARGET.fullmatch(target)
    ):
        return 64

    payload = json.dumps(
        {
            "credential": credential,
            "device_id": device_id,
            "mapping_id": mapping_id,
            "revision": int(revision),
            "run_id": run_id,
            "scenario": scenario,
        },
        ensure_ascii=True,
        separators=(",", ":"),
    )
    ssh = os.environ.get("LIVE_HOME_SSH_BIN", "ssh")
    try:
        result = subprocess.run(
            [
                ssh,
                "-o",
                "BatchMode=yes",
                "-o",
                "ConnectTimeout=8",
                target,
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-EncodedCommand",
                _powershell_argument(),
            ],
            input=payload,
            text=True,
            capture_output=True,
            check=False,
            timeout=65,
        )
    except subprocess.TimeoutExpired:
        print("HANDLE_PROVIDER=FAIL:SSH_TIMEOUT", file=sys.stderr)
        return 20
    except OSError:
        print("HANDLE_PROVIDER=FAIL:SSH_START", file=sys.stderr)
        return 20
    handle = result.stdout.strip()
    if result.returncode != 0:
        print("HANDLE_PROVIDER=FAIL:SSH_EXIT", file=sys.stderr)
        return 20
    if not TOKEN.fullmatch(handle):
        print("HANDLE_PROVIDER=FAIL:INVALID_RESPONSE", file=sys.stderr)
        return 20
    sys.stdout.write(handle)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

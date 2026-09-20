#!/usr/bin/env python3
"""Fetch one content-free, signed Home trace into an owner-only file."""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import subprocess
import sys
from pathlib import Path


RUN_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")
SSH_TARGET = re.compile(r"[A-Za-z0-9._-]+@[A-Za-z0-9.-]+\Z")
POWERSHELL = r"""
$ErrorActionPreference = 'Stop'
try {
    $request = [Console]::In.ReadToEnd() | ConvertFrom-Json
    if ([string] $request.run_id -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') {
        exit 4
    }
    $path = 'C:\ProgramData\HermesHome\provenance\traces\' +
        [string] $request.run_id + '.json'
    $bytes = [System.IO.File]::ReadAllBytes($path)
    [Console]::Out.Write([Convert]::ToBase64String($bytes))
    exit 0
}
catch {
    exit 4
}
"""


def _powershell_argument() -> str:
    return base64.b64encode(POWERSHELL.encode("utf-16le")).decode("ascii")


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--output", required=True)
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    target = os.environ.get("LIVE_HOME_CLAIM_SSH_TARGET", "")
    if not RUN_ID.fullmatch(args.run_id) or not SSH_TARGET.fullmatch(target):
        return 64
    destination = Path(args.output)
    if destination.exists():
        return 64
    payload = json.dumps({"run_id": args.run_id}, separators=(",", ":"))
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
            timeout=20,
        )
    except (OSError, subprocess.TimeoutExpired):
        return 20
    if result.returncode != 0:
        return 20
    encoded = result.stdout.strip()
    try:
        content = base64.b64decode(encoded, validate=True)
        envelope = json.loads(content.decode("utf-8"))
    except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
        return 20
    if (
        not isinstance(envelope, dict)
        or envelope.get("kind") != "run-trace"
        or envelope.get("trace_run_id") != args.run_id
        or not isinstance(envelope.get("signature"), str)
        or len(content) > 65536
    ):
        return 20

    destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if os.name != "nt":
        destination.parent.chmod(0o700)
    temporary = destination.with_name(destination.name + ".tmp")
    if temporary.exists():
        return 64
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
    except OSError:
        try:
            temporary.unlink()
        except OSError:
            pass
        return 20
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

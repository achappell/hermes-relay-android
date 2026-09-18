#!/usr/bin/env python3
"""Scan captured live-gate artifacts without printing their contents."""

import base64
import os
import subprocess
import sys
from pathlib import Path


MARKERS = (
    b"RAW_FRAME=",
    b"RAW_JSON=",
    b"RAW_RESPONSE=",
    b"RESPONSE_TEXT=",
    b"PCM_BYTES=",
    b"PCM_BASE64=",
    b"Authorization: Device ",
    b"Content-Disposition: attachment",
)
FORBIDDEN_SUFFIXES = {".pcm", ".raw", ".wav", ".pcap", ".har", ".jsonl"}
ALLOWED_BINARY_NAMES = {"device-info.pb", "test-result.pb", "test-results.pb"}


def changed_paths(root: Path):
    paths = set()
    try:
        for command in (
            ["git", "diff", "--name-only", "--diff-filter=ACMRTUXB"],
            ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMRTUXB"],
        ):
            result = subprocess.run(
                command,
                cwd=root,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                check=False,
            )
            if result.returncode != 0:
                return None
            paths.update(line for line in result.stdout.decode("utf-8").splitlines() if line)
        result = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard"],
            cwd=root,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if result.returncode != 0:
            return None
        paths.update(line for line in result.stdout.decode("utf-8").splitlines() if line)
    except (OSError, UnicodeDecodeError):
        return None
    return paths


def scan_bytes(data: bytes, protected: tuple[bytes, ...], scan_markers: bool) -> bool:
    return not any(value in data for value in protected) and not (
        scan_markers and any(marker in data for marker in MARKERS)
    )


def scan_path(path: Path, protected: tuple[bytes, ...], scan_markers: bool) -> bool:
    try:
        data = path.read_bytes()
    except OSError:
        return False
    if path.suffix.lower() in FORBIDDEN_SUFFIXES:
        return False
    try:
        data.decode("utf-8")
        is_text = True
    except UnicodeDecodeError:
        is_text = False
    if not is_text:
        if path.name not in ALLOWED_BINARY_NAMES:
            return False
        return scan_bytes(data, protected, scan_markers)
    return scan_bytes(data, protected, scan_markers)


def main() -> int:
    if len(sys.argv) != 3:
        return 1
    root = Path(sys.argv[1])
    run_dir = Path(sys.argv[2])
    values = tuple(
        os.environ[name].encode("utf-8")
        for name in (
            "LIVE_DEVICE_CREDENTIAL",
            "LIVE_TYPED_PROMPT",
            "LIVE_INTERRUPT_PROMPT",
            "LIVE_RECONNECT_PROMPT",
        )
        if name in os.environ
    )
    # Encoded instrumentation arguments are equally sensitive as plaintext.
    values += tuple(base64.urlsafe_b64encode(value).rstrip(b"=") for value in values)
    try:
        captured = [path for path in run_dir.rglob("*") if path.is_file()]
    except OSError:
        return 1
    for path in captured:
        if not scan_path(path, values, True):
            return 1
    paths = changed_paths(root)
    if paths is None:
        return 1
    try:
        diffs = []
        for command in (
            ["git", "diff", "--binary", "--no-ext-diff"],
            ["git", "diff", "--cached", "--binary", "--no-ext-diff"],
        ):
            result = subprocess.run(
                command,
                cwd=root,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                check=False,
            )
            if result.returncode != 0:
                return 1
            diffs.append(result.stdout)
        diff = b"\n".join(diffs)
    except OSError:
        return 1
    # Marker definitions in source/spec/contract fixtures are intentional;
    # current-diff scanning is for protected values and binary leakage only.
    if not scan_bytes(diff, values, False):
        return 1
    for relative in paths:
        path = root / relative
        if not path.is_file():
            continue
        if path.suffix.lower() in FORBIDDEN_SUFFIXES:
            return 1
        try:
            data = path.read_bytes()
            data.decode("utf-8")
        except UnicodeDecodeError:
            if path.name not in ALLOWED_BINARY_NAMES:
                return 1
            if not scan_bytes(data, values, False):
                return 1
        except OSError:
            return 1
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception:
        raise SystemExit(1)

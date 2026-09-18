#!/usr/bin/env python3
"""Validate live Home gate inputs without printing any supplied value."""

import base64
import binascii
import os
import re
import stat
import sys
from urllib.parse import urlsplit


CLAIM_NAMES = (
    "LIVE_HOME_CLAIM_DEVICE_ID",
    "LIVE_HOME_CLAIM_MAPPING_ID",
    "LIVE_HOME_CLAIM_CONFIGURATION_REVISION",
    "LIVE_HOME_CLAIM_SSH_TARGET",
)
HOOK_NAMES = (
    "LIVE_HOME_HANDLE_PROVIDER",
    "LIVE_HOME_TRACE_FETCH_HOOK",
)
PROMPT_NAMES = (
    "LIVE_TYPED_PROMPT",
    "LIVE_INTERRUPT_PROMPT",
    "LIVE_RECONNECT_PROMPT",
)
MISSING_NAMES = (
    "LIVE_HOME_PROFILE_ID",
    "LIVE_HOME_ROUTE",
    "LIVE_DEVICE_CREDENTIAL",
    *CLAIM_NAMES,
    *HOOK_NAMES,
    *PROMPT_NAMES,
    "LIVE_HOME_RUN_ID",
)
BRIDGE_PATH = "/api/v1/bridge/ws"
RUN_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")
TOKEN = re.compile(r"[A-Za-z0-9_-]{43}\Z")
IDENTIFIER = re.compile(r"[A-Za-z0-9._:-]{1,128}\Z")


def utf8_size(value: str):
    try:
        return len(value.encode("utf-8"))
    except UnicodeEncodeError:
        return None


def safe_text(value: str, maximum: int) -> bool:
    size = utf8_size(value)
    return size is not None and 1 <= size <= maximum and not any(
        character in value for character in ("\x00", "\r", "\n")
    )


def valid_route(value: str) -> bool:
    try:
        parsed = urlsplit(value.strip())
    except ValueError:
        return False
    if parsed.scheme.lower() != "wss" or not parsed.hostname:
        return False
    if parsed.username is not None or parsed.password is not None:
        return False
    if parsed.query or parsed.fragment:
        return False
    try:
        if parsed.port is not None and not 1 <= parsed.port <= 65535:
            return False
    except ValueError:
        return False
    if parsed.path not in ("", "/", BRIDGE_PATH, BRIDGE_PATH + "/"):
        return False
    # Keep this aligned with the Android save-time validator: a bare address
    # is not an approved certificate-bound Home route.
    host = parsed.hostname
    if re.fullmatch(r"(?:\d{1,3}\.){3}\d{1,3}", host):
        return False
    if ":" in host and not host.startswith("["):
        # urlsplit removes IPv6 brackets; reject bare IPv6 literals here.
        return False
    return True


def valid_credential(value: str) -> bool:
    if not TOKEN.fullmatch(value):
        return False
    try:
        decoded = base64.urlsafe_b64decode(value + "=")
    except (ValueError, binascii.Error):
        return False
    return len(decoded) == 32


def main() -> int:
    if len(sys.argv) != 1:
        print("INPUTS=FAIL:INVALID_BINDING")
        return 20
    values = {name: os.environ.get(name) for name in MISSING_NAMES}
    if any(value is None or value == "" for value in values.values()):
        print("INPUTS=FAIL:MISSING_PAIRING")
        return 20
    if not valid_route(values["LIVE_HOME_ROUTE"]):
        print("INPUTS=FAIL:INVALID_BINDING")
        return 20
    if not IDENTIFIER.fullmatch(values["LIVE_HOME_PROFILE_ID"]):
        print("INPUTS=FAIL:INVALID_BINDING")
        return 20
    if not valid_credential(values["LIVE_DEVICE_CREDENTIAL"]):
        print("INPUTS=FAIL:INVALID_BINDING")
        return 20
    for name in ("LIVE_HOME_CLAIM_DEVICE_ID", "LIVE_HOME_CLAIM_MAPPING_ID"):
        if not IDENTIFIER.fullmatch(values[name]):
            print("INPUTS=FAIL:INVALID_BINDING")
            return 20
    try:
        revision = int(values["LIVE_HOME_CLAIM_CONFIGURATION_REVISION"])
    except ValueError:
        print("INPUTS=FAIL:INVALID_BINDING")
        return 20
    if revision < 0 or str(revision) != values["LIVE_HOME_CLAIM_CONFIGURATION_REVISION"]:
        print("INPUTS=FAIL:INVALID_BINDING")
        return 20
    if not re.fullmatch(r"[A-Za-z0-9._-]+@[A-Za-z0-9.-]+", values["LIVE_HOME_CLAIM_SSH_TARGET"]):
        print("INPUTS=FAIL:INVALID_BINDING")
        return 20
    for name in PROMPT_NAMES:
        if not safe_text(values[name], 4096):
            print("INPUTS=FAIL:INVALID_BINDING")
            return 20
    if not RUN_ID.fullmatch(values["LIVE_HOME_RUN_ID"]):
        print("INPUTS=FAIL:INVALID_BINDING")
        return 20
    for name in HOOK_NAMES:
        try:
            mode = os.stat(values[name]).st_mode
        except OSError:
            print("INPUTS=FAIL:INVALID_BINDING")
            return 20
        if not stat.S_ISREG(mode) or not os.access(values[name], os.X_OK):
            print("INPUTS=FAIL:INVALID_BINDING")
            return 20
    print("INPUTS=PASS")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception:
        print("INPUTS=FAIL:INVALID_BINDING")
        raise SystemExit(20)

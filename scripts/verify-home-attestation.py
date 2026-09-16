#!/usr/bin/env python3
"""Verify one signed Home deployment or current-run trace envelope."""

import argparse
import base64
import binascii
import hashlib
import json
import os
import re
import stat
import sys


ROUTE_CLASSES = {"home", "tailscale", "public"}
SAFE_STRING = re.compile(r"[^\x00-\x1f\x7f]{1,256}\Z")
TRACE_METHODS = {
    "conversation.open",
    "prompt.submit",
    "session.interrupt",
    "peer.close",
    "conversation.reconnect",
    "turn.completed",
    "turn.interrupted",
    "turn.failed",
}
TRACE_OUTCOMES = {
    "accepted",
    "observed",
    "ready",
    "acknowledged",
    "completed",
    "interrupted",
    "failed",
}
SCENARIO_GROUPS = {
    "handshake": (("conversation.open", "ready"),),
    "typed_audio": (
        ("conversation.open", "ready"),
        ("prompt.submit", "accepted"),
        ("turn.completed", "completed"),
    ),
    "interrupt": (
        ("conversation.open", "ready"),
        ("prompt.submit", "accepted"),
        ("session.interrupt", "acknowledged"),
        ("turn.interrupted", "interrupted"),
    ),
    "reconnect": (
        ("conversation.open", "ready"),
        ("prompt.submit", "accepted"),
        ("peer.close", "observed"),
        ("conversation.reconnect", "ready"),
    ),
}


class InvalidEnvelope(Exception):
    pass


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InvalidEnvelope()
        result[key] = value
    return result


def read_owner_file(path: str) -> bytes:
    try:
        info = os.lstat(path)
    except OSError as error:
        raise InvalidEnvelope() from error
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise InvalidEnvelope()
    try:
        with open(path, "rb") as stream:
            return stream.read()
    except OSError as error:
        raise InvalidEnvelope() from error


def decode_b64url(value: object, expected_length: int):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+\Z", value):
        raise InvalidEnvelope()
    try:
        decoded = base64.urlsafe_b64decode(value + "=" * ((4 - len(value) % 4) % 4))
    except (ValueError, binascii.Error) as error:
        raise InvalidEnvelope() from error
    if len(decoded) != expected_length:
        raise InvalidEnvelope()
    return decoded


def safe_string(value: object, *, no_whitespace: bool = False) -> bool:
    if not isinstance(value, str) or not SAFE_STRING.fullmatch(value):
        return False
    return not no_whitespace or not any(character.isspace() for character in value)


def exact_fields(value: object, expected: set[str]) -> dict:
    if not isinstance(value, dict) or set(value) != expected:
        raise InvalidEnvelope()
    return value


def exact_int(value: object, expected: int) -> bool:
    return type(value) is int and value == expected


def canonicalize(value, rfc8785):
    result = rfc8785.dumps(value)
    return result if isinstance(result, bytes) else result.encode("utf-8")


def load_allowlist(path: str) -> dict[str, str]:
    raw = read_owner_file(path)
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise InvalidEnvelope() from error
    try:
        parsed = json.loads(text, object_pairs_hook=strict_object)
    except (json.JSONDecodeError, InvalidEnvelope) as error:
        parsed = None
        json_error = error
    else:
        json_error = None
    entries = {}
    if isinstance(parsed, dict):
        if isinstance(parsed.get("entries"), list):
            for entry in parsed["entries"]:
                if not isinstance(entry, dict) or set(entry) != {"signer_key_id", "fingerprint"}:
                    raise InvalidEnvelope()
                if entry["signer_key_id"] in entries:
                    raise InvalidEnvelope()
                entries[entry["signer_key_id"]] = entry["fingerprint"]
        else:
            entries = parsed
    elif json_error is not None:
        for line in text.splitlines():
            stripped = line.strip()
            if not stripped:
                continue
            pieces = stripped.split()
            if len(pieces) != 2:
                raise InvalidEnvelope() from json_error
            if pieces[0] in entries:
                raise InvalidEnvelope() from json_error
            entries[pieces[0]] = pieces[1]
    else:
        raise InvalidEnvelope()
    if not entries:
        raise InvalidEnvelope()
    for key, fingerprint in entries.items():
        if not safe_string(key, no_whitespace=True) or not isinstance(fingerprint, str):
            raise InvalidEnvelope()
        if not re.fullmatch(r"[0-9a-f]{64}\Z", fingerprint):
            raise InvalidEnvelope()
    return entries


def parse_args():
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--kind", required=False)
    parser.add_argument("--attestation-file", default=os.environ.get("LIVE_HOME_ATTESTATION_FILE"))
    parser.add_argument("--public-key-file", default=os.environ.get("LIVE_HOME_ATTESTATION_PUBLIC_KEY_FILE"))
    parser.add_argument("--allowlist-file", default=os.environ.get("LIVE_HOME_ATTESTATION_KEY_ALLOWLIST_FILE"))
    parser.add_argument("--run-id", default=os.environ.get("LIVE_HOME_RUN_ID"))
    parser.add_argument("--device-serial-fingerprint", default=os.environ.get("LIVE_DEVICE_SERIAL_FINGERPRINT"))
    parser.add_argument("--route-class", default=os.environ.get("LIVE_OBSERVED_ROUTE_CLASS"))
    parser.add_argument("--route-id", default=os.environ.get("LIVE_OBSERVED_ROUTE_ID"))
    parser.add_argument("--deployment-id", default=os.environ.get("LIVE_HOME_DEPLOYMENT_ID"))
    parser.add_argument("--deployment-revision", default=os.environ.get("LIVE_HOME_DEPLOYMENT_REVISION"))
    parser.add_argument("--signer-key-id", default=os.environ.get("LIVE_HOME_SIGNER_KEY_ID"))
    parser.add_argument("--projection-file")
    return parser.parse_args()


def verify(args) -> dict:
    if args.kind not in {"deployment", "run-trace"}:
        raise InvalidEnvelope()
    if not all((args.attestation_file, args.public_key_file, args.allowlist_file)):
        raise InvalidEnvelope()
    if args.kind == "run-trace" and not all(
        (args.run_id, args.device_serial_fingerprint, args.route_class, args.route_id)
    ):
        raise InvalidEnvelope()
    public_key_bytes = decode_b64url(
        read_owner_file(args.public_key_file).decode("ascii").strip(),
        32,
    )
    fingerprint = hashlib.sha256(public_key_bytes).hexdigest()
    allowlist = load_allowlist(args.allowlist_file)

    try:
        import rfc8785
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    except ImportError as error:
        raise RuntimeError("dependency") from error

    raw = read_owner_file(args.attestation_file)
    try:
        envelope = json.loads(raw.decode("utf-8"), object_pairs_hook=strict_object)
    except (UnicodeDecodeError, json.JSONDecodeError, InvalidEnvelope) as error:
        raise InvalidEnvelope() from error
    if args.kind == "deployment":
        required = {
            "schema", "kind", "deployment_id", "deployment_revision", "adapter",
            "signer_key_id", "route_class", "route_id", "signature",
        }
    else:
        required = {
            "schema", "kind", "deployment_id", "deployment_revision", "adapter",
            "signer_key_id", "route_class", "route_id", "trace_run_id", "trace_digest",
            "device_serial_fingerprint", "trace", "signature",
        }
    envelope = exact_fields(envelope, required)
    if not exact_int(envelope["schema"], 1) or envelope["kind"] != args.kind:
        raise InvalidEnvelope()
    for field in (
        "deployment_id", "deployment_revision", "signer_key_id", "route_id"
    ):
        if not safe_string(envelope[field], no_whitespace=field in {"signer_key_id", "route_id"}):
            raise InvalidEnvelope()
    if envelope["adapter"] != "standard-backed" or envelope["route_class"] not in ROUTE_CLASSES:
        raise InvalidEnvelope()
    if allowlist.get(envelope["signer_key_id"]) != fingerprint:
        raise InvalidEnvelope()
    if args.signer_key_id and envelope["signer_key_id"] != args.signer_key_id:
        raise InvalidEnvelope()
    if args.deployment_id and envelope["deployment_id"] != args.deployment_id:
        raise InvalidEnvelope()
    if args.deployment_revision and envelope["deployment_revision"] != args.deployment_revision:
        raise InvalidEnvelope()
    if args.route_class and envelope["route_class"] != args.route_class:
        raise InvalidEnvelope()
    if args.route_id and envelope["route_id"] != args.route_id:
        raise InvalidEnvelope()

    signature = decode_b64url(envelope["signature"], 64)
    unsigned = {key: value for key, value in envelope.items() if key != "signature"}
    try:
        Ed25519PublicKey.from_public_bytes(public_key_bytes).verify(
            signature,
            canonicalize(unsigned, rfc8785),
        )
    except Exception as error:
        raise InvalidEnvelope() from error

    projection = {
        "deployment_id": envelope["deployment_id"],
        "deployment_revision": envelope["deployment_revision"],
        "adapter": envelope["adapter"],
        "signer_key_id": envelope["signer_key_id"],
        "route_class": envelope["route_class"],
        "route_id": envelope["route_id"],
    }
    if args.kind == "run-trace":
        if not safe_string(envelope["trace_run_id"], no_whitespace=True):
            raise InvalidEnvelope()
        if envelope["trace_run_id"] != args.run_id:
            raise InvalidEnvelope()
        if envelope["device_serial_fingerprint"] != args.device_serial_fingerprint:
            raise InvalidEnvelope()
        if not re.fullmatch(r"[0-9a-f]{64}\Z", envelope["device_serial_fingerprint"]):
            raise InvalidEnvelope()
        if not re.fullmatch(r"[0-9a-f]{64}\Z", envelope["trace_digest"]):
            raise InvalidEnvelope()
        trace = envelope["trace"]
        if not isinstance(trace, list) or len(trace) != 12:
            raise InvalidEnvelope()
        expected_sequence = 1
        offset = 0
        for scenario, expected_group in SCENARIO_GROUPS.items():
            for method, outcome in expected_group:
                entry = trace[offset]
                offset += 1
                allowed = {
                    "schema", "scenario", "sequence", "method", "outcome"
                }
                if scenario == "reconnect":
                    allowed.add("same_conversation")
                entry = exact_fields(entry, allowed)
                if (
                    not exact_int(entry["schema"], 1)
                    or entry["scenario"] != scenario
                    or not exact_int(entry["sequence"], expected_sequence)
                    or entry["method"] != method
                    or entry["outcome"] != outcome
                ):
                    raise InvalidEnvelope()
                if entry["method"] not in TRACE_METHODS or entry["outcome"] not in TRACE_OUTCOMES:
                    raise InvalidEnvelope()
                if scenario == "reconnect" and type(entry["same_conversation"]) is not bool:
                    raise InvalidEnvelope()
                if scenario == "reconnect" and method == "conversation.reconnect" and entry["same_conversation"] is not True:
                    raise InvalidEnvelope()
                expected_sequence += 1
        if envelope["trace_digest"] != hashlib.sha256(canonicalize(trace, rfc8785)).hexdigest():
            raise InvalidEnvelope()
        projection.update({
            "trace_run_id": envelope["trace_run_id"],
            "trace_digest": envelope["trace_digest"],
            "device_serial_fingerprint": envelope["device_serial_fingerprint"],
        })
    if args.projection_file:
        destination = os.path.abspath(args.projection_file)
        parent = os.path.dirname(destination)
        os.makedirs(parent, mode=0o700, exist_ok=True)
        temporary = destination + ".tmp"
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
        descriptor = os.open(temporary, flags, 0o600)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                descriptor = None
                json.dump(projection, output, sort_keys=True, separators=(",", ":"))
                output.write("\n")
            os.replace(temporary, destination)
        finally:
            if descriptor is not None:
                os.close(descriptor)
            try:
                os.unlink(temporary)
            except FileNotFoundError:
                pass
    return projection


def main() -> int:
    try:
        args = parse_args()
        verify(args)
    except RuntimeError:
        print("ATTESTATION=FAIL:DEPENDENCY")
        return 20
    except (InvalidEnvelope, OSError, ValueError, TypeError, UnicodeError):
        print("ATTESTATION=FAIL:INVALID")
        return 20
    print("ATTESTATION=PASS")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception:
        print("ATTESTATION=FAIL:INVALID")
        raise SystemExit(20)

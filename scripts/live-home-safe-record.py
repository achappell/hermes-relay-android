#!/usr/bin/env python3
"""Maintain the allowlisted schema-3 aggregate for the live Home gate."""

import argparse
import json
import os
import re
import stat
import sys
from pathlib import Path


SCENARIOS = ("handshake", "typed_audio", "interrupt", "reconnect")
STATUSES = {"pass", "fail", "inconclusive", "not-run"}
EXECUTIONS = {"not-started", "started", "completed"}
REASONS = {
    "DEVICE_SELECTION", "WRONG_DEVICE", "AUDIO_OUTPUT", "MISSING_PAIRING",
    "INVALID_BINDING", "HOME_404", "HOME_UNREACHABLE", "HOME_UNAVAILABLE",
    "UNRESOLVED_TURN", "CAPABILITY_SHAPE_INVALID", "CAPABILITY_UNAVAILABLE",
    "HOME_PROVENANCE", "CONTROLLED_CLOSE", "NATURAL_COMPLETION_RACE",
    "TERMINAL_TIMEOUT", "AUDIO_FAILURE", "RECONNECT_TRACE", "HARNESS_FAILURE",
}
PHASES = {
    "Idle", "Listening", "Transcribing", "Thinking", "Buffering", "Speaking",
    "Complete", "Unavailable", "Disconnected", "Interrupted",
}
AUDIO_STATES = {"NotStarted", "Buffering", "Speaking", "Delivered", "Unavailable"}
SAFE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")


class Invalid(Exception):
    pass


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Invalid()
        result[key] = value
    return result


def load(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_pairs)
    except Exception as error:
        raise Invalid() from error


def write_atomic(path: Path, value):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor = os.open(
        str(path) + ".tmp",
        os.O_WRONLY | os.O_CREAT | os.O_EXCL,
        0o600,
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            descriptor = None
            json.dump(value, stream, sort_keys=True, separators=(",", ":"))
            stream.write("\n")
        os.replace(str(path) + ".tmp", path)
    finally:
        if descriptor is not None:
            os.close(descriptor)
        try:
            (path.parent / (path.name + ".tmp")).unlink()
        except FileNotFoundError:
            pass


def empty_branch():
    return {
        "status": "not-run",
        "reason": "HOME_PROVENANCE",
        "execution": "not-started",
        "evidence": {
            "available": False,
            "request_counts": {"observed": False, "prompt_submit": None, "interrupt": None},
            "facts": None,
        },
    }


def empty_record(run_id):
    return {
        "schema_version": 3,
        "status": "done-with-environment-limitation",
        "exit_code": 20,
        "run_id": run_id,
        "device": {
            "status": "not-run",
            "model": None,
            "api": None,
            "physical": None,
            "audio_output": None,
            "serial_fingerprint": None,
            "default_non_live_count": None,
            "default_live_count": None,
        },
        "provenance": {"deployment_attested": False, "run_trace_attested": False},
        "home": None,
        "branches": {scenario: empty_branch() for scenario in SCENARIOS},
        "commands": {
            "default": {"status": "not-run", "exit_code": None, "report_count": None},
            "audio_preflight": {"status": "not-run", "exit_code": None},
            "live": {"status": "not-run", "exit_code": None, "report_count": None},
        },
        "overall_rule": "live-pass only when all four branches pass and Home provenance is attested",
    }


def bool_or_none(value):
    return value is None or type(value) is bool


def count_or_none(value):
    return value is None or (type(value) is int and value >= 0)


def safe_identity(value):
    return (
        isinstance(value, str)
        and 1 <= len(value.encode("utf-8")) <= 256
        and not any(character.isspace() or ord(character) < 32 for character in value)
    )


def exact_fields(value, fields):
    if not isinstance(value, dict) or set(value) != set(fields):
        raise Invalid()
    return value


def validate_route(route):
    if route is None:
        return
    route = exact_fields(route, ("class", "id"))
    if route["class"] not in {"home", "tailscale", "public"} or not safe_identity(route["id"]):
        raise Invalid()


def validate_capabilities(caps):
    if caps is None:
        return
    caps = exact_fields(caps, ("heartbeat", "timing", "command_count", "interrupt", "audio"))
    if (
        type(caps["heartbeat"]) is not bool
        or caps["timing"] != "absent"
        or type(caps["command_count"]) is not int
        or caps["command_count"] < 0
        or type(caps["interrupt"]) is not bool
        or type(caps["audio"]) is not bool
    ):
        raise Invalid()


def validate_facts(scenario, facts):
    if facts is None:
        return
    if not isinstance(facts, dict):
        raise Invalid()
    if scenario == "handshake":
        facts = exact_fields(facts, (
            "connection_ready", "response_id_matched", "unresolved_turn", "route", "capabilities"
        ))
        for key in ("connection_ready", "response_id_matched", "unresolved_turn"):
            if not bool_or_none(facts[key]):
                raise Invalid()
        validate_route(facts["route"])
        validate_capabilities(facts["capabilities"])
    elif scenario == "typed_audio":
        facts = exact_fields(facts, (
            "format", "accepted_bytes", "accepted_frames", "audio_chunk_received",
            "audio_failed", "drained", "underrun_count", "terminal_event_observed",
            "final_phase", "final_audio",
        ))
        fmt = facts["format"]
        if fmt is not None:
            fmt = exact_fields(fmt, ("sample_rate", "channels", "sample_width", "byte_order", "encoding"))
            if (
                type(fmt["sample_rate"]) is not int or fmt["sample_rate"] <= 0
                or fmt["sample_rate"] not in range(8_000, 192_001)
                or type(fmt["channels"]) is not int or fmt["channels"] not in (1, 2)
                or fmt["sample_width"] != 2
                or fmt["byte_order"] != "little"
                or fmt["encoding"] != "pcm_s16le"
            ):
                raise Invalid()
        for key in ("accepted_bytes", "accepted_frames", "underrun_count"):
            if not count_or_none(facts[key]):
                raise Invalid()
        for key in ("audio_chunk_received", "audio_failed", "drained", "terminal_event_observed"):
            if not bool_or_none(facts[key]):
                raise Invalid()
        if facts["final_phase"] is not None and facts["final_phase"] not in PHASES:
            raise Invalid()
        if facts["final_audio"] is not None and facts["final_audio"] not in AUDIO_STATES:
            raise Invalid()
    elif scenario == "interrupt":
        facts = exact_fields(facts, (
            "non_terminal_state_observed", "interrupt_sent_count", "acknowledgement_observed",
            "terminal_event_observed", "terminal_event",
        ))
        for key in (
            "non_terminal_state_observed", "acknowledgement_observed", "terminal_event_observed"
        ):
            if not bool_or_none(facts[key]):
                raise Invalid()
        if not count_or_none(facts["interrupt_sent_count"]):
            raise Invalid()
        if facts["terminal_event"] not in (None, "completed", "interrupted"):
            raise Invalid()
    elif scenario == "reconnect":
        facts = exact_fields(facts, (
            "accepted_prompt_submit_count", "post_accept_prompt_submit_count", "peer_close_observed",
            "conversation_reconnect_observed", "same_conversation", "uncertainty_preserved",
        ))
        for key in ("accepted_prompt_submit_count", "post_accept_prompt_submit_count"):
            if not count_or_none(facts[key]):
                raise Invalid()
        for key in (
            "peer_close_observed", "conversation_reconnect_observed", "same_conversation",
            "uncertainty_preserved",
        ):
            if not bool_or_none(facts[key]):
                raise Invalid()
    else:
        raise Invalid()


def passing_facts(scenario, facts):
    if not isinstance(facts, dict):
        return False
    if scenario == "handshake":
        return (
            facts["connection_ready"] is True
            and facts["response_id_matched"] is True
            and facts["unresolved_turn"] is False
            and facts["route"] is not None
            and facts["capabilities"] is not None
        )
    if scenario == "typed_audio":
        return (
            facts["format"] is not None
            and facts["accepted_bytes"] is not None and facts["accepted_bytes"] > 0
            and facts["accepted_frames"] is not None and facts["accepted_frames"] > 0
            and facts["audio_chunk_received"] is True
            and facts["audio_failed"] is False
            and facts["drained"] is True
            and facts["underrun_count"] == 0
            and facts["terminal_event_observed"] is True
            and facts["final_phase"] == "Complete"
            and facts["final_audio"] == "Delivered"
        )
    if scenario == "interrupt":
        return (
            facts["non_terminal_state_observed"] is True
            and facts["interrupt_sent_count"] == 1
            and facts["acknowledgement_observed"] is True
            and facts["terminal_event_observed"] is True
            and facts["terminal_event"] == "interrupted"
        )
    if scenario == "reconnect":
        return (
            facts["accepted_prompt_submit_count"] == 1
            and facts["post_accept_prompt_submit_count"] == 0
            and facts["peer_close_observed"] is True
            and facts["conversation_reconnect_observed"] is True
            and facts["same_conversation"] is True
            and facts["uncertainty_preserved"] is True
        )
    return False


def validate_handoff(handoff, run_id, scenario):
    handoff = exact_fields(handoff, ("schema_version", "run_id", "scenario", "status", "reason", "evidence"))
    if handoff["schema_version"] != 3 or handoff["run_id"] != run_id or handoff["scenario"] != scenario:
        raise Invalid()
    if handoff["status"] not in STATUSES or (handoff["status"] == "pass") != (handoff["reason"] is None):
        raise Invalid()
    if handoff["reason"] is not None and handoff["reason"] not in REASONS:
        raise Invalid()
    evidence = exact_fields(handoff["evidence"], ("available", "request_counts", "facts"))
    if handoff["status"] == "pass" and (evidence["available"] is not True or evidence["facts"] is None):
        raise Invalid()
    if handoff["status"] == "pass" and not passing_facts(scenario, evidence["facts"]):
        raise Invalid()
    if type(evidence["available"]) is not bool or evidence["available"] != (evidence["facts"] is not None):
        raise Invalid()
    counts = exact_fields(evidence["request_counts"], ("observed", "prompt_submit", "interrupt"))
    if type(counts["observed"]) is not bool:
        raise Invalid()
    if counts["observed"] is not True:
        raise Invalid()
    if not count_or_none(counts["prompt_submit"]) or not count_or_none(counts["interrupt"]):
        raise Invalid()
    validate_facts(scenario, evidence["facts"])
    return handoff


def validate_record(record):
    record = exact_fields(record, (
        "schema_version", "status", "exit_code", "run_id", "device", "provenance",
        "home", "branches", "commands", "overall_rule",
    ))
    if record["schema_version"] != 3 or record["status"] not in {
        "done-with-environment-limitation", "live-pass", "failed"
    }:
        raise Invalid()
    if record["exit_code"] not in (0, 20, 30, 64):
        raise Invalid()
    if record["run_id"] is not None and not SAFE_ID.fullmatch(record["run_id"]):
        raise Invalid()
    device = exact_fields(record["device"], (
        "status", "model", "api", "physical", "audio_output", "serial_fingerprint",
        "default_non_live_count", "default_live_count",
    ))
    if device["status"] not in {"observed", "not-run"}:
        raise Invalid()
    if device["api"] is not None and (type(device["api"]) is not int or device["api"] < 0):
        raise Invalid()
    for key in ("physical", "audio_output"):
        if not bool_or_none(device[key]):
            raise Invalid()
    for key in ("default_non_live_count", "default_live_count"):
        if not count_or_none(device[key]):
            raise Invalid()
    if device["serial_fingerprint"] is not None and not re.fullmatch(r"[0-9a-f]{64}\Z", device["serial_fingerprint"]):
        raise Invalid()
    provenance = exact_fields(record["provenance"], ("deployment_attested", "run_trace_attested"))
    if type(provenance["deployment_attested"]) is not bool or type(provenance["run_trace_attested"]) is not bool:
        raise Invalid()
    if record["home"] is not None:
        home = exact_fields(record["home"], (
            "deployment_id", "deployment_revision", "adapter", "signer_key_id", "route_class",
            "route_id", "trace_run_id", "trace_digest", "device_serial_fingerprint",
        ))
        for key in ("deployment_id", "deployment_revision", "signer_key_id", "route_id", "trace_run_id"):
            if not safe_identity(home[key]):
                raise Invalid()
        if home["adapter"] != "standard-backed" or home["route_class"] not in {"home", "tailscale", "public"}:
            raise Invalid()
        if not re.fullmatch(r"[0-9a-f]{64}\Z", home["trace_digest"]):
            raise Invalid()
        if not re.fullmatch(r"[0-9a-f]{64}\Z", home["device_serial_fingerprint"]):
            raise Invalid()
    branches = exact_fields(record["branches"], SCENARIOS)
    for scenario in SCENARIOS:
        branch = exact_fields(branches[scenario], ("status", "reason", "execution", "evidence"))
        if branch["status"] not in STATUSES or branch["execution"] not in EXECUTIONS:
            raise Invalid()
        if branch["status"] == "pass" and branch["reason"] is not None:
            raise Invalid()
        if branch["status"] != "pass" and branch["reason"] not in REASONS:
            raise Invalid()
        evidence = exact_fields(branch["evidence"], ("available", "request_counts", "facts"))
        if type(evidence["available"]) is not bool:
            raise Invalid()
        if branch["status"] == "pass" and (evidence["available"] is not True or evidence["facts"] is None):
            raise Invalid()
        if branch["status"] == "pass" and not passing_facts(scenario, evidence["facts"]):
            raise Invalid()
        counts = exact_fields(evidence["request_counts"], ("observed", "prompt_submit", "interrupt"))
        if type(counts["observed"]) is not bool:
            raise Invalid()
        if not count_or_none(counts["prompt_submit"]) or not count_or_none(counts["interrupt"]):
            raise Invalid()
        if evidence["available"] != (evidence["facts"] is not None):
            raise Invalid()
        validate_facts(scenario, evidence["facts"])
    commands = record["commands"]
    exact_fields(commands, ("default", "audio_preflight", "live"))
    for name, command in commands.items():
        allowed = ("status", "exit_code") if name == "audio_preflight" else ("status", "exit_code", "report_count")
        command = exact_fields(command, allowed)
        if command["status"] not in {"not-run", "pass", "fail", "inconclusive"}:
            raise Invalid()
        if command["exit_code"] is not None and (type(command["exit_code"]) is not int or not 0 <= command["exit_code"] <= 255):
            raise Invalid()
        if name != "audio_preflight" and not count_or_none(command["report_count"]):
            raise Invalid()
    if record["overall_rule"] != "live-pass only when all four branches pass and Home provenance is attested":
        raise Invalid()
    return record


def recompute(record):
    failures = any(record["branches"][scenario]["status"] == "fail" for scenario in SCENARIOS)
    failures = failures or any(
        record["commands"][name]["status"] == "fail" for name in record["commands"]
    )
    if failures:
        record["status"] = "failed"
        record["exit_code"] = 30
        return record
    all_pass = all(
        record["branches"][scenario]["status"] == "pass"
        and record["branches"][scenario]["evidence"]["available"] is True
        and record["branches"][scenario]["evidence"]["facts"] is not None
        for scenario in SCENARIOS
    )
    commands_pass = all(record["commands"][name]["status"] == "pass" for name in record["commands"])
    device = record["device"]
    device_ready = (
        device["status"] == "observed"
        and device["physical"] is True
        and device["audio_output"] is True
        and type(device["default_non_live_count"]) is int
        and device["default_non_live_count"] > 0
        and device["default_live_count"] == 0
    )
    report_counts_ready = (
        record["commands"]["default"]["report_count"] == device["default_non_live_count"]
        and record["commands"]["live"]["report_count"] == len(SCENARIOS)
    )
    attested = record["provenance"]["deployment_attested"] and record["provenance"]["run_trace_attested"]
    if all_pass and commands_pass and device_ready and report_counts_ready and attested and record["home"] is not None:
        record["status"] = "live-pass"
        record["exit_code"] = 0
    else:
        record["status"] = "done-with-environment-limitation"
        record["exit_code"] = 20
    return record


def parser():
    result = argparse.ArgumentParser(add_help=False)
    result.add_argument("--state", required=True)
    result.add_argument("--write", required=True)
    result.add_argument("--run-id")
    result.add_argument("--set-branch")
    result.add_argument("--branch-file")
    result.add_argument("--branch-status")
    result.add_argument("--branch-reason")
    result.add_argument("--branch-execution")
    result.add_argument("--deployment-attested", choices=("true", "false"))
    result.add_argument("--trace-attested", choices=("true", "false"))
    result.add_argument("--home-file")
    result.add_argument("--command")
    result.add_argument("--command-status")
    result.add_argument("--command-exit", type=int)
    result.add_argument("--command-report-count", type=int)
    result.add_argument("--device-status")
    result.add_argument("--device-model")
    result.add_argument("--device-api", type=int)
    result.add_argument("--device-physical", choices=("true", "false"))
    result.add_argument("--device-audio-output", choices=("true", "false"))
    result.add_argument("--device-serial-fingerprint")
    result.add_argument("--default-non-live-count", type=int)
    result.add_argument("--default-live-count", type=int)
    return result


def main():
    args = parser().parse_args()
    state_path = Path(args.state)
    if state_path.exists():
        record = load(state_path)
    else:
        record = empty_record(args.run_id)
    if args.run_id is not None and record["run_id"] not in (None, args.run_id):
        raise Invalid()
    if record["run_id"] is None:
        record["run_id"] = args.run_id

    if args.set_branch:
        if args.set_branch not in SCENARIOS or not args.branch_file:
            raise Invalid()
        handoff = validate_handoff(load(Path(args.branch_file)), record["run_id"], args.set_branch)
        record["branches"][args.set_branch] = {
            "status": handoff["status"],
            "reason": handoff["reason"],
            "execution": "completed",
            "evidence": handoff["evidence"],
        }
    if args.branch_status:
        if args.branch_status not in SCENARIOS or args.branch_execution not in EXECUTIONS:
            raise Invalid()
        status = args.branch_reason
        if status not in REASONS:
            raise Invalid()
        previous = record["branches"][args.branch_status]
        preserve_evidence = (
            args.branch_execution != "not-started"
            and previous["evidence"]["available"] is True
        )
        record["branches"][args.branch_status] = {
            "status": "not-run" if args.branch_execution == "not-started" else "inconclusive",
            "reason": status,
            "execution": args.branch_execution,
            "evidence": previous["evidence"] if preserve_evidence else {
                "available": False,
                "request_counts": {"observed": False, "prompt_submit": None, "interrupt": None},
                "facts": None,
            },
        }
    if args.deployment_attested is not None:
        record["provenance"]["deployment_attested"] = args.deployment_attested == "true"
    if args.trace_attested is not None:
        record["provenance"]["run_trace_attested"] = args.trace_attested == "true"
    if args.home_file:
        home = load(Path(args.home_file))
        exact_fields(home, (
            "deployment_id", "deployment_revision", "adapter", "signer_key_id", "route_class",
            "route_id", "trace_run_id", "trace_digest", "device_serial_fingerprint",
        ))
        record["home"] = home
    if args.command:
        if args.command not in record["commands"] or args.command_status not in {"not-run", "pass", "fail", "inconclusive"}:
            raise Invalid()
        command = record["commands"][args.command]
        command["status"] = args.command_status
        command["exit_code"] = args.command_exit
        if args.command != "audio_preflight":
            command["report_count"] = args.command_report_count
    if args.device_status:
        if args.device_status not in {"observed", "not-run"}:
            raise Invalid()
        device = record["device"]
        device["status"] = args.device_status
        if args.device_model is not None:
            device["model"] = args.device_model
        if args.device_api is not None:
            device["api"] = args.device_api
        if args.device_physical is not None:
            device["physical"] = args.device_physical == "true"
        if args.device_audio_output is not None:
            device["audio_output"] = args.device_audio_output == "true"
        if args.device_serial_fingerprint is not None:
            device["serial_fingerprint"] = args.device_serial_fingerprint
        if args.default_non_live_count is not None:
            device["default_non_live_count"] = args.default_non_live_count
        if args.default_live_count is not None:
            device["default_live_count"] = args.default_live_count
    record = recompute(record)
    validate_record(record)
    write_atomic(state_path, record)
    write_atomic(Path(args.write), record)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        raise SystemExit(1)

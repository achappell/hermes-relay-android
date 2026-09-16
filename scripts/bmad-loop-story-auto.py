#!/usr/bin/env python3
"""Run one Android stories-mode plan gate with a bounded independent review.

``bmad-loop`` pauses at the plan checkpoint, but its plugin workflow stages do
not include that checkpoint. This wrapper therefore launches the read-only plan
reviewer after the pause and resumes only on an explicit, validated pass.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any


DEFAULT_SPEC = "_bmad-output/implementation-artifacts/next-wave-android-live-home-gate"
PLAN_CHECKPOINT = "plan-checkpoint"
VERDICT_NAME = "spec-review-verdict.json"
STORY_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


def _display_path(path: Path) -> str:
    home = Path.home()
    try:
        return "~" + str(path.resolve().relative_to(home))
    except ValueError:
        return str(path)


def _loop_binary() -> str:
    binary = shutil.which("bmad-loop")
    if binary is None:
        raise RuntimeError("bmad-loop is not installed")
    return binary


def _json_command(
    binary: str,
    args: list[str],
    project: Path,
    timeout: float = 30.0,
) -> dict[str, Any]:
    try:
        completed = subprocess.run(
            [binary, *args],
            cwd=project,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(f"{binary} {' '.join(args)} timed out") from exc
    if completed.stderr:
        print(completed.stderr, file=sys.stderr, end="")
    if completed.returncode != 0:
        raise RuntimeError(
            f"{binary} {' '.join(args)} failed with exit code {completed.returncode}"
        )
    try:
        document = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError("bmad-loop returned invalid JSON") from exc
    if not isinstance(document, dict):
        raise RuntimeError("bmad-loop returned a JSON value that was not an object")
    return document


def _validate(binary: str, project: Path, spec: str) -> bool:
    try:
        completed = subprocess.run(
            [binary, "validate", "--project", str(project), "--spec", spec, "--json"],
            cwd=project,
            check=False,
            capture_output=True,
            text=True,
            timeout=30.0,
        )
    except subprocess.TimeoutExpired:
        print("bmad-loop validate timed out; refusing to start", file=sys.stderr)
        return False
    if completed.stderr:
        print(completed.stderr, file=sys.stderr, end="")
    try:
        document = json.loads(completed.stdout)
    except json.JSONDecodeError:
        print("bmad-loop validate did not return JSON; refusing to start", file=sys.stderr)
        if completed.stdout:
            print(completed.stdout, file=sys.stderr, end="")
        return False
    if not isinstance(document, dict) or completed.returncode != 0 or not document.get("ok"):
        print(json.dumps(document, indent=2, sort_keys=True), file=sys.stderr)
        return False
    return True


def _run_ids(binary: str, project: Path) -> set[str]:
    document = _json_command(binary, ["list", "--project", str(project), "--json"], project)
    runs = document.get("runs")
    if not isinstance(runs, list):
        raise RuntimeError("bmad-loop list JSON did not contain a runs list")
    return {
        str(item["run_id"])
        for item in runs
        if isinstance(item, dict) and isinstance(item.get("run_id"), str)
    }


def _status(binary: str, project: Path, run_id: str) -> dict[str, Any]:
    return _json_command(
        binary,
        ["status", "--project", str(project), "--json", run_id],
        project,
    )


def _story_is_present(document: dict[str, Any], story: str) -> bool:
    if document.get("paused_story_key") == story:
        return True
    tasks = document.get("tasks")
    return isinstance(tasks, list) and any(
        isinstance(task, dict) and task.get("story_key") == story for task in tasks
    )


def _new_story_run(
    binary: str,
    project: Path,
    before: set[str],
    story: str,
    timeout: float,
    poll_interval: float,
) -> tuple[str, dict[str, Any]] | None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        document = _json_command(
            binary, ["list", "--project", str(project), "--json"], project
        )
        runs = document.get("runs")
        if not isinstance(runs, list):
            raise RuntimeError("bmad-loop list JSON did not contain a runs list")
        matching: list[tuple[str, dict[str, Any]]] = []
        for item in runs:
            if not isinstance(item, dict) or not isinstance(item.get("run_id"), str):
                continue
            run_id = item["run_id"]
            if run_id in before:
                continue
            run_status = _status(binary, project, run_id)
            if _story_is_present(run_status, story):
                matching.append((run_id, run_status))
        if len(matching) == 1:
            return matching[0]
        if len(matching) > 1:
            raise RuntimeError(f"multiple new bmad-loop runs claim story {story!r}")
        time.sleep(poll_interval)
    return None


def _story_spec_path(project: Path, spec_folder: str, story: str) -> Path:
    project_root = project.resolve()
    folder = (project_root / spec_folder).resolve()
    candidates = sorted((folder / "stories").glob(f"{story}-*.md"))
    if len(candidates) != 1:
        raise RuntimeError(
            f"expected exactly one dispatch story spec for {story!r}; found {len(candidates)}"
        )
    spec_path = candidates[0].resolve(strict=True)
    if not spec_path.is_file() or not spec_path.is_relative_to(project_root):
        raise RuntimeError("dispatch story spec is not a regular file inside the project")
    return spec_path


def _review_prompt(project: Path, spec_path: Path, story: str) -> str:
    dispatch_folder = spec_path.parent.parent
    return f"""Act as the independent plan gate for story {story!r}.

Read the story specification at {spec_path}, its dispatch folder epic spec at
{dispatch_folder / 'SPEC.md'}, and the repository AGENTS.md. Follow the story's
parent-spec, validation, context, and Code Map references far enough to check
the named files and symbols against the current checkout. Review the complete
plan against the ready-for-development standard: every task must name a real
file and symbol, every acceptance criterion must be observable and in
Given/When/Then form, and every branch must have a named deterministic, device,
or live proof. Check especially that the Android Home route, Device credential
safety, opt-in test isolation, terminal/audio-drain evidence, reconnect and
interrupt behavior, device identity, partial-run handling, and honest
environment-limited outcomes are explicit. Do not invent requirements outside
the story intent.

This is read-only. Use shell reads only; do not edit files, run builds, access
live endpoints, use MCP/CUA, or modify git state. Finish within the harness
deadline. Return ONLY one JSON object with this shape:
{{
  "schema_version": 1,
  "story_key": {json.dumps(story)},
  "phase": "plan",
  "status": "pass",
  "summary": "short summary",
  "findings": [{{"location": "path:line", "summary": "concrete gap", "evidence": "what the plan omits or contradicts"}}]
}}

Use an empty findings array only for a genuine pass. Any concrete gap or
contradiction requires status fail and a finding; status may be pass or fail.
"""


def _review_schema() -> dict[str, Any]:
    finding = {
        "type": "object",
        "required": ["location", "summary", "evidence"],
        "additionalProperties": False,
        "properties": {
            "location": {"type": "string"},
            "summary": {"type": "string"},
            "evidence": {"type": "string"},
        },
    }
    return {
        "type": "object",
        "required": ["schema_version", "story_key", "phase", "status", "summary", "findings"],
        "additionalProperties": False,
        "properties": {
            "schema_version": {"type": "integer", "const": 1},
            "story_key": {"type": "string"},
            "phase": {"type": "string", "const": "plan"},
            "status": {"type": "string", "enum": ["pass", "fail"]},
            "summary": {"type": "string"},
            "findings": {"type": "array", "items": finding},
        },
    }


def _run_dir(project: Path, run_id: str) -> Path:
    runs_root = (project / ".bmad-loop" / "runs").resolve()
    run_dir = (runs_root / run_id).resolve()
    if not run_dir.is_relative_to(runs_root) or not run_dir.is_dir():
        raise RuntimeError(f"bmad-loop run directory is unavailable: {_display_path(run_dir)}")
    return run_dir


def _review_root(project: Path, run_dir: Path, story: str) -> Path:
    try:
        state = json.loads((run_dir / "state.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"paused bmad-loop state is unavailable: {exc}") from exc
    tasks = state.get("tasks")
    task = tasks.get(story) if isinstance(tasks, dict) else None
    if not isinstance(task, dict):
        raise RuntimeError(f"paused bmad-loop state has no task for {story!r}")
    worktree_value = task.get("worktree_path")
    if not worktree_value:
        return project.resolve()
    if not isinstance(worktree_value, str):
        raise RuntimeError(f"paused worktree path for {story!r} is not a string")
    worktrees_root = (run_dir / "worktrees").resolve()
    candidate = Path(worktree_value)
    if not candidate.is_absolute():
        candidate = run_dir / candidate
    candidate = candidate.resolve()
    if not candidate.is_relative_to(worktrees_root) or not candidate.is_dir():
        raise RuntimeError(f"paused worktree for {story!r} is unavailable")
    return candidate


def _write_bytes_atomically(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    try:
        temporary.write_bytes(content)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _write_verdict(run_dir: Path, story: str, verdict: dict[str, Any]) -> Path:
    content = json.dumps(verdict, indent=2, sort_keys=True).encode("utf-8") + b"\n"
    root_path = run_dir / VERDICT_NAME
    task_dir = run_dir / "tasks" / f"{story}-spec-review"
    _write_bytes_atomically(root_path, content)
    _write_bytes_atomically(task_dir / VERDICT_NAME, content)
    marker = "done" if verdict.get("status") == "pass" else "blocked"
    _write_bytes_atomically(
        task_dir / "completion.md",
        f"---\nstatus: {marker}\n---\n\nReview status: {verdict.get('status', 'fail')}\n".encode(
            "utf-8"
        ),
    )
    return root_path


def _failure_verdict(story: str, summary: str, evidence: str) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "story_key": story,
        "phase": "plan",
        "status": "fail",
        "summary": summary,
        "findings": [
            {
                "location": "reviewer-harness",
                "summary": summary,
                "evidence": evidence,
            }
        ],
    }


def _valid_verdict(verdict: Any, story: str) -> bool:
    if not isinstance(verdict, dict):
        return False
    if set(verdict) != {"schema_version", "story_key", "phase", "status", "summary", "findings"}:
        return False
    if type(verdict.get("schema_version")) is not int or verdict["schema_version"] != 1:
        return False
    if verdict.get("story_key") != story or verdict.get("phase") != "plan":
        return False
    if verdict.get("status") not in {"pass", "fail"}:
        return False
    if not isinstance(verdict.get("summary"), str):
        return False
    findings = verdict.get("findings")
    if not isinstance(findings, list) or (verdict["status"] == "pass" and findings):
        return False
    if verdict["status"] == "fail" and not findings:
        return False
    return all(
        isinstance(finding, dict)
        and set(finding) == {"location", "summary", "evidence"}
        and all(isinstance(finding[key], str) for key in ("location", "summary", "evidence"))
        for finding in findings
    )


def _terminate(process: subprocess.Popen[str]) -> None:
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=5)
    except (OSError, subprocess.TimeoutExpired):
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except OSError:
            pass
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass


def _launch_reviewer(
    project: Path,
    run_dir: Path,
    story: str,
    spec: str,
    timeout: float,
) -> dict[str, Any]:
    try:
        review_root = _review_root(project, run_dir, story)
        spec_path = _story_spec_path(review_root, spec, story)
    except (OSError, RuntimeError) as exc:
        return _failure_verdict(story, "dispatch story spec unavailable", str(exc))
    codex = shutil.which("codex")
    if codex is None:
        return _failure_verdict(story, "reviewer unavailable", "codex is not installed")

    review_dir = run_dir / "reviewer"
    review_dir.mkdir(parents=True, exist_ok=True)
    prompt = _review_prompt(review_root, spec_path, story)
    (review_dir / "prompt.txt").write_text(prompt, encoding="utf-8")
    output_path = review_dir / "last-message.json"
    log_path = review_dir / "session.log"
    output_path.unlink(missing_ok=True)
    with tempfile.NamedTemporaryFile(
        mode="w", encoding="utf-8", suffix=".json", prefix="schema-", dir=review_dir, delete=False
    ) as schema_file:
        json.dump(_review_schema(), schema_file)
        schema_path = Path(schema_file.name)

    command = [
        codex,
        "--ask-for-approval",
        "never",
        "exec",
        "-C",
        str(review_root),
        "--ephemeral",
        "--ignore-user-config",
        "--sandbox",
        "read-only",
        "--color",
        "never",
        "--output-schema",
        str(schema_path),
        "--output-last-message",
        str(output_path),
        "-",
    ]
    try:
        with log_path.open("w", encoding="utf-8") as log_file:
            process = subprocess.Popen(
                command,
                cwd=review_root,
                stdin=subprocess.PIPE,
                stdout=log_file,
                stderr=subprocess.STDOUT,
                text=True,
                start_new_session=True,
            )
            try:
                process.communicate(prompt, timeout=timeout)
            except subprocess.TimeoutExpired:
                _terminate(process)
                return _failure_verdict(
                    story,
                    "reviewer timed out",
                    f"codex reviewer exceeded the {timeout:g}-second deadline",
                )
        if process.returncode != 0:
            return _failure_verdict(
                story,
                "reviewer exited unsuccessfully",
                f"codex reviewer returned exit code {process.returncode}",
            )
        try:
            verdict = json.loads(output_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            return _failure_verdict(
                story,
                "reviewer returned no usable verdict",
                f"last-message JSON was missing or invalid: {exc}",
            )
        if not _valid_verdict(verdict, story):
            return _failure_verdict(
                story,
                "reviewer returned an invalid verdict",
                "the result did not match the plan-review schema or pass invariant",
            )
        return verdict
    except OSError as exc:
        return _failure_verdict(story, "reviewer launch failed", str(exc))
    finally:
        schema_path.unlink(missing_ok=True)


def _run_foreground(binary: str, args: list[str], project: Path, timeout: float) -> int:
    print(f"$ {shlex.join([binary, *args])}", file=sys.stderr)
    try:
        return subprocess.run(
            [binary, *args], cwd=project, check=False, timeout=timeout
        ).returncode
    except subprocess.TimeoutExpired:
        print("bmad-loop command timed out", file=sys.stderr)
        return 124


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--spec", default=DEFAULT_SPEC)
    parser.add_argument("--story", default="5-A-4-LIVE-HOME-GATE")
    parser.add_argument("--poll-interval", type=float, default=0.5)
    parser.add_argument("--discovery-timeout", type=float, default=30.0)
    parser.add_argument(
        "--review-timeout",
        type=float,
        default=900.0,
        help="seconds allowed for the independent plan reviewer (default: 900)",
    )
    parser.add_argument(
        "--loop-timeout",
        type=float,
        default=3600.0,
        help="seconds allowed for each foreground bmad-loop command (default: 3600)",
    )
    parser.add_argument("--no-resume", action="store_true")
    args = parser.parse_args(argv)
    if min(args.poll_interval, args.discovery_timeout, args.review_timeout, args.loop_timeout) <= 0:
        parser.error("poll and timeout values must be positive")
    if not STORY_ID_RE.fullmatch(args.story):
        parser.error("story must contain only letters, numbers, '.', '_' and '-'")

    project = args.project.expanduser().resolve()
    if not project.is_dir():
        print(f"project does not exist: {_display_path(project)}", file=sys.stderr)
        return 2
    try:
        binary = _loop_binary()
        if not _validate(binary, project, args.spec):
            print("bmad-loop preflight failed; nothing was started", file=sys.stderr)
            return 2
        before = _run_ids(binary, project)
        run_rc = _run_foreground(
            binary,
            ["run", "--project", str(project), "--spec", args.spec, "--story", args.story],
            project,
            args.loop_timeout,
        )
        if run_rc != 0:
            print(f"bmad-loop run stopped with exit code {run_rc}; not reviewing", file=sys.stderr)
            return run_rc
        found = _new_story_run(
            binary, project, before, args.story, args.discovery_timeout, args.poll_interval
        )
        if found is None:
            print(f"could not identify the new run for story {args.story!r}", file=sys.stderr)
            return 3
        run_id, run_status = found
        if run_status.get("status") != "paused" or run_status.get("paused_stage") != PLAN_CHECKPOINT:
            print(
                f"run {run_id} did not pause at the plan checkpoint "
                f"(status={run_status.get('status')!r}, paused_stage={run_status.get('paused_stage')!r})",
                file=sys.stderr,
            )
            return 3

        run_dir = _run_dir(project, run_id)
        verdict = _launch_reviewer(project, run_dir, args.story, args.spec, args.review_timeout)
        verdict_path = _write_verdict(run_dir, args.story, verdict)
        print(f"plan reviewer wrote {_display_path(verdict_path)}", file=sys.stderr)
        if verdict.get("status") != "pass":
            print(f"plan reviewer returned {verdict.get('status')!r}; plan remains paused", file=sys.stderr)
            print(json.dumps(verdict.get("findings", []), indent=2, sort_keys=True), file=sys.stderr)
            return 4
        if args.no_resume:
            return 0
        return _run_foreground(
            binary, ["resume", "--project", str(project), run_id], project, args.loop_timeout
        )
    except (OSError, RuntimeError) as exc:
        print(f"bmad-loop supervisor: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

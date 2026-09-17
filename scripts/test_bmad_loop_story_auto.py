from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("bmad-loop-story-auto.py")
SPEC = importlib.util.spec_from_file_location("bmad_loop_story_auto", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class HarnessTests(unittest.TestCase):
    def test_rendered_pass_requires_empty_findings(self) -> None:
        passing = {
            "schema_version": 1,
            "story_key": "5-A-4-LIVE-HOME-GATE",
            "phase": "plan",
            "status": "pass",
            "summary": "ready",
            "findings": [],
        }
        self.assertTrue(MODULE._valid_verdict(passing, "5-A-4-LIVE-HOME-GATE"))
        passing["findings"] = [{"location": "x", "summary": "gap", "evidence": "e"}]
        self.assertFalse(MODULE._valid_verdict(passing, "5-A-4-LIVE-HOME-GATE"))
        passing["findings"] = []
        passing["unexpected"] = "not allowed"
        self.assertFalse(MODULE._valid_verdict(passing, "5-A-4-LIVE-HOME-GATE"))

    def test_build_auto_render_command_has_no_interpolated_quotes(self) -> None:
        root = Path(__file__).parents[1]
        for tree in (".agents", ".claude"):
            skill = root / tree / "skills/bmad-build-auto/SKILL.md"
            text = skill.read_text(encoding="utf-8")
            self.assertIn(
                f"uv run --no-cache _bmad/scripts/render_skill.py --project-root . --skill {tree}/skills/bmad-build-auto",
                text,
            )
            self.assertNotIn("{project-root}", text)
            self.assertNotIn("{skill-root}", text)

    def test_failed_verdict_cannot_be_empty(self) -> None:
        failed = MODULE._failure_verdict("5-A-4-LIVE-HOME-GATE", "gap", "evidence")
        self.assertTrue(MODULE._valid_verdict(failed, "5-A-4-LIVE-HOME-GATE"))
        failed["findings"] = []
        self.assertFalse(MODULE._valid_verdict(failed, "5-A-4-LIVE-HOME-GATE"))

    def test_verdict_is_written_identically_to_root_and_task(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            run_dir = Path(directory)
            verdict = MODULE._failure_verdict("5-A-4-LIVE-HOME-GATE", "timeout", "evidence")
            root = MODULE._write_verdict(run_dir, "5-A-4-LIVE-HOME-GATE", verdict)
            task = run_dir / "tasks" / "5-A-4-LIVE-HOME-GATE-spec-review" / MODULE.VERDICT_NAME
            self.assertEqual(root.read_bytes(), task.read_bytes())
            self.assertEqual(json.loads(root.read_text()), verdict)

    def test_reviewer_resolves_the_single_generated_dispatch_spec(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            story_dir = project / "dispatch" / "stories"
            story_dir.mkdir(parents=True)
            story = story_dir / "5-A-4-LIVE-HOME-GATE-live-home-gate.md"
            story.write_text("---\nstatus: ready-for-dev\n---\n", encoding="utf-8")
            self.assertEqual(
                MODULE._story_spec_path(project, "dispatch", "5-A-4-LIVE-HOME-GATE"),
                story.resolve(),
            )

    def test_reviewer_refuses_missing_or_ambiguous_dispatch_spec(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            stories = project / "dispatch" / "stories"
            stories.mkdir(parents=True)
            with self.assertRaises(RuntimeError):
                MODULE._story_spec_path(project, "dispatch", "5-A-4-LIVE-HOME-GATE")
            for suffix in ("live-home-gate", "duplicate"):
                (stories / f"5-A-4-LIVE-HOME-GATE-{suffix}.md").write_text(
                    "---\nstatus: ready-for-dev\n---\n", encoding="utf-8"
                )
            with self.assertRaises(RuntimeError):
                MODULE._story_spec_path(project, "dispatch", "5-A-4-LIVE-HOME-GATE")

    def test_reviewer_uses_the_paused_story_worktree(self) -> None:
        story_key = "5-A-4-LIVE-HOME-GATE"
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            run_dir = project / ".bmad-loop" / "runs" / "run-1"
            worktree = run_dir / "worktrees" / story_key
            worktree.mkdir(parents=True)
            (run_dir / "state.json").write_text(
                json.dumps({"tasks": {story_key: {"worktree_path": str(worktree)}}}),
                encoding="utf-8",
            )
            self.assertEqual(MODULE._review_root(project, run_dir, story_key), worktree.resolve())

    def test_story_id_cannot_escape_run_directory(self) -> None:
        self.assertIsNone(MODULE.STORY_ID_RE.fullmatch("../outside"))
        self.assertIsNotNone(MODULE.STORY_ID_RE.fullmatch("5-A-4-LIVE-HOME-GATE"))

    def test_reviewer_timeout_returns_fail_closed_verdict(self) -> None:
        class StalledProcess:
            pid = 1234
            returncode = None

            def poll(self):
                return None

            def communicate(self, *_args, **_kwargs):
                raise MODULE.subprocess.TimeoutExpired("codex", 1)

            def wait(self, **_kwargs):
                self.returncode = -15

        with tempfile.TemporaryDirectory() as directory:
            process = StalledProcess()
            run_dir = Path(directory) / "run"
            run_dir.mkdir()
            (run_dir / "state.json").write_text(
                json.dumps(
                    {
                        "tasks": {
                            "5-A-4-LIVE-HOME-GATE": {"worktree_path": ""},
                        }
                    }
                ),
                encoding="utf-8",
            )
            stories = Path(directory) / "dispatch" / "stories"
            stories.mkdir(parents=True)
            (stories / "5-A-4-LIVE-HOME-GATE-live-home-gate.md").write_text(
                "---\nstatus: ready-for-dev\n---\n", encoding="utf-8"
            )
            with mock.patch.object(MODULE.shutil, "which", return_value="/usr/bin/codex"):
                with mock.patch.object(MODULE.subprocess, "Popen", return_value=process) as popen:
                    with mock.patch.object(MODULE.os, "killpg"):
                        verdict = MODULE._launch_reviewer(
                            Path(directory),
                            run_dir,
                            "5-A-4-LIVE-HOME-GATE",
                            "dispatch",
                            1,
                        )
            self.assertEqual(verdict["status"], "fail")
            self.assertEqual(verdict["summary"], "reviewer timed out")
            command = popen.call_args.args[0]
            self.assertIsInstance(command, list)
            self.assertIn("--ignore-user-config", command)
            self.assertIn("--sandbox", command)
            self.assertIn("read-only", command)
            self.assertLess(command.index("--ask-for-approval"), command.index("exec"))
            self.assertEqual(command[command.index("-C") + 1], str(Path(directory).resolve()))
            self.assertEqual(MODULE._review_schema()["properties"]["schema_version"]["type"], "integer")


if __name__ == "__main__":
    unittest.main()

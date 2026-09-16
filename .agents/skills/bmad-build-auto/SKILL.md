---
name: bmad-build-auto
description: 'One iteration of an unattended development loop. Use when invoked by name'
---

Run the following command exactly once without changing the current working directory. The
session starts in the project root, so keep these arguments exactly as written. Do not
rebuild the command with quoted absolute paths or concatenate a run/worktree identifier:

```bash
uv run --no-cache _bmad/scripts/render_skill.py --project-root . --skill .agents/skills/bmad-build-auto
```

- On success, read and follow the one absolute `workflow.md` instruction printed to stdout.
- On failure (including `uv` being unavailable), report the command output and HALT. Do not run any workflow source directly.

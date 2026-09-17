#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
project_root="$(cd -- "$script_dir/.." && pwd)"
python_bin="${PYTHON_BIN:-python3}"

if "$python_bin" - "$project_root" <<'PY' >/dev/null 2>&1
import hashlib
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
canonical = root / "_bmad-output/implementation-artifacts/spec-5-a-4-live-home-gate.md"
dispatch_story = root / (
    "_bmad-output/implementation-artifacts/next-wave-android-live-home-gate/"
    "stories/5-A-4-LIVE-HOME-GATE-live-home-gate.md"
)
dispatch_spec = root / (
    "_bmad-output/implementation-artifacts/next-wave-android-live-home-gate/SPEC.md"
)
stories = root / (
    "_bmad-output/implementation-artifacts/next-wave-android-live-home-gate/stories.yaml"
)

for path in (canonical, dispatch_story, dispatch_spec, stories):
    if not path.is_file():
        raise SystemExit(1)

def frontmatter(path):
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---\n"):
        raise SystemExit(1)
    end = text.find("\n---\n", 4)
    if end < 0:
        raise SystemExit(1)
    values = {}
    for line in text[4:end].splitlines():
        match = re.fullmatch(r"([A-Za-z0-9_]+):[ \t]*(.*)", line)
        if match:
            values[match.group(1)] = match.group(2).strip().strip("'")
    return values

def body(path):
    text = path.read_text(encoding="utf-8")
    end = text.find("\n---\n", 4)
    if end < 0:
        raise SystemExit(1)
    return text[end + 5:]

dispatch = frontmatter(dispatch_story)
expected = hashlib.sha256(canonical.read_bytes()).hexdigest()
if dispatch.get("authority_revision") != "5":
    raise SystemExit(1)
if dispatch.get("canonical_spec_sha256") != expected:
    raise SystemExit(1)
if body(canonical) != body(dispatch_story):
    raise SystemExit(1)
if "5-A-4-LIVE-HOME-GATE" not in dispatch_story.read_text(encoding="utf-8"):
    raise SystemExit(1)
if "spec-5-a-4-live-home-gate.md" not in dispatch_spec.read_text(encoding="utf-8"):
    raise SystemExit(1)
PY
then
    printf '%s\n' 'DISPATCH=PASS'
else
    printf '%s\n' 'DISPATCH=FAIL'
    exit 64
fi

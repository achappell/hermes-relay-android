---
status: blocked
---

# BMad Build Auto Result

Status: blocked
Blocking condition: dirty working tree; untracked runtime trees `.agents/`, `.claude/`, and `_bmad/` are present

The Android migration target was resolved to story 5, `[Android] Migrate the Android client`, in the Standard Hermes compatibility migration story list. The required `git add --refresh -- .` check completed, but the working tree remained dirty because those untracked trees are not ignored or tracked.

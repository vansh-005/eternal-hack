---
description: Run the checks for a phase gate (G0–G4) and report pass/fail
argument-hint: <G0|G1|G2|G3|G4>
---

Run gate **$ARGUMENTS** from `TASKS.md` ("Gates" section).

1. Read the gate's checklist in `TASKS.md`.
2. Execute every check command exactly as written. Do not substitute weaker checks.
3. For each check print: `PASS` / `FAIL`, the command, and the last 10 lines of output.
4. If anything fails, list the owning task ID for each failure (from TASKS.md) and stop. Do not fix code in this command.
5. If all pass, tick the gate in `TASKS.md` and record the time.

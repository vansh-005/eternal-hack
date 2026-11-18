---
description: Implement one task from TASKS.md end to end
argument-hint: <TASK-ID, e.g. B-04>
---

Implement task **$ARGUMENTS** from `TASKS.md`.

1. Read `CLAUDE.md` fully. Read the `$ARGUMENTS` block in `TASKS.md`, then every doc it links under "Spec".
2. Confirm every task in its "Depends" line is ticked `[x]`. If one is not, stop and report which.
3. Confirm every path you will touch is owned by this task's owner (CLAUDE.md §3). List the files you plan to create or edit before writing code.
4. Write the tests named in the task first (where the task lists tests). Run them and show they fail for the right reason.
5. Implement. Follow CLAUDE.md §5 and §6.
6. Run every command under "Done when". If any fails, fix and rerun. Do not tick a task with a failing check.
7. Tick the task in `TASKS.md`, add one line of notes under it, and commit with message `$ARGUMENTS: <summary>`.
8. Reply with: files changed, test results (tail), anything that deviated from spec and the DECISIONS.md line you added.

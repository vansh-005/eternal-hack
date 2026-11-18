# AGENTS.md

All agent instructions for this repository live in **`CLAUDE.md`**. Read it fully before doing anything, then read your task in `TASKS.md` and the docs it links.

Short version for tools that only read this file:

- Spec lives in `docs/`. `docs/DOMAIN.md` wins any conflict.
- Edit only paths your owner (B / F / Q) owns — see `CLAUDE.md` §3.
- Tests first for domain logic, races and API contracts. No `Thread.sleep` in tests; use `MutableClock`.
- NoCook never mutates OMS. Every side effect is an outbox row written in the same transaction as the state change.
- Metric names in `docs/OBSERVABILITY.md` are a contract with the dashboards.
- Run the task's "Done when" commands, tick it in `TASKS.md`, commit as `<TASK-ID>: <summary>`.

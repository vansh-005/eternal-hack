---
description: Full local verification before a commit to main
---

1. `make test` — backend unit + Testcontainers ITs + frontend vitest. All must pass.
2. If the stack is running (`make ps`), run `make smoke`.
3. `cd frontend && npm run lint && npm run build`.
4. Grep for forbidden patterns and report any hit with file:line:
   - `Instant.now(`, `LocalDateTime.now(`, `System.currentTimeMillis(` under `backend/*/src/main`
   - `Thread.sleep(` under `backend/*/src/test`
   - `@MockBean`, `com.fasterxml.jackson.databind`, `lombok` anywhere in `backend/`
   - `localhost` under `frontend/src`
5. Summarise: pass/fail per step.

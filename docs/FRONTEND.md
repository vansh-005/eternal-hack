# FRONTEND.md — React app (owner F)

One Vite + React 19 + TypeScript app, served by nginx in the `web` container on `:8080`. Three audiences: the **judge** (Simulator), the **kitchen** (Tablet), the **ops/judge-proof view** (Ops).

## 1. Layout

```
frontend/
├── Dockerfile               node:24-alpine build → nginx:stable-alpine (pinned) serving dist/ + nginx.conf
├── nginx.conf               see §7
├── index.html  vite.config.ts  tailwind (v4, @import "tailwindcss")  tsconfig.json (strict)
└── src/
    ├── main.tsx  App.tsx (router)
    ├── api/        client.ts (fetch wrapper, JSON, error → ApiError), types.ts (copied from API.md), queries.ts (TanStack hooks)
    ├── mqtt/       useMqtt.ts (single connection per tab, subscribe/unsubscribe, status), topics.ts
    ├── lib/        time.ts (countdown from server timestamps), sound.ts (WebAudio beep, no assets), ids.ts (uuid)
    ├── components/ ProofBadge.tsx, GrafanaPanel.tsx, StatusDot.tsx, Countdown.tsx, Timeline.tsx, Kpi.tsx
    └── routes/
        ├── simulator/  SimulatorPage.tsx, OrderControls.tsx, LoadControls.tsx
        ├── tablet/     TabletPage.tsx, OrderQueue.tsx, CancelCard.tsx, TakeoverOverlay.tsx, CallScreen.tsx, useTabletCases.ts
        └── ops/        OpsLayout.tsx, LivePage.tsx, CaseDetailPage.tsx, ChaosPanel.tsx, RunsPage.tsx, FaultsPage.tsx, ImpactPage.tsx
```

`vite.config.ts` dev proxy: `/api`, `/oms` → `http://localhost:8080`; `/mqtt` → `ws://localhost:8080` (`ws: true`).

## 2. Routes

| Route | Purpose | Query params |
|---|---|---|
| `/simulator` | Judge drives one demo order | `outlet` (default `outlet-demo`) |
| `/tablet` | Restaurant tablet | `outlet` (default `outlet-demo`), `device` (default `tab-1`) |
| `/ops` | Live: KPIs, case list, embedded Grafana, chaos | — |
| `/ops/cases/:caseId` | Server timeline for one case | — |
| `/ops/runs` | P1–P6 results table (MEASURED) | — |
| `/ops/faults` | Fault-table results (TESTED) | — |
| `/ops/impact` | 10,000-scenario A/B (SIMULATED) | — |

## 3. Simulator page (`/simulator`)

Two columns. Left: **order controls** for the demo outlet. Right: the selected order's live snapshot (poll `GET /oms/api/orders/{id}` every 1 s) and a link "Open timeline" once a case exists.

Buttons (each disabled unless the OMS transition is legal, per DATA_MODEL.md §1):
`New order (Margherita ₹450)` → `Accept` → `Start prep` → `Into oven` → `Mark ready` → `Pick up`
`Customer cancels` (primary, red) · `Restaurant cancels` · `Reinstate` (test)

Panel "Race demos": `Ready-then-cancel ×500` (calls race endpoint), `Duplicate storm ×20` (ops chaos).

Panel "Background load" (status only): shows `GET /oms/api/firehose` achieved rate + a hint "start profiles with `make run P=P3`". Firehose start/stop buttons are allowed here for manual demos.

## 4. Tablet page (`/tablet?outlet=…&device=…`)

Data sources:
- Order queue: `GET /oms/api/outlets/{outlet}/orders?active=true` every 2 s.
- Cases: `GET /api/outlets/{outlet}/open-cases` on mount **and on every MQTT (re)connect**; then live updates from MQTT `cards` and `calls` topics.
- Case store (`useTabletCases`): `Map<caseId, CardMessage>`; apply a message only if `caseVersion >= stored.caseVersion`; `CLOSE` removes the card (after a 1.5 s "Closed: acknowledged via IVR" toast).

Screen (landscape, 1024×768 minimum, also usable on a phone):

| Element | Behaviour |
|---|---|
| Header | outlet name, device id, MQTT `StatusDot` (green connected / amber reconnecting / red offline), clock |
| Order queue (left 40%) | cards per active order: ref, items, status/stage chip, time since accepted |
| Cancellation cards (right 60%) | pinned, red border, beep on arrival (WebAudio, 3 short tones; tap anywhere once to unlock audio — show a banner until unlocked). Shows: order ref, "Cancelled HH:MM:SS by customer", **countdown to takeover** (`l1At`) then **to call** (`ivrAt`), three big buttons: **Stopped** / **Hadn't started** / **Already cooked** |
| ACK click | `POST /api/cases/{id}/ack` with a fresh idempotencyKey per click attempt (reuse it on network retry). Optimistic: buttons disabled + spinner. 200 → card closes locally; 409 → show the reason from the body and close |
| Takeover (`TAKEOVER` message or state `ESCALATED_L1`) | full-screen red overlay with the same three buttons, order ref huge, "Manager alerted". The order queue strip stays visible at the bottom (accepted orders must not be hidden). |
| Call screen (`INCOMING_CALL`) | phone UI: ringing animation, script text, buttons **Answer / Reject / Busy / No answer / Network fail**; after Answer → keypad **1 2 3** (+ 4–9, 0 disabled). Each button calls `POST /api/mock-telephony/calls/{providerCallId}/{action}`. `CALL_ENDED` or card `CLOSE` dismisses it |
| Receipts | on every `CardMessage`: POST `DELIVERED` immediately in the MQTT handler; POST `RENDERED` in `requestAnimationFrame` after the card mounts; on `CLOSE`: POST `DISMISSED` after removal. Fire-and-forget, retry ×3 |
| Offline toggle | dev switch "Simulate offline" disconnects MQTT for 20 s, then reconnects → snapshot GET proves no card is lost and expired cards never reappear |

## 5. Ops pages

All numbers use `<ProofBadge kind n runId />`. `ProofBadge` renders e.g. `MEASURED · N=12,408 · run-20261121-1930-P3`. If `n < 1000` for a p99, render p99 as `—` with tooltip "p99 hidden below N = 1,000".

### 5.1 `/ops` (Live)

- **KPI strip** from `GET /api/ops/summary` every 2 s: open cases, opened/acked/expired last 5 min, queue ready, DLQ, outbox pending, overdue deadlines, replica status chips (`nocook-1 ● RUNNING`).
- **Embedded Grafana** (`GrafanaPanel`): iframe `${GRAFANA}/d-solo/nocook-live-slos/live-slos?orgId=1&panelId=<id>&refresh=5s&theme=dark&from=now-15m&to=now`. Panel ids are fixed in DASHBOARDS.md. Show 4: Case open p50/p95/p99, Card delivery, ACK API, Timer lateness. Each with a link "Open in Grafana".
- **Case list** (`GET /api/ops/cases?limit=50`, every 2 s): time, order ref, outlet, state chip, age, close reason. Row click → `/ops/cases/:id`. Filter: demo outlet only (default) / all.
- **ChaosPanel**: buttons with current state from `GET /api/ops/chaos/state`:
  `DB +300 ms` (toggle) · `OMS down` (toggle) · `Pause consumers` (toggle) · `Kill nocook-2` · `Duplicate storm ×20` · `Reset all`. Each shows a confirm-free toast with the time it fired (the Grafana annotation lands at the same instant).

### 5.2 `/ops/cases/:caseId`

`GET /api/cases/{id}/timeline` every 1 s while open. Vertical timeline: `HH:mm:ss.SSS  KIND  channel/device  metadata`, with `+Δ ms from cancel` on the right. Highlight rows: `CARD_RENDERED` (grey, label "not an acknowledgement"), `L1_TRIGGERED` (amber), `IVR_RESERVED` (orange), `*_ACKNOWLEDGED` (green), `CASE_CLOSED`. Header shows `derived` ms values. Link "Trace in Tempo" when `traceId` present: `${GRAFANA}/explore?…` (TraceQL `{ trace:id = "<id>" }`).

### 5.3 `/ops/runs` (MEASURED)

Table from `GET /api/ops/runs` + details: one row per run: profile, target rate, achieved rate, duration, N(case.open), case.open p50/p95/p99, SLO share ≤2 s, card.delivery p99, ACK p99, lateness p99, peak queue depth, drain time, k6 dropped iterations, verdict (PASS / VOID / FAIL). Click a row → expands invariants and the Grafana snapshot links (`from`/`to` = run window). A "Knee" card for P6: highest sustained cases/s with p99 ≤ 2 s, and the bottleneck text from the run notes.

### 5.4 `/ops/faults` (TESTED)

The fault table from LOAD_AND_CHAOS.md §6 with columns: fault, how injected, expected, observed (from the run's `invariants.json` + notes), PASS/FAIL, run link.

### 5.5 `/ops/impact` (SIMULATED)

From `GET /api/ops/simulations/latest` (`sim-summary.json`, SIMULATION.md §5). Big banner: "SIMULATED — modelled behaviour with assumptions v1, seed 2026, N = 10,000 paired scenarios". Charts (recharts):
1. Time-to-kitchen-stop CDF, baseline vs NoCook (two lines, x = seconds 0–600).
2. ₹ prep cost avoided: total, per 1,000 cancellations, with sensitivity bars at late-notice 5 / 10 / 20 %.
3. Late discovery rate (>120 s): baseline vs NoCook.
4. Funnel: opened → acked on card → acked after L1 → IVR → acked by IVR → expired.
5. IVR calls per 1,000 cases.
6. Assumptions table (every parameter, with "assumed" tags) and the manifest (seed, commit, run time).

## 6. Shared components

- `ProofBadge({ kind, n, runId })`: colour by kind (MEASURED blue, TESTED green, SIMULATED purple).
- `GrafanaPanel({ dashboardUid, panelId, height, from?, to? })`: iframe, lazy, `title` attribute for a11y.
- `Countdown({ to })`: renders `mm:ss` from server `to` minus `Date.now()`, red under 10 s.
- `Timeline({ events, cancelledAt })`.

## 7. nginx.conf (in `frontend/`)

```nginx
upstream nocook_upstream {
    server nocook-1:8080 max_fails=1 fail_timeout=5s;
    server nocook-2:8080 max_fails=1 fail_timeout=5s;
    server nocook-3:8080 max_fails=1 fail_timeout=5s;
}
server {
    listen 80;
    root /usr/share/nginx/html;
    location / { try_files $uri /index.html; }
    location /api/ {
        proxy_pass http://nocook_upstream;
        proxy_next_upstream error timeout http_502 http_503;
        proxy_connect_timeout 1s;
        proxy_set_header Host $host;
    }
    location /oms/ { proxy_pass http://mock-oms:8090; }
    location /mqtt {
        proxy_pass http://mosquitto:9001;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 1h;
    }
}
```

nginx resolves upstream names at start, so `web` must start after all three replicas are healthy (compose `depends_on: condition: service_healthy`). After a replica is killed, nginx keeps routing to the survivors via `proxy_next_upstream`.

## 8. Tests (Vitest)

- `useTabletCases` reducer: version ordering, CLOSE removal, snapshot merge on reconnect.
- `Countdown`: uses server timestamps.
- `ProofBadge`: hides p99 when n < 1000.
- Optional (cut first): Playwright smoke that opens `/tablet`, cancels via OMS API, waits for the card.

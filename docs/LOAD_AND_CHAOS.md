# LOAD_AND_CHAOS.md — load profiles, generators, faults, run protocol (owner Q)

Headline every run serves: **"At 10× Zomato's record New Year's Eve peak, on one laptop, p99 case-open was X s, and the system's limit was Y cases/s, set by Z."**

## 1. Where load comes from

| Generator | Path exercised | Rate driver |
|---|---|---|
| **k6** (`load/profiles.js`) | real cancellations: HTTP → OMS tx + outbox → relay → RabbitMQ → NoCook | open-model arrival rate (no coordinated omission) |
| **Firehose** (mock-oms) | non-cancel order events straight to exchange `order.events`; dropped by the broker (no binding) | `POST /oms/api/firehose {eventsPerSecond}` |
| **Tablet swarm** (`tools/tablet-swarm.mjs`) | MQTT card delivery, receipts, ACK API, fan-out for `outlet-load-*` | reacts to cards |
| **Mock gateway auto-outcomes** | full IVR path (reserve → place → signed webhooks → DTMF ACK) for `outlet-load-*` | reacts to calls |

Rates use the submission's assumptions: 0.6 % eligible cancellations per order, 10 lifecycle events per order. Zomato's NYE 2023 record: 8,422 orders in the minute at 8:06 pm = 140 orders/s.

## 2. Profiles

| Profile | Orders/s (notional) | Firehose events/s into the broker | Cancels/s (k6) | k6 executor | Duration | Purpose |
|---|---|---|---|---|---|---|
| P1 Dinner peak | 93 | 933 | 0.56 | `constant-arrival-rate` rate 28, timeUnit `50s` | 5 min | sanity at the modelled peak |
| P2 NYE record | 140 | 1,400 | 0.84 | `constant-arrival-rate` rate 42, timeUnit `50s` | 20 min | real record; 20 min so N ≥ 1,000 for p99 |
| P3 10× NYE | 1,400 | 14,000 | 8.4 | `constant-arrival-rate` rate 42, timeUnit `5s` | 10 min | **live demo load** |
| P4 100× cases | 1,400 (held) | 14,000 | 56 | `constant-arrival-rate` rate 56, timeUnit `1s` | 10 min | blueprint stress: ~170 timer firings/s |
| P5 Burst | 1,400 (held) | 14,000 | 200 for 60 s, then 0.84 | two scenarios: `constant-arrival-rate` 200/s 60 s; then rate 42/`50s` from `startTime: 60s` for 4 min | 5 min | backlog peak + drain time |
| P6 Knee ramp | 1,400 (held) | 14,000 | 10 → 500 | `ramping-arrival-rate` startRate 10, timeUnit `1s`, stages `[{target: 500, duration: '10m'}]` | 10 min | the laptop's limit and its bottleneck |

`TRACING_SAMPLE=0.1` for P4–P6 (set by `tools/run.mjs` via a compose env override; replicas must be restarted with it before the run — or keep 1.0 if Tempo copes; check at G2).

### 2.1 `load/profiles.js` (one script, profile via `__ENV.PROFILE`)

Per iteration (one cancellation):

```js
// outlet spread over 500 load outlets
const outletId = `outlet-load-${String((exec.scenario.iterationInTest % 500) + 1).padStart(4, '0')}`;
// 1) create an already-accepted order (one HTTP call, one tx)
POST ${BASE_URL}/oms/api/load/accepted-orders
     { outletId, stage: pick(['NOT_STARTED','PREP','OVEN']), acceptedSecondsAgo: randInt(30, 600), valuePaise: randInt(15000, 90000) }
     headers: { 'Content-Type': 'application/json', 'X-Run-Id': __ENV.RUN_ID }, tags: { name: 'create' }
// 2) 3 % of iterations: mark ready first (exercises NOT_ACTIONABLE READY_OR_PICKED_UP)
// 3) cancel: 95 % CUSTOMER, 5 % RESTAURANT (exercises RESTAURANT_INITIATED)
POST ${BASE_URL}/oms/api/orders/${orderId}/cancel   { initiatedBy }   tags: { name: 'cancel' }
```

Options: `discardResponseBodies: false` (need orderId), `thresholds: { dropped_iterations: ['count==0'], http_req_failed: ['rate<0.01'] }` (no `abortOnFail`; verdict decides), `preAllocatedVUs` ≈ 2 × rate, `maxVUs` 2,000 for P5/P6. `handleSummary(data)` → `{ ['/results/' + __ENV.RUN_ID + '/k6-summary.json']: JSON.stringify(data), stdout: textSummary(...) }`.

## 3. Tablet swarm (`tools/tablet-swarm.mjs`, compose service `swarm`, profile `load`)

- One MQTT connection, subscribes `nocook/outlets/+/cards` and `nocook/outlets/+/calls`; ignores outlets not starting with `outlet-load-`.
- Device id `swarm-<outletNo>`. Sends headers `X-Outlet-Id`, `X-Device-Id`.
- On `OPEN`: POST `DELIVERED` immediately, `RENDERED` after 16–50 ms. Decide behaviour by `hash(caseId) % 100`:
  - 0–59 → ACK at `openedAt + U(5 s, 25 s)` (before takeover)
  - 60–79 → ACK at `openedAt + U(32 s, 60 s)` (after takeover)
  - 80–99 → never ACK (case goes to IVR; the mock gateway answers)
  ACK choice: 70 % STOPPED, 20 % NOT_STARTED, 10 % ALREADY_COOKED.
- On `CLOSE`: POST `DISMISSED`.
- HTTP via one keep-alive agent, max 256 sockets. Log a line every 10 s: cards/s, acks/s, p99 client-side handling time. If handling falls behind (> 200 ms queueing), print a WARN: the swarm's own lag is included in `card.delivery{client="swarm"}`. Human-tablet numbers (`client="tablet"`) are reported separately.

## 4. Mock gateway auto-outcomes (only for outlets with prefix `outlet-load-`)

After `placeCall`, a virtual thread plays a provider script through the **real signed webhooks**:

| Share | Script |
|---|---|
| 75 % | `RINGING` +1 s → `ANSWERED` +4 s → DTMF `1|2|3` +8 s → `COMPLETED` +9 s |
| 15 % | `RINGING` +1 s → `NO_ANSWER` +30 s |
| 10 % | `BUSY` +3 s |

`outlet-demo` never auto-plays: a human presses the buttons.

## 5. Run protocol — `make run P=P3` → `tools/run.mjs`

1. `runId = run-YYYYMMDD-HHMM-<P>` (+ `-1rep` when `REPLICAS=1`).
2. Preflight: web healthy; `POST /api/ops/chaos/reset`; queue `nocook.cancellations` ready = 0; warn if open cases > 0.
3. Write `results/<runId>/manifest.json` (OUTPUT.md §2).
4. Grafana annotation `RUN_START` (tags `run`, profile, runId).
5. Start firehose at the profile's rate; wait 30 s warm-up.
6. Ensure `swarm` is running (`docker compose --profile load up -d swarm`).
7. `docker compose --profile load run --rm -e RUN_ID -e PROFILE k6 run -o opentelemetry /scripts/profiles.js`.
8. When k6 exits: wait until queue ready = 0 and outbox pending = 0 (max 120 s), then 90 s more so in-flight escalations (≤ 75 s) complete.
9. Stop firehose. Grafana annotation `RUN_END`.
10. `node tools/export-run.mjs --run <runId>` (OUTPUT.md §3).
11. Print the one-line headline and the verdict.

Window for all exported numbers: `[RUN_START + 30 s warm-up, k6 end]` for latencies; invariants over the whole run including drain.

## 6. Fault runs (TESTED) — each is its own run with background P3 unless noted

| ID | Fault | How (Ops button or `tools/run.mjs --fault`) | When | Pass criteria (all from `invariants.json` + Prometheus) |
|---|---|---|---|---|
| F1 | Scale-out | `REPLICAS=1 make run P=P4`, then `make run P=P4` | — | Report both: case-open p99, queue depth peak, cases/s at knee. Honest note: replicas share one CPU, gain may be small |
| F2 | Kill a replica | `kill-replica nocook-2` | t = 3 min | `lost_cases = 0`, `max_ivr_per_case ≤ 1`, `escalations_after_close = 0`; queue drains; case-open p99 back under 2 s within 60 s |
| F3 | Pause consumers 60 s | `listener STOPPED` → 60 s → `RUNNING` | t = 3 min | queue depth rises then drains to 0; `lost_cases = 0`; `escalations_after_max_age = 0` |
| F4 | OMS down 30 s | `oms-down true` → 30 s → `false` | t = 3 min | 0 `L1_TRIGGERED`/`IVR_ESCALATED` facts timestamped inside the down window; `escalations{outcome="deferred"}` > 0; `lost_cases = 0` after recovery |
| F5 | Slow DB +300 ms for 60 s | `db-latency 300` → 60 s → `0` | t = 3 min | timer-lateness p99 visibly rises then recovers below 3 s within 60 s of removal; invariants all 0 |
| F6 | Duplicate storm ×20 | `duplicate-storm {lastN:50, times:20}` | t = 2 min | duplicates received ≈ 1,000; `duplicate_cases = 0` |
| F7 | Ready-before-cancel race ×500 | `POST /oms/api/test/race/ready-then-cancel {count:500}` | t = 2 min | the 500 cases are `NOT_ACTIONABLE` (`READY_OR_PICKED_UP`); 0 escalation facts on them |
| F8 | ACK vs IVR race ×1,000 | Testcontainers test R3 (TESTING.md) | — | `max_ivr_per_case ≤ 1`; when ACK commits first, no call attempt |
| F9 | Forged / replayed webhooks | Testcontainers test R10 | — | 401 on bad signature; replay has no effect |

`tools/run.mjs --profile P3 --fault F2` fires the chaos action at the given time and writes the fault's start/end into the manifest so the exporter can evaluate the window-based criteria.

## 7. Knee (P6)

Computed by the exporter from 30 s windows: **knee = the highest window cases/s where case-open p99 ≤ 2 s and the queue depth slope ≤ 0.** The bottleneck is written by a human after looking at panels 204–206 (CPU at 100 %? Hikari pending > 0? swarm lagging?) into `results/<runId>/notes.md`, which the exporter copies into `summary.json.bottleneck`.

## 8. Rules that keep the numbers honest

- k6 dropped iterations > 0 → run **VOID** (the laptop couldn't generate the load), not a system failure.
- N shown beside every percentile; p99 hidden below N = 1,000.
- All containers share one clock, so cross-service timestamps compare directly.
- Record host CPU model, cores, RAM, OS and Docker resource limits in the manifest.
- On an 8 GB machine: one replica, skip P4–P6.

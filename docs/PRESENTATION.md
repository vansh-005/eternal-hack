# NoCook: mid-eval one-pager and run book

Use Part 1 as the page you speak from. Use Part 2 to start and drive everything. Part 3 is the fallback plan.

> Do not quote any number that is not in `results/`. Runs P1, P2 and P3 are exported. The fault runs F2–F7 and the capacity runs P4–P6 were still running when this was written; check `results/` and the `/ops/runs` page before claiming them.

---

# PART 1: THE ONE-PAGER

## The problem (15 seconds)
When a customer cancels a Zomato order, the kitchen finds out only if someone happens to notice. Late notice means food cooked for nothing. A louder ring has no end condition and leaves no evidence.

## What NoCook does
It turns a cancellation into a **time-bounded human acknowledgement**, with escalation and a server-side audit trail.

| Time after cancel | What happens |
|---|---|
| +0.02 s | A case opens and a card appears on the kitchen tablet with a beep |
| +30 s | No ACK: a full-screen takeover ("Manager alerted") |
| +75 s | Still no ACK: a mock IVR phone call rings. Pressing 1, 2 or 3 acknowledges it |
| ACK at any point | The case closes on **every** tablet, and the ACK is recorded |
| +10 min | No response: the case expires. It never escalates after that |

## What is proven (say it with the label)

| Claim | Label | Evidence |
|---|---|---|
| At 10× Zomato's NYE peak, one laptop: case-open **p99 0.44 s**, N = 4,682, **100 % inside the 2 s SLO**, ACK API p99 63 ms | MEASURED | run `run-20261119-0604-P3` |
| NYE record load (P2) and dinner peak (P1) pass. N = 922 and 153, so p99 is hidden by the N ≥ 1,000 rule; share inside SLO 100 % | MEASURED | P2, P1 runs |
| Takeover at 30.1 s and call at 75.3 s on the real clock, with the full path closing via IVR | TESTED | `make smoke` |
| Race and invariant tests R1–R13 all pass (duplicate storm, two workers, ACK vs IVR, lease takeover, OMS down, crash after commit and others) | TESTED | 132 backend tests, 0 failures |
| Time to kitchen stop p95: **43.5 s** with NoCook vs **241 s** today. About **₹3,521 prep cost avoided per 1,000 cancellations** (range ₹1,846–₹6,887 for a 5–20 % late-notice rate) | SIMULATED (10,000 paired scenarios, assumptions v1, seed 2026) | `results/sim-20261119-0321` |

Add the fault-run and knee lines only if `/ops/faults` and `/ops/runs` show them:
- F2 replica kill, F3 consumer pause, F4 OMS down, F5 slow DB, F6 duplicate storm, F7 ready-before-cancel race.
- P6 limit in cases/s and its named bottleneck.

## Why you can trust it (the design in four sentences)
1. **Safety is a database fact, not a code path.** One case per order version, one IVR call per case ever, one outbox row per action. These are unique constraints.
2. **Every escalation re-reads the order from the OMS first.** If the OMS is down it defers. It never escalates on stale state.
3. **NoCook never writes to the OMS.** Its database role has no access to that schema, and a test proves it.
4. **Every side effect is a durable row first.** Cards and calls are outbox rows written in the same transaction, so a crash loses nothing and duplicates nothing.

## Honest limits (volunteer these)
- It records what the merchant says. It does not stop the cooking.
- The ₹ numbers are **simulated**, and the late-notice rate is the key unknown. That is why there is a sensitivity range.
- The IVR is a mock provider with signed webhooks. A real provider (Exotel or Plivo) is one adapter.
- RabbitMQ stands in for Zomato's Kafka. Ingestion is one adapter (`CancellationIngestor`).
- It all runs on one laptop and the replicas share its CPU.
- Identity is header-level only. Real auth would be outlet-scoped tokens.

## Production mapping (one line each)
OMS events (Kafka) → consumer-group adapter · read-only OMS API → same endpoint · Mosquitto → Pulse or the existing merchant notification layer · mock IVR → Exotel/Plivo behind `TelephonyGateway` · otel-lgtm → the same OTLP to New Relic.

## Likely judge questions

| Question | Answer |
|---|---|
| Why RabbitMQ, not Kafka? | We know it, so the hours went into NoCook. Duplicates collapse on `(order_id, order_version)` and every escalation re-reads the OMS, so ordering doesn't matter. In production it's a consumer group on your Kafka. |
| Can it take 14,000 events a second? | Only about 0.6 % route to NoCook's queue. The rest are dropped at the exchange. The Load dashboard shows the achieved firehose rate (we measured about 14,000/s). |
| Is the p99 real? | Server-side histograms from all replicas, aggregated by bucket, open-model load (no coordinated omission). N is shown, p99 is hidden below N = 1,000, and the SLO share is exact because there is a bucket at 2 s. Click a slow point to see the trace in Tempo. |
| What if NoCook is down? | Today's notification path is untouched. Cancellations wait in a quorum queue. On recovery, overdue deadlines fire, and anything past 10 minutes expires instead of escalating. |
| What if the OMS is down? | Fail closed. Escalations defer and never fire on stale state (Reliability panel 106). |
| Does it stop cooking? | No. It records what the merchant states. The ₹ numbers are simulated and labelled. |
| Privacy? | No customer PII: outlet id, case id, last-4 order ref, opaque device id, server timestamps. |

---

# PART 2: RUN BOOK (how to start and drive everything)

## 0. One-time facts
- Repo folder: `C:\Users\vansh\Desktop\Eternal`. Run commands from there.
- Docker Desktop must be running. It needs about 6–7 GB of RAM; the stack idles at about 2.5 GB.
- Open **PowerShell** in the repo folder for everything below. `make` is installed but your PATH may not know it yet. If `make` is not found, run this once in that window:

```powershell
$env:Path += ";$env:LOCALAPPDATA\Microsoft\WinGet\Packages\ezwinports.make_Microsoft.Winget.Source_8wekyb3d8bbwe\bin"
```

- Node 24, JDK 25 and the Docker images are already installed or pulled.

## 1. Start the stack

```powershell
make up
```

First build takes several minutes (Maven runs inside Docker). Afterwards it is quick. If Docker was restarted and containers are just stopped, this is enough instead:

```powershell
docker compose -f infra/docker-compose.yml --profile load up -d
```

Check everything is healthy (all `healthy`, 3 nocook replicas):

```powershell
make ps
```

## 2. The URLs (open these before the eval)

| What | URL |
|---|---|
| Tablet 1 (left screen) | http://localhost:8080/tablet?outlet=outlet-demo&device=tab-1 |
| Tablet 2 (shows multi-device close) | http://localhost:8080/tablet?outlet=outlet-demo&device=tab-2 |
| Simulator (hand the mouse to a judge) | http://localhost:8080/simulator |
| Ops Live (KPIs, case list, chaos buttons) | http://localhost:8080/ops |
| Ops Runs (all exported runs) | http://localhost:8080/ops/runs |
| Ops Faults | http://localhost:8080/ops/faults |
| Ops Impact (SIMULATED) | http://localhost:8080/ops/impact |
| **Grafana Live SLOs (kiosk, right screen)** | http://localhost:3000/d/nocook-live-slos?kiosk&refresh=5s |
| Grafana Reliability | http://localhost:3000/d/nocook-reliability?refresh=5s |
| Grafana Load & Capacity | http://localhost:3000/d/nocook-load?refresh=5s |
| RabbitMQ UI | http://localhost:15672 (nocook / nocook) |
| Prometheus | http://localhost:9090 |

Grafana login is `admin` / `admin`. Anonymous viewing also works.

**Click once on each tablet tab** to unlock audio (a banner asks you to tap), or the beep won't play.

## 3. Five minutes before you present

```powershell
make demo-reset
make run P=P3 NOTE="demo load"
```

- `make demo-reset` clears cases, queues and chaos state. It keeps your dashboards and `results/`.
- `make run P=P3` is the **live load**: 14,000 broker events/s plus 8.4 cancellations/s. It runs about 10 minutes of load (about 14 including drain and export), so start it so your demo sits inside that window. Leave that terminal alone.
- Wait about 5 minutes for the dashboard windows to fill. The p99 line appears only once the 5-minute window holds at least 1,000 cases. This takes about 2 minutes at P3's rate.
- That run writes a new folder under `results/`. If you don't want it in your results, delete that folder afterwards, or leave it as an extra measured run. It can't overwrite earlier runs, because the id includes the time.
- Do **not** start a demo load while the proof-run sequence is still going. They share the stack and would contaminate each other's numbers.

## 4. The 3-minute demo, step by step

| Time | You do | You say |
|---|---|---|
| 0:00 | Show tablet (left) and Live SLOs kiosk (right), P3 running | "14,000 order events a second hit the broker, 10× Zomato's record New Year's Eve peak, and about 8 cancellations a second flow through NoCook. These are live p50, p95 and p99 against the 2 s SLO." |
| 0:20 | On `/simulator`, let a judge click **New order**, **Accept**, **Start prep**, then **Customer cancels** | Card appears on both tablets with a beep. "Delivered is not acknowledged. The grey line is a device receipt, not a human." |
| 0:30 | Don't touch the tablet. Point at the countdown | At +30 s the takeover fills the screen: "Manager alerted." |
| 1:00 | At about +75 s the phone screen rings. Judge presses **Answer**, then **1** | Card closes on **both** tablets. "One call, ever, per case, enforced by a unique constraint." Open the case timeline from `/ops` if time allows. |
| 1:45 | Go to `/ops`. Click **Kill nocook-2**, then **Duplicate storm ×20** | Switch to Grafana Reliability: red annotations, lease takeovers, duplicates received about 1,000 but cases created twice = 0, max IVR per case = 1. Back on Live SLOs, the p99 dips and recovers. |
| 2:15 | Open `/ops/impact` | "Separately, a 10,000-scenario simulation, clearly labelled SIMULATED, puts time to kitchen stop p95 at 43.5 s against 241 s today." |
| 2:45 | Open `/ops/runs` | Read the headline sentence from the latest P3 row. |

**Reset between rehearsals:** click **Reset all** on the Ops chaos panel, and if a replica was killed, bring it back:

```powershell
make up-replica R=2
```

## 5. Useful commands during the eval

```powershell
make ps                       # container health
make logs S=nocook-1          # follow one service's logs
make smoke                    # full end-to-end check, about 76 s, prints PASS with timings
make demo-reset               # clean slate (keeps results and dashboards)
node tools/readme-table.mjs   # regenerate the results table from results/
make export RUN=<run-id>      # re-export a finished run
make down                     # stop everything, keep data
```

Quick health probes:

```powershell
curl.exe -s http://localhost:8080/api/ops/summary
curl.exe -s http://localhost:8080/api/ops/chaos/state
```

## 6. Showing the proof (results, tests, simulation)

- **All runs and their verdicts:** http://localhost:8080/ops/runs. Click a row for invariants and the Grafana window links.
- **Raw evidence on disk:** `results/run-*/summary.json`, `invariants.json`, `manifest.json`, `cases.csv`; `results/sim-*/sim-summary.json`; `results/tests-*.json`.
- **Re-run the tests live (optional, about 2 minutes):**

```powershell
cd backend
.\mvnw.cmd -q verify
```

  Use JDK 25 for this. `make test-domain` is the fast version (domain tests only, seconds). Do not run the full backend tests while a load run is active.
- **Re-run the simulation (seconds):** `make sim`. It is deterministic, so the same seed gives identical output.

## 7. If something goes wrong

| Symptom | Fix |
|---|---|
| Grafana panels blank | Wait 20–30 s for data. If still blank, open http://localhost:3000/d/nocook-live-slos directly, and check a load run is active. After editing dashboard JSON, `docker compose -f infra/docker-compose.yml restart lgtm`. |
| No beep | Click once on the tablet tab to unlock audio. |
| Tablet shows MQTT offline | Reload the page. Check `make ps` shows mosquitto up. |
| `make` not found | Run the PATH line in section 0. |
| Everything stopped after a Docker restart | `docker compose -f infra/docker-compose.yml --profile load up -d` |
| p99 line missing | Expected at low rates. p99 is hidden below N = 1,000 by design. Say so. |
| Live demo breaks | Switch to the Ops Runs tab (pre-recorded results) and your screen recording. |

## 8. Pre-eval checklist
- [ ] `make up` is healthy and `make smoke` says PASS
- [ ] The proof-run sequence has finished, so `/ops/runs` shows P1–P6 and the fault runs
- [ ] `node tools/readme-table.mjs` is regenerated and the README headline is filled from `results/`
- [ ] Both tablet tabs and both Grafana tabs are open, audio unlocked
- [ ] Rehearsed twice in under 3 minutes, takeover and call landing within ±3 s
- [ ] Screen recording of a full successful run saved outside git
- [ ] Wi-Fi off once, to prove nothing needs the internet

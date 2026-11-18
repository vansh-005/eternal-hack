# NoCook

**Cancellation acknowledgement assurance for restaurant kitchens.** When a Zomato order is cancelled, NoCook makes sure a human in the kitchen acknowledges it within a bounded time: a pinned card on every tablet, a full-screen takeover at 30 s, one IVR call at 75 s, expiry and review at 10 minutes. Every step is a server-side fact. NoCook never changes the order.

Eternal Hackathon Showcase 2026.

## Headline

> _Filled from `results/` by `node tools/readme-table.mjs` (OUTPUT.md §6):_
> At 10× Zomato's record New Year's Eve peak, on one laptop, p99 case-open was **X s** (N = …), with **…%** inside the 2 s SLO. The system's limit was **Y cases/s**, set by **Z**. Across N fault runs: 0 lost cases, 0 duplicate calls.

## Run it

Requirements: Docker Desktop (16 GB RAM, 8 CPUs allotted ideally), Node 24, JDK 25 (only for running tests outside Docker), `make`, `jq`.

```bash
make prepull      # once, with internet
make up           # builds and starts everything (~5 min first time)
make smoke        # end-to-end check (~2 min, real 30 s / 75 s timers)
```

| What | URL |
|---|---|
| Simulator (drive an order) | http://localhost:8080/simulator |
| Restaurant tablet | http://localhost:8080/tablet?outlet=outlet-demo&device=tab-1 |
| Ops (live, runs, faults, impact) | http://localhost:8080/ops |
| Grafana (admin/admin; anonymous view on) | http://localhost:3000 |
| RabbitMQ management (nocook/nocook) | http://localhost:15672 |

Load and proof:

```bash
make run P=P3     # 10× NYE: 14,000 events/s into the broker, 8.4 cancellations/s through NoCook
make run P=P6     # knee ramp
make sim          # 10,000 paired scenarios (SIMULATED)
```

## What's inside

| Component | Tech |
|---|---|
| NoCook service ×3 | Java 25, Spring Boot 4.1, virtual threads, JdbcClient |
| Mock OMS | Spring Boot 4.1, transactional outbox → RabbitMQ |
| Event bus | RabbitMQ 4.3, topic exchange + quorum queue |
| System of record | PostgreSQL 18 (deadlines with `FOR UPDATE SKIP LOCKED` leases) |
| Tablet push | MQTT 5 over WebSockets (Mosquitto 2) |
| Observability | OpenTelemetry → grafana/otel-lgtm (Prometheus, Tempo, Loki, Grafana) |
| Load / faults | k6 (open model), Toxiproxy, replica kill, consumer pause |
| UI | React 19, Vite, TypeScript, Tailwind |

Architecture: `docs/ARCHITECTURE.md`. State machine and invariants: `docs/DOMAIN.md`. Why each choice: `docs/DECISIONS.md`.

## Results

_Generated table (do not edit by hand):_

<!-- results-table:start -->
| Run | Label | Load | case-open p50 / p95 / p99 | N | ≤ 2 s | ACK API p99 | Invariants | Verdict |
|---|---|---|---|---|---|---|---|---|
<!-- results-table:end -->

Proof labels: **MEASURED** = real timings from the running stack · **TESTED** = invariant pass/fail under injected faults · **SIMULATED** = virtual-time model with stated assumptions. They are never mixed without a badge.

## Repo map

```
backend/   nocook-domain · nocook-service · mock-oms · nocook-sim
frontend/  React app + nginx
infra/     docker-compose, broker/proxy configs, Grafana dashboards
load/      k6 profiles
tools/     run, export, smoke, swarm, reset scripts
results/   exported runs (MEASURED / TESTED / SIMULATED)
docs/      the spec
```

Building with Claude Code: start at `CLAUDE.md`, then `TASKS.md`.

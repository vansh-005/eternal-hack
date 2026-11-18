# DASHBOARDS.md — what judges look at (owner Q, Ops page owner F)

Three provisioned Grafana dashboards + the React Ops pages. Dashboards are JSON files in `infra/grafana/dashboards/`, mounted read-only into `grafana/otel-lgtm` at `/otel-lgtm/grafana/conf/provisioning/dashboards/custom`.

**Before writing any panel, run `make metric-names` and use the names, datasource UIDs and `le` label formats from `docs/METRIC_NAMES.lock.md`.** The PromQL below uses the *expected* names from OBSERVABILITY.md §2; substitute if the lock differs.

## 0. Conventions for every dashboard

- `refresh: "5s"`, default range `now-15m`, timezone browser, `graphTooltip: 1` (shared crosshair).
- Fixed `uid` and fixed panel `id`s (the Ops page iframes depend on them).
- Annotations: built-in Grafana annotations filtered by tags `chaos` (red) and `run` (blue).
- Every latency timeseries: three lines **p50 (thin), p95 (medium), p99 (thick)** + the SLO as a **dashed red threshold line** (`fieldConfig.defaults.custom.thresholdsStyle.mode = "dashed"`, threshold at the SLO value).
- Every latency row has: timeseries (w=12), stat "Share ≤ SLO" (w=6, thresholds red < 0.99 ≤ green), stat "N in window" (w=6).
- Panel descriptions (the ⓘ) state: what starts/stops the clock, and the proof label (`MEASURED` / `TESTED`).
- Hide p99 when N < 1,000: wrap the p99 query with `and on() (<N query> >= 1000)`.

Reusable PromQL (replace `M` with the metric base, e.g. `nocook_case_open_seconds`):

```promql
# pXX across all replicas
histogram_quantile(0.99, sum by (le) (rate(M_bucket[1m])))
# exact share under the SLO (needs a bucket at exactly the SLO)
sum(rate(M_bucket{le="2"}[5m])) / sum(rate(M_count[5m]))
# N in the stat window
sum(increase(M_count[5m]))
# p99 hidden below N=1000
histogram_quantile(0.99, sum by (le) (rate(M_bucket[1m]))) and on() (sum(increase(M_count[5m])) >= 1000)
```

---

## 1. "NoCook — Live SLOs" · `uid: nocook-live-slos` · MEASURED

| id | Title | Type | Query (expected names) | SLO line |
|---|---|---|---|---|
| 1 | Case open — cancel commit → case committed | timeseries | p50/p95/p99 of `nocook_case_open_seconds` | 2 s |
| 2 | Case open — share ≤ 2 s (5 m) | stat | share, `le="2"` | — |
| 3 | Case open — N (5 m) | stat | N | — |
| 4 | Card delivery — case committed → tablet receipt | timeseries | p50/p95/p99 of `nocook_card_delivery_seconds` | 2 s |
| 5 | Card delivery — share ≤ 2 s | stat | | |
| 6 | Card delivery — N | stat | | |
| 7 | ACK API — request → response | timeseries | `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/cases/{caseId}/ack"}[1m])))` (+p50/p95) | 300 ms |
| 8 | ACK API — share < 300 ms | stat | `le="0.3"` | |
| 9 | ACK API — N | stat | | |
| 10 | Timer lateness — due → escalation committed (by kind) | timeseries | `histogram_quantile(0.99, sum by (le, kind) (rate(nocook_deadline_lateness_seconds_bucket[1m])))` | 3 s |
| 11 | Timer lateness — share ≤ 3 s | stat | `le="3"` | |
| 12 | Timer lateness — N | stat | | |
| 13 | ACK fan-out — ACK committed → device dismissed | timeseries | `nocook_ack_fanout_seconds` | 2 s |
| 14 | ACK fan-out — share ≤ 2 s | stat | | |
| 15 | ACK fan-out — N | stat | | |
| 16 | Throughput | timeseries | `sum(rate(nocook_cases_opened_total[30s]))` "cases opened/s"; `sum(rate(nocook_acks_total[30s]))` "ACKs/s"; `sum(rate(nocook_events_consumed_total[30s]))` "events consumed/s"; `sum(rate(k6_iterations_total[30s]))` "k6 iterations/s" | — |
| 17 | Broker → NoCook queue depth | timeseries | `max(nocook_queue_ready{queue="nocook.cancellations"})`, `max(nocook_queue_consumers{queue="nocook.cancellations"})` (right axis) | — |
| 18 | Backlogs | timeseries | `max(nocook_outbox_pending)`, `max(nocook_deadlines_overdue)` | 0 |
| 19 | Open cases | stat | `max(nocook_cases_open)` | — |
| 20 | Background broker load | stat + sparkline | `max(oms_firehose_achieved)` "events/s into the broker" | — |

Layout: row "SLOs" (1–15, five rows of three), row "Flow" (16–20). Ops page embeds panels **1, 4, 7, 10**.

## 2. "NoCook — Reliability" · `uid: nocook-reliability` · TESTED

| id | Title | Type | Query | Pass rule shown in description |
|---|---|---|---|---|
| 101 | Duplicate deliveries received | stat | `sum(increase(nocook_events_consumed_total{outcome="duplicate"}[$__range]))` | any number is fine |
| 102 | Cases created twice | stat (green at 0, red ≥1) | `max(nocook_invariant_duplicate_cases)` | must be 0 |
| 103 | Max IVR attempts per case | stat (green ≤1) | `max(nocook_invariant_max_ivr_per_case)` | must be ≤ 1 |
| 104 | Duplicate IVR intents prevented | stat | `sum(increase(nocook_ivr_duplicate_prevented_total[$__range]))` | — |
| 105 | Escalations after close | stat (green at 0) | `max(nocook_invariant_escalations_after_close)` | must be 0 |
| 106 | Escalations by outcome | timeseries (stacked) | `sum by (kind, outcome) (rate(nocook_escalations_total[1m]))` | `deferred` rises only while OMS is down |
| 107 | Lease takeovers | timeseries | `sum by (table) (increase(nocook_lease_takeovers_total[1m]))` | spikes only after a replica kill |
| 108 | Late ACKs rejected | timeseries | `sum by (code) (increase(nocook_acks_rejected_total[1m]))` | — |
| 109 | DLQ depth | stat | `max(nocook_queue_ready{queue="nocook.cancellations.dlq"})` | 0 in normal runs |
| 110 | OMS read p99 + failures | timeseries | p99 of `nocook_oms_read_seconds`; `sum by (outcome) (rate(nocook_oms_read_seconds_count{outcome!="ok"}[1m]))` | — |
| 111 | Broker → consumer lag p99 | timeseries | p99 of `nocook_consume_lag_seconds` | — |
| 112 | Consumers running per replica | state-timeline | `max by (replica) (nocook_listener_running)` | gap = replica dead / paused |
| 113 | Calls by status | timeseries | `sum by (status) (increase(nocook_calls_total[1m]))` | — |
| 114 | Webhooks | timeseries | `sum by (kind, result) (increase(nocook_webhooks_total[1m]))` | `bad_signature` only in the signature test |

Add three gauges to `GaugeSampler` (every 15 s) for 102/103/105 using the SQL in DATA_MODEL.md §4: `nocook.invariant.duplicate_cases`, `nocook.invariant.max_ivr_per_case`, `nocook.invariant.escalations_after_close`.

## 3. "NoCook — Load & Capacity" · `uid: nocook-load` · MEASURED

| id | Title | Type | Query |
|---|---|---|---|
| 201 | Arrival rate: k6 achieved vs cases opened | timeseries | `sum(rate(k6_iterations_total[30s]))`, `sum(rate(nocook_cases_opened_total[30s]))` |
| 202 | k6 dropped iterations (run void if > 0) | stat (red > 0) | `sum(increase(k6_dropped_iterations_total[$__range]))` |
| 203 | k6 HTTP p95/p99 | timeseries | from `k6_http_req_duration_*` per lock file |
| 204 | CPU per replica | timeseries | `max by (replica) (process_cpu_usage)`, `max(system_cpu_usage)` |
| 205 | Heap used per replica | timeseries | `sum by (replica) (jvm_memory_used_bytes{area="heap"})` |
| 206 | Hikari active / pending | timeseries | `max by (replica) (hikaricp_connections_active)`, `max by (replica) (hikaricp_connections_pending)` |
| 207 | Knee view: cases/s vs case-open p99 | timeseries, two axes | left `sum(rate(nocook_cases_opened_total[30s]))`, right p99 of `nocook_case_open_seconds` |
| 208 | Queue depth and drain | timeseries | `max(nocook_queue_ready{queue="nocook.cancellations"})` |
| 209 | Outbox dispatch p99 / relay publish p99 | timeseries | p99 of `nocook_outbox_dispatch_seconds`, `oms_relay_publish_seconds` |
| 210 | Broker load from firehose | timeseries | `max(oms_firehose_achieved)` |

## 4. Ops page panels (React, owner F) — see FRONTEND.md §5

| Page | Data | Label |
|---|---|---|
| `/ops` KPI strip | `GET /api/ops/summary` | live counts (no badge) |
| `/ops` embedded panels | Grafana panels 1, 4, 7, 10 | MEASURED |
| `/ops/runs` | `results/*/summary.json` | MEASURED · N · runId |
| `/ops/faults` | `results/*/invariants.json` + notes | TESTED · runId |
| `/ops/impact` | `results/sim-*/sim-summary.json` | SIMULATED · N=10,000 · seed |

## 5. Authoring the JSON quickly

1. Build dashboard 1 in the Grafana UI against live data (P1 running), using the lock file names.
2. Dashboard settings → JSON model → copy into `infra/grafana/dashboards/live-slos.json`; set `"id": null`, keep `uid`, set panel ids as above, replace datasource objects with `{ "type": "prometheus", "uid": "<uid from lock>" }`.
3. `make down && make up`; confirm it loads from provisioning (it is read-only in the UI).
4. Repeat for the other two.

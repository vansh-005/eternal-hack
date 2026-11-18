# OUTPUT.md — what we hand to judges, and the exact export formats

The build is done when everything in §5 exists. Numbers come from runs, never typed by hand.

## 1. `results/` layout (committed to git)

```
results/
├── run-20261121-1930-P2/
│   ├── manifest.json        inputs: profile, rates, commit, host, images, replicas, fault schedule
│   ├── summary.json         outputs: percentiles, N, SLO shares, throughput, verdict, headline
│   ├── invariants.json      DB checks (DATA_MODEL.md §4) with values and pass/fail
│   ├── timeseries.json      5 s series for the run window (for charts in /ops/runs)
│   ├── k6-summary.json      written by k6 handleSummary
│   ├── cases.csv            one row per case of this run (from nocook.cases + derived timings)
│   ├── notes.md             human: bottleneck, anomalies (optional, copied into summary.bottleneck)
│   └── screenshots/         live-slos.png, reliability.png, load.png (optional, `make screenshots`)
├── run-…-P3/ … run-…-P6/ , run-…-P3-F2/ …
├── sim-20261121-2010/       SIMULATION.md §4
└── tests-20261121-2100.json R-test summaries (TESTING.md §6)
```

## 2. `manifest.json`

```json
{
  "runId": "run-20261121-1930-P3", "profile": "P3", "kind": "MEASURED",
  "startedAt": "…", "k6EndedAt": "…", "endedAt": "…",
  "window": { "from": "…", "to": "…" },
  "rates": { "cancelsPerSecond": 8.4, "firehoseEventsPerSecond": 14000, "notionalOrdersPerSecond": 1400 },
  "replicas": 3, "tracingSample": 1.0,
  "fault": { "id": "F2", "action": "kill-replica", "params": { "replica": "nocook-2" }, "at": "…", "until": null },
  "commit": "abc1234", "dirty": false,
  "images": { "postgres": "postgres:18", "rabbitmq": "rabbitmq:4.3-management", "lgtm": "grafana/otel-lgtm:<tag>", "k6": "grafana/k6:<tag>" },
  "host": { "cpuModel": "…", "cores": 8, "ramGb": 16, "os": "…", "docker": "…" },
  "note": "free text from NOTE="
}
```

## 3. `summary.json` (written by `tools/export-run.mjs`)

Every latency block is computed over the run window with PromQL `@ <end>` and `[<duration>s]`:

```promql
histogram_quantile(0.99, sum by (le) (increase(nocook_case_open_seconds_bucket[600s] @ 1764000000)))
sum(increase(nocook_case_open_seconds_bucket{le="2"}[600s] @ 1764000000)) / sum(increase(nocook_case_open_seconds_count[600s] @ 1764000000))
sum(increase(nocook_case_open_seconds_count[600s] @ 1764000000))
```

```jsonc
{
  "runId": "run-20261121-1930-P3", "profile": "P3", "kind": "MEASURED",
  "verdict": "PASS",                       // PASS | FAIL | VOID (k6 dropped iterations > 0)
  "verdictReasons": [],
  "latency": {
    "caseOpen":      { "n": 5040, "p50": 0.041, "p95": 0.12, "p99": 0.31, "slo": 2.0, "shareWithinSlo": 0.9996 },
    "cardDelivery":  { "n": 5000, "p50": 0, "p95": 0, "p99": 0, "slo": 2.0, "shareWithinSlo": 0, "byClient": { "tablet": {…}, "swarm": {…} } },
    "cardRender":    { … },
    "ackApi":        { "n": 0, "p50": 0, "p95": 0, "p99": 0, "slo": 0.3, "shareWithinSlo": 0 },
    "timerLateness": { "L1": {…}, "IVR": {…}, "EXPIRY": {…} },
    "ackFanout":     { … },
    "consumeLag":    { … }, "omsRead": { … }
  },
  "throughput": { "casesOpenedPerSecond": { "mean": 8.4, "max30s": 9.1 }, "acksPerSecond": {…},
                  "firehoseAchieved": 13980, "k6": { "iterations": 5040, "dropped": 0, "httpP99": 0 } },
  "backlog": { "queuePeak": 12, "drainSeconds": 3, "outboxPendingPeak": 4, "deadlinesOverduePeak": 0 },
  "resources": { "cpuPeakByReplica": {…}, "heapPeakMbByReplica": {…}, "hikariPendingPeak": 0 },
  "knee": null,                              // P6 only: { "casesPerSecond": 0, "p99AtKnee": 0, "windowStart": "…" }
  "bottleneck": null,                        // from notes.md
  "invariantsPass": true,
  "headline": "At 10× Zomato's record NYE peak on one laptop, p99 case-open was 0.31 s (N=5,040), 99.96% within 2 s.",
  "grafana": { "liveSlos": "http://localhost:3000/d/nocook-live-slos?from=…&to=…", "reliability": "…", "load": "…" }
}
```

(Numbers are shape placeholders.) Verdict rules: `VOID` if k6 dropped > 0. `FAIL` if any invariant fails, or for P1–P3 if `caseOpen.shareWithinSlo < 0.99` or `ackApi.p99 ≥ 0.3`. P4–P6 are capacity runs: PASS if invariants pass; their SLO numbers are reported, not judged.

## 4. `invariants.json`

```json
{ "runId": "…", "checkedAt": "…",
  "checks": [
    { "name": "duplicate_cases",              "value": 0, "rule": "== 0", "pass": true },
    { "name": "max_ivr_per_case",             "value": 1, "rule": "<= 1", "pass": true },
    { "name": "escalations_after_close",      "value": 0, "rule": "== 0", "pass": true },
    { "name": "escalations_after_max_age",    "value": 0, "rule": "== 0", "pass": true },
    { "name": "escalations_on_not_actionable","value": 0, "rule": "== 0", "pass": true },
    { "name": "lost_cases",                   "value": 0, "rule": "== 0", "pass": true },
    { "name": "outbox_stuck",                 "value": 0, "rule": "== 0", "pass": true },
    { "name": "fault_window_escalations",     "value": 0, "rule": "== 0 (F4 only)", "pass": true }
  ],
  "counts": { "cases": 5040, "byState": { "ACKNOWLEDGED": 0, "EXPIRED": 0, "NOT_ACTIONABLE": 0, "SUPERSEDED": 0, "open": 0 },
              "duplicatesReceived": 0, "ivrReserved": 0, "ivrDuplicatePrevented": 0, "leaseTakeovers": 0, "lateAcks": 0 } }
```

`cases.csv` columns: `case_id,order_id,outlet_id,state,close_reason,ack_channel,ack_choice,cancelled_at,opened_at,first_delivered_at,first_rendered_at,l1_at,ivr_at,acked_at,closed_at,case_open_ms,delivery_ms,ack_ms`.

## 5. Final deliverables checklist (G3 + G4)

| # | Deliverable | Evidence of done |
|---|---|---|
| D1 | Running stack on one laptop | `make up` from clean in < 6 min; `make smoke` PASS |
| D2 | **Live SLOs dashboard** with p50/p95/p99, SLO lines, exact shares, N | screenshot during P3 in `results/<P3>/screenshots/live-slos.png` |
| D3 | **Reliability dashboard** with invariant tiles at 0 / ≤1 and chaos annotations | screenshot during a fault run |
| D4 | **Load & Capacity dashboard** with the P6 knee | screenshot of P6 |
| D5 | Runs P2, P3, P4 (1 and 3 replicas), P5, P6 exported | folders in `results/` with `verdict` ≠ VOID |
| D6 | Fault runs F2–F7 exported, F8–F9 test reports | `results/*-F*/invariants.json` all pass; `results/tests-*.json` |
| D7 | 10,000-scenario simulation | `results/sim-*/sim-summary.json`; Impact tab renders it |
| D8 | Ops pages: Live, Case timeline, Runs, Faults, Impact | all read from real endpoints / files |
| D9 | README with the headline sentence and a results table generated from `results/` (`node tools/readme-table.mjs`) | table matches `summary.json` values |
| D10 | Demo rehearsed twice < 3 min; full screen recording as backup | `demo-recording.mp4` stored outside git (link in README) |
| D11 | Docs current: any spec change logged in DECISIONS.md | review |

## 6. The headline (fill from P3 + P6 summaries only)

> "At 10× Zomato's record New Year's Eve peak, on one laptop, p99 case-open was **{P3.caseOpen.p99} s** (N = {P3.caseOpen.n}), with **{P3.caseOpen.shareWithinSlo}** of cases inside the 2 s SLO. The system's limit was **{P6.knee.casesPerSecond} cases/s**, set by **{P6.bottleneck}**. Across {count of fault runs} fault runs: 0 lost cases, 0 duplicate calls."

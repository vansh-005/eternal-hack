# SIMULATION.md — 10,000 paired scenarios in virtual time (owner Q) · SIMULATED

Purpose: estimate *what NoCook changes* (time to kitchen stop, ₹ prep avoided, call load) under explicit assumptions. Never presented as measured or as Zomato data.

## 1. Rules

- Module `backend/nocook-sim`, depends only on `nocook-domain`. The NoCook arm drives the **real** `CasePolicy.decideOpen` / `transition` with virtual `Instant`s, so the state paths are the production ones.
- Seeded (`SplittableRandom(seed)`), deterministic: same seed + assumptions + commit ⇒ byte-identical output. A unit test asserts this.
- **Paired:** each scenario is generated once, then run under `BASELINE` and `NOCOOK`. Both arms consume the same pre-drawn random numbers (draw all of a scenario's randomness up front into a `ScenarioDraws` record).
- "ACK" (merchant says something) and "STOP" (kitchen physically stops) are different events. Baseline has no ACK; compare **time to stop**.

## 2. Scenario draw (all distributions in `sim/assumptions-v1.json`)

| Field | Distribution (v1) | Note |
|---|---|---|
| `stageAtCancel` | NOT_STARTED 35 %, PREP 35 %, OVEN 20 %, READY 10 % | READY → NOT_ACTIONABLE (no saving possible either arm) |
| `initiator` | CUSTOMER 95 %, RESTAURANT 5 % | RESTAURANT → NOT_ACTIONABLE |
| `orderValuePaise` | uniform ₹150–₹900 | only for context |
| Kitchen timeline from cancel | time to next stage: NOT_STARTED→PREP U(30, 180) s, PREP→OVEN U(120, 300) s, OVEN→READY U(300, 600) s | |
| Stage cost (cumulative sunk ₹) | NOT_STARTED 0, PREP 45, OVEN 110, READY 150 | blueprint's pizza example; illustrative |
| `baselineLate` | Bernoulli(lateNoticeRate = 0.10) | **the key unknown**; sensitivity at 0.05 / 0.10 / 0.20 |
| Baseline discovery delay | not late: lognormal(median 20 s, σ 0.6); late: lognormal(median 240 s, σ 0.5) | first time anyone in the kitchen notices |
| Card seen & ACKed before L1 | p = 0.70; delay lognormal(median 12 s, σ 0.5), truncated < 30 s | |
| ACK after takeover (L1) | p = 0.60 of remaining; delay after L1 lognormal(median 10 s, σ 0.5), truncated < 45 s | |
| IVR answered | p = 0.80 of remaining; DTMF after call start lognormal(median 15 s, σ 0.4) | |
| IVR outcome if not answered | NO_ANSWER 60 %, BUSY 25 %, FAILED 15 % | |
| Stop delay after ACK | U(0, 10) s | ACK ≠ STOP |
| Device offline at cancel | p = 0.05 → card delivered only on reconnect U(20, 120) s | |

## 3. Arms

**BASELINE:** `stopAt = cancelAt + baselineDiscoveryDelay`.

**NOCOOK:** run the case through `CasePolicy` in virtual time: open at `cancelAt` (+ small open latency 0.2 s), feed `DeadlineDue(L1)` at +30 s, `DeadlineDue(IVR)` at +75 s, `DeadlineDue(EXPIRY)` at +600 s, and `MerchantAck` at the drawn ACK instant (whichever channel responds first), with `OmsView.Available(snapshot)` reflecting the kitchen timeline (READY reached before an escalation → SUPERSEDED). `nocookAckAt` = the ACK instant, if any. Because NoCook adds a channel and never removes the existing one: `stopAt = min(baselineDiscoveryAt, nocookAckAt + stopDelay)`.

**Prep cost spent** = sunk cost of the stage reached at `stopAt` (stage at cancel, advanced by the kitchen timeline). **Avoided ₹** per scenario = `max(0, baselineCost − nocookCost)`. It can't go negative by construction; assert it.

## 4. Outputs (`results/sim-<ts>/`)

- `sim-summary.json`
- `scenarios.csv` (one row per scenario: id, stage, eligible, baselineStopS, nocookStopS, baselineCost, nocookCost, path e.g. `CARD_ACK` / `L1_ACK` / `IVR_ACK` / `EXPIRED`, ivrCalled)
- `manifest.json` (seed, n, assumptions file hash, commit, startedAt, wallMs)

## 5. `sim-summary.json` schema

```json
{
  "kind": "SIMULATED", "runId": "sim-20261121-2010", "seed": 2026, "n": 10000, "assumptionsVersion": "v1",
  "eligible": 8550,
  "timeToStopSeconds": {
    "baseline": { "p50": 21.3, "p95": 288.0, "p99": 402.1, "cdf": [[0,0.0],[10,0.08], "… 0..600 step 5 …"] },
    "nocook":   { "p50": 14.9, "p95": 61.2,  "p99": 98.4,  "cdf": [[0,0.0], "…"] }
  },
  "lateDiscoveryRate": { "thresholdSeconds": 120, "baseline": 0.10, "nocook": 0.012 },
  "rupeesAvoided": { "total": 0, "per1000Cancellations": 0,
                     "sensitivity": [ { "lateNoticeRate": 0.05, "per1000": 0 }, { "lateNoticeRate": 0.10, "per1000": 0 }, { "lateNoticeRate": 0.20, "per1000": 0 } ] },
  "funnel": { "opened": 8550, "ackedOnCard": 0, "ackedAfterL1": 0, "ivrCalled": 0, "ackedByIvr": 0, "expired": 0 },
  "ivrCallsPer1000": 0,
  "statePaths": { "AWAITING_ACK>ACKNOWLEDGED": 0, "AWAITING_ACK>ESCALATED_L1>ACKNOWLEDGED": 0, "…": 0 },
  "assumptions": { "…copied from assumptions-v1.json with an 'assumed': true flag per field…" },
  "commit": "abc1234", "wallMs": 0
}
```

(Numbers above are placeholders for shape only; the real values come from the run.)

Sensitivity: rerun the paired set with `lateNoticeRate` 0.05 and 0.20 (same seed) and record `per1000`. Also report the break-even late-notice rate at which `₹ avoided per 1,000` equals an assumed IVR cost per 1,000 (`ivrCostPerCallPaise` in assumptions, e.g. ₹1.5/call): bisection over lateNoticeRate.

## 6. Tests

- `SimDeterminismTest`: two runs, same seed → identical `sim-summary.json` bytes.
- `PairingTest`: both arms see identical `ScenarioDraws`.
- `NoNegativeSavingsTest`.
- `StatePathTest`: a scenario with no ACK ends `EXPIRED` via real `CasePolicy`; one where READY precedes L1 ends `SUPERSEDED`.

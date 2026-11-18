# DEMO.md — 3-minute live demo, Q&A, rehearsal

## 1. Screen setup (one laptop + one external screen, or two laptops)

| Screen | What | URL |
|---|---|---|
| Left half | Tablet (also open on a judge's phone if they want) | `/tablet?outlet=outlet-demo&device=tab-1` |
| Right half | Grafana "Live SLOs", kiosk mode, last 15 min | `:3000/d/nocook-live-slos?kiosk&refresh=5s` |
| Tab 2 | Ops Live (`/ops`) with chaos panel | |
| Tab 3 | Simulator (`/simulator`) — hand the mouse to a judge | |
| Tab 4 | Grafana "Reliability" | |
| Tab 5 | Ops Impact (`/ops/impact`) | |
| Second tablet tab | `/tablet?outlet=outlet-demo&device=tab-2` (shows multi-device close) | |

**T-5 min:** `make demo-reset`, then `make run P=P3` in a terminal (it runs 10 min; start it so the demo runs inside its window). Click once on each tablet tab to unlock audio. Check RabbitMQ UI shows consumers = 3 × concurrency.

## 2. Script (escalation runs on the real wall clock: 30 s and 75 s)

| Time | On screen | Say / point at |
|---|---|---|
| 0:00–0:20 | Tablet + Live SLOs, P3 running | "14,000 order events a second hit the broker — 10× Zomato's record New Year's Eve peak — and 8 cancellations a second flow through NoCook. These are live p50, p95, p99 lines against the 2-second SLO." |
| 0:20–0:30 | Judge clicks **Customer cancels** in the Simulator | Card appears on both tablets with a beep. Ops timeline: `CASE_OPENED`, `CARD_DELIVERED`, `CARD_RENDERED`. "Delivered is not acknowledged — this grey line is a device receipt, not a human." |
| 0:30–1:00 | Nobody touches the tablet | Point at the countdown and at the exact-share tile. At +30 s the takeover goes full screen: `L1_TRIGGERED`. |
| 1:00–1:45 | Mock call rings at +75 s; judge presses **Answer** then **1** | Card closes on **both** tablets. Timeline: `IVR_RESERVED` (one call max) → `CALL_ANSWERED` → `DTMF_ACKNOWLEDGED STOPPED` → `CASE_CLOSED`. "One call, ever, per case — enforced by a unique constraint, not by hope." |
| 1:45–2:15 | Ops: **Kill nocook-2**, then **Duplicate storm ×20** | Switch to Reliability: red annotation, consumer gap for nocook-2, lease takeovers, "duplicates received 1,000 → cases created twice 0", "max IVR per case 1". Back to Live SLOs: p99 dips and recovers. |
| 2:15–2:45 | Ops Impact (SIMULATED badge) | "Separately, a 10,000-scenario paired simulation — clearly labelled simulated — puts time-to-kitchen-stop p95 at X s vs Y s baseline, and ₹Z avoided per 1,000 cancellations, with sensitivity bands because the late-notice rate is the unknown." |
| 2:45–3:00 | Ops Runs table | Read the headline from OUTPUT.md §6: "At 10× the NYE record on one laptop, p99 case-open was X s, and the limit was Y cases/s, set by Z. In production the ingestion adapter becomes a consumer group on your Kafka and nothing else changes; the same OTLP goes to New Relic." |

Backup if anything breaks live: Ops Runs tab (pre-recorded P2–P6 + faults) and `demo-recording.mp4`.

## 3. Judge Q&A

| Question | Answer |
|---|---|
| Why RabbitMQ, when Zomato runs Kafka? | We know it, so the 10 hours went into NoCook, not broker setup. Ingestion is one adapter (`CancellationIngestor`); in production it's a consumer group on your Kafka. Ordering doesn't matter: duplicates collapse on `(order_id, order_version)` and every escalation re-reads the OMS. The broker also filters: only `order.cancelled` reaches NoCook. |
| Can RabbitMQ take 14k events/s on a laptop? | Only ~0.6 % of them route to NoCook's quorum queue; the rest are routed and dropped at the exchange. The Load dashboard shows achieved firehose rate. Published single-node 4.3 benchmarks sustain ~45k msg/s into a quorum queue with confirms. |
| Why MQTT, not WebSockets? | A broker fans out no matter which of 3 replicas publishes, and Pulse is already MQTT. QoS 1 PUBACK is per hop, so we still collect an app receipt, and even that isn't a human ACK. |
| Is the p99 real? | Server-side histograms from all replicas, aggregated by bucket; open-model load (no coordinated omission); N shown; p99 hidden below N = 1,000; SLO share exact because there's a bucket at exactly 2 s. Click a slow point → the trace in Tempo. |
| Does it scale? | Modelled peak is 0.56 cases/s. We ran 10× NYE and ramped to the knee (P6): Y cases/s on one laptop, limited by Z. Deadlines and outbox use `SKIP LOCKED` leases, so more replicas compete safely. |
| What if NoCook is down? | Today's notification path is untouched. Cancellations wait in a quorum queue; on recovery overdue deadlines fire, and anything past 10 minutes expires instead of escalating. |
| What if OMS is down? | We fail closed: escalations are deferred (Reliability panel 106), never fired on stale state. |
| Does it actually stop cooking? | No. It records what the merchant states. ₹ numbers are simulated and labelled. |
| Why not just ring louder? | A louder ring has no end condition and leaves no evidence. |
| How would it plug into Zomato? | Consumer on order events (or a worker behind your Worker Controller), read-only OMS API, existing merchant notification layer, Exotel/Plivo behind `TelephonyGateway`, OTLP to New Relic. |
| Privacy? | No customer PII stored: outlet id, case id, order ref (last 4), opaque device id, server timestamps. |

## 4. Rehearsal checklist (G4)

- [ ] Clean machine path: `make nuke && make prepull && make up` < 6 min, `make smoke` PASS
- [ ] Demo run-through #1 under 3:00 with P3 running; note timestamp drift of takeover/call (must be within ±3 s)
- [ ] Demo run-through #2 under 3:00, different person driving
- [ ] Wi-Fi off test: everything works
- [ ] Screen recording of a full successful run saved
- [ ] Ops Runs/Faults/Impact pages show final results
- [ ] README headline and table filled from `results/`
- [ ] `git tag v-demo` on the frozen commit; no changes after

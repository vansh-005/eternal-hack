# TESTING.md — what must be tested, and how

Tests are part of the demo: the Faults tab and the TESTED label point at them. A failing test blocks its gate.

## 1. Layers

| Layer | Where | Tool | Speed | Owner |
|---|---|---|---|---|
| Domain unit | `nocook-domain/src/test` | JUnit 5 + AssertJ, parameterized | < 2 s | Q |
| Service integration + races | `nocook-service/src/test/java/com/nocook/service/it/**` (`*IT`) | Spring Boot test + Testcontainers (Postgres 18, RabbitMQ 4.3) | ~1–2 min | Q (races), B (component ITs) |
| API contract | `nocook-service/.../api/*IT` | MockMvc / `RestTestClient` against real DB | fast | B |
| mock-oms | `mock-oms/src/test` | Testcontainers | fast | B |
| Simulation | `nocook-sim/src/test` | JUnit | fast | Q |
| Frontend | `frontend/src/**/*.test.ts(x)` | Vitest | fast | F |
| System smoke | `tools/smoke.mjs` | Node against running stack | ~3 min | Q |
| Load + fault runs | `tools/run.mjs` | k6 + exporter | minutes | Q |

## 2. Harness (Q builds in Q-02, everyone uses it)

- `MutableClock extends Clock` (`advance(Duration)`, `set(Instant)`), exposed as a `@Primary @Bean` in `TestClockConfig`.
- `FakeOms` implements `OmsReadClient`'s port: in-memory snapshots, switchable to `Unavailable`. `@Primary` in ITs. (One IT, `OmsReadClientIT`, tests the real client against mock-oms.)
- Workers expose `tick()`; in ITs the `@Scheduled` methods are disabled (`nocook.scheduling.enabled=false`) and tests call `tick()` explicitly after advancing the clock.
- `@ServiceConnection` containers, shared static across ITs (`@Testcontainers` base class `AbstractIT`):

```java
@Container @ServiceConnection static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");
@Container @ServiceConnection static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.3-management");
```

  The Postgres container runs `infra/postgres/init.sql` (copy to test resources) so roles and schemas match production.
- MQTT in ITs: a `RecordingCardPublisher` replaces `CardPublisher` (captures messages); one IT (`MqttPublishIT`) uses a Mosquitto Testcontainer (`GenericContainer("eclipse-mosquitto:2")` + config) to prove the real client works.
- No `Thread.sleep`. Awaitility (`await().atMost(5, SECONDS)`) only for broker deliveries.

## 3. Domain tests (Q-01) — names are fixed

`CasePolicyOpenTest`
- `eligible_customer_cancel_after_accept_opens_awaiting_ack_with_three_deadlines_and_card`
- `status_not_cancelled_is_stale_event`
- `not_accepted_before_cancel_is_not_actionable`
- `restaurant_initiated_is_not_actionable`
- `ready_before_or_after_cancel_is_not_actionable`
- `picked_up_is_not_actionable`
- `cancel_older_than_max_age_is_too_old` (boundary: 599.999 s eligible, 600 s not)
- `deadlines_are_anchored_to_cancelled_at_not_now`

`CasePolicyTransitionTest` — one `@ParameterizedTest` per table row D1–D10, A1–A5, plus:
- `l1_boundary_fires_at_exactly_30s` (the worker passes `now ≥ dueAt`; policy is called with now = dueAt)
- `expiry_wins_over_oms_unavailable` (D2 before D3)
- `version_advance_alone_does_not_supersede`
- `ivr_from_awaiting_ack_when_l1_was_deferred`
- `ack_not_gated_on_case_version`
- `every_applied_result_has_at_least_one_fact`
- `no_effects_on_terminal_states` (exhaustive: every terminal state × every event)

`TransitionTableCoverageTest`: iterates all `CaseState × {DeadlineDue(L1|IVR|EXPIRY), MerchantAck}` × `{Available, Unavailable, NotRead}` and asserts no exception and a defined outcome (catches holes in the `switch`).

## 4. Race and invariant ITs (Q-02) — the TESTED evidence

| ID | Test class | Scenario | Assertion |
|---|---|---|---|
| R1 | `DuplicateStormIT` | publish the same cancellation 20× concurrently to the real queue (3 listener containers in the context: concurrency 8) | exactly 1 case; 19 `DUPLICATE_EVENT` facts; `nocook_events_consumed{duplicate}` = 19 |
| R2 | `TwoWorkersOneTimerIT` | two `DeadlineWorker` instances (ids A, B) `tick()` concurrently on 200 due deadlines | each deadline processed once; 200 `L1_TRIGGERED` facts total |
| R3 | `AckVsIvrRaceIT` | 1,000 cases at `ivrAt`; thread pool fires ACKs and the IVR worker tick at the same instant | `max(call_attempts per case) ≤ 1`; for every case whose ACK committed first: no call attempt; no case both `ACKNOWLEDGED` and with an `IVR_RESERVED` fact after `acked_at` |
| R4 | `LeaseTakeoverIT` | worker A claims (lease 10 s) and never processes; clock +11 s; reaper; worker B ticks | B processes; `LEASE_TAKEOVER` fact; `nocook_lease_takeovers_total` = 1 |
| R5 | `OmsUnavailableDefersIT` | FakeOms unavailable at L1 | state stays `AWAITING_ACK`; deadline PENDING at now + 2 s; `ESCALATION_DEFERRED` fact; OMS back → next tick escalates |
| R6 | `ReadyBeforeCancelIT` | snapshot has `readyAt` 5 ms before cancel | `NOT_ACTIONABLE`, 0 deadlines, 0 outbox |
| R7 | `SupersededAtEscalationIT` | OMS shows `READY` (and separately `ACCEPTED` after reinstate) when L1 fires | `SUPERSEDED`, `ESCALATION_BLOCKED`, CLOSE card queued, remaining deadlines CANCELLED |
| R8 | `ExpiryIT` | no ACK; advance to +10 min | `EXPIRED`; later IVR/L1 no-op; late ACK → 409 `CASE_CLOSED` + `LATE_ACK_IGNORED` |
| R9 | `OutboxCrashAfterCommitIT` | commit case + outbox, "crash" before dispatch (don't tick), restart dispatcher bean | card published exactly once (same `messageId`); `SENT` |
| R10 | `WebhookSecurityIT` | bad signature; missing signature; same `providerEventId` twice; DTMF `1` | 401, 401, second has no effect, case `ACKNOWLEDGED` via `IVR` |
| R11 | `NocookRoleCannotWriteOmsIT` | as `nocook_app`: `select 1 from oms.orders` and `update oms.orders …` | both fail with permission denied |
| R12 | `CrashAfterCommitBeforeAckIT` | a test-only `@MockitoSpyBean` makes the listener throw **after** the case transaction commits (so the broker never gets the ack), once | message redelivered; still exactly 1 case + 1 `DUPLICATE_EVENT`; nothing lost |
| R13 | `AckIdempotencyIT` | same idempotencyKey twice; different key after ACK | 200 + `duplicate=true`; 409 `ALREADY_ACKNOWLEDGED` |

## 5. System smoke (`make smoke` → `tools/smoke.mjs`, the G1 gate)

Against the running stack, wall clock, demo timings shortened is **not** allowed (real 30 s / 75 s):

1. `POST /oms/api/orders` (outlet `outlet-smoke`) → accept → stage PREP → cancel (CUSTOMER).
2. Poll `GET /api/outlets/outlet-smoke/open-cases` until the case appears (fail if > 5 s). Record case-open ms.
3. Connect MQTT as `tablet-smoke`, assert an `OPEN` card arrives; POST `DELIVERED`, `RENDERED`.
4. Wait for `TAKEOVER` (expect at cancel + 30 s ± 3 s).
5. Wait for `INCOMING_CALL` (cancel + 75 s ± 3 s). `POST /api/mock-telephony/calls/{id}/answer`, then `/dtmf {digit:"1"}`.
6. Expect `CLOSE` with `ackChannel=IVR`, `ackChoice=STOPPED`. POST `DISMISSED`.
7. `GET /api/cases/{id}/timeline` contains, in order: `CASE_OPENED, CARD_DISPATCHED, CARD_DELIVERED, CARD_RENDERED, L1_TRIGGERED, IVR_ESCALATED, IVR_RESERVED, CALL_DIALING, CALL_ANSWERED, DTMF_ACKNOWLEDGED, CASE_CLOSED`.
8. Second order: cancel, ACK from tablet within 5 s → 200; ACK again with same key → 200 duplicate; different key → 409.
9. Prometheus: `nocook_case_open_seconds_count` > 0 and `nocook_card_delivery_seconds_count` > 0 (query `localhost:9090/api/v1/query`).
10. Print PASS with timings.

## 6. Naming and running

- Unit `*Test` (surefire), integration `*IT` (failsafe). `make test` runs both + Vitest.
- Test method names state the behaviour (`snake_case_sentences`).
- Each R-test logs a one-line JSON summary `{"test":"R3","cases":1000,"maxIvrPerCase":1,"result":"PASS"}`; `tools/export-tests.mjs` collects these from `target/failsafe-reports` into `results/tests-<ts>.json` for the Faults tab.

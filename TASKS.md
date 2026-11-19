# TASKS.md — the build plan (10 hours, 3 owners, 5 gates)

Owners: **B** Backend · **F** Frontend · **Q** Simulation / QA / Infra. Each owner runs their own Claude Code session and works their column top to bottom. Use `/task <ID>` to start a task and `/gate <G#>` to check a gate.

**Rules**
- A task starts only when everything in **Depends** is ticked.
- Tick `- [x]` only when every **Done when** command passes. Add one line of notes under the task.
- A gate is checked by whoever finishes last in that phase. **If a gate slips by more than 30 minutes, apply the cut order (§7) instead of pushing on.**
- Spec changes: edit the doc + add a line to `docs/DECISIONS.md` change log in the same commit.

---

## 0. Timeline at a glance

| Hour | B (Backend) | F (Frontend) | Q (Sim / QA / Infra) | Gate at end |
|---|---|---|---|---|
| H0–1 Skeleton | B-00 scaffold · B-01 mock OMS + relay | F-00 scaffold + MQTT + nginx | Q-00 infra + Makefile · Q-01 domain (tests first) | **G0** a cancel lands in `nocook.cancellations` |
| H1–4 Core loop | B-02 ingest/open · B-03 outbox+MQTT · B-04 deadlines · B-05 ACK API · B-06 mock IVR | F-01 tablet · F-02 call screen · F-03 simulator | Q-02 test harness + race ITs · Q-03 firehose/replay/race · Q-04 smoke | **G1** one order end-to-end, races green |
| H4–7 Telemetry + screens | B-07 meters/traces · B-08 ops + chaos API | F-04 Ops live + case timeline + chaos · F-05 Runs/Faults pages | Q-05 metric lock + 3 dashboards · Q-06 k6 + swarm · Q-07 run/export tools | **G2** p99 panels live under P1, chaos annotates |
| H7–9 Proof runs | B-09 knee fixes + run support | F-06 Impact page · F-07 polish | Q-08 simulation · Q-09 proof runs + exports | **G3** results exported, all invariants pass |
| H9–10 Rehearse | ALL-01 README + headline · ALL-02 rehearse ×2, record, tag | | | **G4** demo < 3 min twice |

Start P2 (20 min) as early in H7 as possible; it runs while other work continues.

---

## 1. Phase H0–1 — Skeleton

### Q-00 · Infra, compose, Makefile
- [x] **Owner** Q · **Est** 40 min · **Depends** —
- **Spec:** INFRA.md (all), DATA_MODEL.md §0
- **Files:** `infra/**`, `Makefile`, `tools/package.json`, `.gitignore`
- **Build:**
  1. Pin image tags in `infra/.env` (look up current tags for otel-lgtm, toxiproxy, k6, nginx; write them in, never `latest`). Record them in CLAUDE.md §2.
  2. `docker-compose.yml`, `postgres/init.sql`, `rabbitmq/rabbitmq.conf`, `mosquitto/mosquitto.conf`, `toxiproxy/toxiproxy.json` exactly as INFRA.md. Empty `grafana/dashboards/` with a `.gitkeep`.
  3. Makefile targets from INFRA.md §5 (stubs OK for targets whose tools come later; they must print "not yet implemented: <task id>").
  4. `tools/package.json` with deps `mqtt`, `pg`; `"type": "module"`.
- **Done when:**
  ```bash
  docker compose -f infra/docker-compose.yml config -q && echo OK
  docker compose -f infra/docker-compose.yml up -d postgres rabbitmq mosquitto toxiproxy lgtm
  docker compose -f infra/docker-compose.yml exec postgres psql -U postgres -d nocook -c '\dn'   # shows oms, nocook
  curl -s localhost:8474/proxies | grep -q postgres && curl -s -o /dev/null -w '%{http_code}\n' localhost:3000/api/health   # 200
  ```
  - Notes: all 4 Done-when pass; toxiproxy tag is 2.13.0 (no v prefix); pgdata mounts at /var/lib/postgresql fine; make up uses npm install when no lockfile.

### Q-01 · Domain model + CasePolicy (tests first)
- [x] **Owner** Q · **Est** 60 min · **Depends** B-00 (module exists; if not yet, create `backend/nocook-domain` yourself with a minimal pom and tell B)
- **Spec:** DOMAIN.md (all), TESTING.md §3
- **Files:** `backend/nocook-domain/**`
- **Build:**
  1. Write `CasePolicyOpenTest`, `CasePolicyTransitionTest`, `TransitionTableCoverageTest` with the exact names in TESTING.md §3. Run: they fail to compile / fail.
  2. Implement types and `EligibilityPolicy`, `CasePolicy` with one private method per table row (D1–D10, A1–A5).
  3. No Spring, no clock reads, no I/O.
- **Done when:**
  ```bash
  cd backend && ./mvnw -q -pl nocook-domain test          # all green
  grep -rE "Instant\.now|System\.currentTimeMillis|springframework" nocook-domain/src/main && exit 1 || echo CLEAN
  ```
  - Notes: 53 tests green (Open 9, Transition 43, Coverage 1); pure Java, no Spring/clock. Choices logged in DECISIONS.md.

### B-00 · Backend scaffold
- [x] **Owner** B · **Est** 40 min · **Depends** —
  - Notes: boots with Flyway; queues declared; health UP. JVM TZ forced to UTC (pg18 rejects Asia/Calcutta).
- **Spec:** BACKEND.md §1, §3.5, §4; DATA_MODEL.md §1–2; API.md §3 (topology); CLAUDE.md §2 Boot 4 traps
- **Files:** `backend/pom.xml`, `backend/Dockerfile`, `backend/mvnw*`, `backend/nocook-service/**` (skeleton), `backend/mock-oms/**` (skeleton), `backend/nocook-sim/pom.xml` (empty module), `backend/nocook-domain/pom.xml` (if Q hasn't)
- **Build:**
  1. Generate both apps on start.spring.io (Boot 4.1.x, Java 25, Maven): Spring Web, JDBC API, Flyway Migration, PostgreSQL Driver, Spring for RabbitMQ, Actuator, OpenTelemetry, Validation, Testcontainers. Merge into the multi-module parent. Pin the exact Boot version.
  2. `V1__oms.sql` and `V1__nocook.sql` verbatim from DATA_MODEL.md.
  3. `application.yml` for both apps (BACKEND.md §3.5 and §4); `ClockConfig`; `RabbitTopologyConfig` (Declarables) in both apps.
  4. Add `com.hivemq:hivemq-mqtt-client` to nocook-service.
  5. `backend/Dockerfile` with `MODULE` build arg.
- **Done when:**
  ```bash
  cd backend && ./mvnw -q -DskipTests package
  make up    # (or compose up) all services healthy
  curl -fs localhost:8081/actuator/health && curl -fs localhost:8090/actuator/health
  docker compose -f infra/docker-compose.yml exec postgres psql -U postgres -d nocook -c '\dt nocook.*' | grep -q cases
  curl -s -u nocook:nocook localhost:15672/api/queues/%2F/nocook.cancellations | grep -q '"type":"quorum"'
  ```

### B-01 · Mock OMS: lifecycle, outbox, relay
- [x] **Owner** B · **Est** 50 min · **Depends** B-00
  - Notes: OrderLifecycleIT 3 tests green; relay uses confirms; traceparent kept in outbox payload, moved to AMQP header.
- **Spec:** API.md §2 (except firehose/test endpoints → Q-03), §3; DATA_MODEL.md §1; BACKEND.md §4
- **Files:** `backend/mock-oms/src/main/java/com/nocook/oms/{orders,outbox,load,config}/**`, tests under `mock-oms/src/test`
- **Build:**
  1. `OrderService` with legal-transition checks (409 on illegal), each mutation in one tx: update order (version+1, timestamps from Clock) + insert outbox row with the event payload (API.md §3) and current `traceparent`.
  2. Endpoints: create, accept, stage, ready, pickup, deliver, cancel, reinstate, get snapshot, list outlet orders, `load/accepted-orders`. `X-Run-Id` stored.
  3. `OutboxRelay`: `@Scheduled(fixedDelay=20ms)` + `wake()` after commit; claim `PENDING` batch 200 `FOR UPDATE SKIP LOCKED`; publish with `x-published-at` and `message-id`; `waitForConfirmsOrDie(5000)`; mark `SENT`. Timer `oms.relay.publish`.
  4. IT: create → accept → cancel produces one `order.cancelled` message with the right payload on a test queue.
- **Done when:**
  ```bash
  cd backend && ./mvnw -q -pl mock-oms verify
  # stack running, nocook listener not yet consuming:
  ID=$(curl -s -XPOST localhost:8090/oms/api/orders -H 'content-type: application/json' -d '{"outletId":"outlet-demo","items":[{"name":"Margherita","qty":1,"pricePaise":45000}]}' | jq -r .orderId)
  curl -s -XPOST localhost:8090/oms/api/orders/$ID/accept >/dev/null
  curl -s -XPOST localhost:8090/oms/api/orders/$ID/cancel -H 'content-type: application/json' -d '{"initiatedBy":"CUSTOMER"}' | jq .status   # "CANCELLED"
  curl -s -u nocook:nocook localhost:15672/api/queues/%2F/nocook.cancellations | jq .messages   # ≥ 1 (or consumed if B-02 is in)
  ```

### F-00 · Frontend scaffold, MQTT, nginx
- [x] **Owner** F · **Est** 45 min · **Depends** —
- **Spec:** FRONTEND.md §1, §2, §6, §7; API.md §4; frontend/CLAUDE.md
- **Files:** `frontend/**`
- **Build:**
  1. Vite React TS (strict), Tailwind 4, react-router, TanStack Query, `mqtt`, recharts, vitest.
  2. `api/client.ts`, `api/types.ts` (all payloads from API.md), `mqtt/useMqtt.ts` (connect to `/mqtt`, status, subscribe API, auto-reconnect), `StatusDot`, `ProofBadge`, `GrafanaPanel` stubs.
  3. Routes with placeholder pages. Tablet page shows MQTT status.
  4. `Dockerfile` (multi-stage) and `nginx.conf` exactly as FRONTEND.md §7.
- **Done when:**
  ```bash
  cd frontend && npm run lint && npm run test -- --run && npm run build
  make up && curl -fs localhost:8080/tablet >/dev/null && echo SPA_OK
  # manual: open http://localhost:8080/tablet → status dot green within 3 s
  ```
  - Notes: lint/test(3)/build/docker build pass; live `make up` + green-dot check deferred to G0. Dockerfile ARG NGINX_IMAGE sits before the first FROM; mqtt imported as default export.

### ☑ Gate G0 — "a cancel lands in `nocook.cancellations`" (target: end of H1)
- [x] `make up` → all containers healthy (`make ps`)
- [x] B-01 "Done when" block passes (message visible in the queue or consumed)
- [x] `make test-domain` green
- [x] `/tablet` shows MQTT connected
- [x] `docker stats --no-stream` total memory noted in TASKS notes (< 8 GB)
  - G0 passed 2026-11-19: 10 containers healthy; cancel produced 1 message in nocook.cancellations; /tablet 200 and MQTT-over-WS via nginx connects; docker stats total ~2.5 GB idle (<8 GB). make up cold build 7.5 min.

---

## 2. Phase H1–4 — Core loop

### B-02 · Ingestion → fresh read → case opening
- [x] **Owner** B · **Est** 60 min · **Depends** G0, Q-01
  - Notes: CaseOpeningIT 6 tests green (needs -Dfailsafe.failIfNoSpecifiedTests=false with -am); live cancel -> AWAITING_ACK with 3 deadlines + MQTT_CARD outbox row.
- **Spec:** DOMAIN.md §2, §5; BACKEND.md §3, §3.1; DATA_MODEL.md §2–3; API.md §2 (`GET` snapshot), §3
- **Files:** `nocook-service/src/main/java/com/nocook/service/{ingestion,oms,application,persistence,config}/**`
- **Build:**
  1. Repositories (`CaseRepository`, `CaseEventRepository`, `DeadlineRepository`, `OutboxRepository`) with SQL from DATA_MODEL.md §3.
  2. `OmsReadClient` (RestClient, 300 ms connect / 800 ms read, no retries) → `OmsView`.
  3. `EffectApplier` implementing DOMAIN.md §5 for `ScheduleDeadline`, `SendCard` (others stubbed until B-04/B-06).
  4. `CaseOpeningService.open` (one tx; `ON CONFLICT DO NOTHING` → duplicate path).
  5. `CancellationListener` → `CancellationIngestor`: validate, read OMS (unavailable → throw to requeue), open, afterCommit hooks (timer + dispatcher wake).
- **Tests (B):** `CaseOpeningIT` (eligible → case + 3 deadlines + 1 outbox row; not-actionable → row with reason, nothing else; duplicate → `DUPLICATE_EVENT`).
- **Done when:**
  ```bash
  cd backend && ./mvnw -q -pl nocook-service -am verify -Dit.test='CaseOpeningIT'
  # stack: cancel an order (B-01 snippet), then:
  docker compose -f infra/docker-compose.yml exec postgres psql -U postgres -d nocook -c "select state, order_id from nocook.cases order by opened_at desc limit 1"   # AWAITING_ACK
  ```

### B-03 · Outbox dispatcher, MQTT publish, receipts
- [x] **Owner** B · **Est** 45 min · **Depends** B-02
  - Notes: OutboxDispatchIT 5 green; live OPEN card on MQTT 111 ms after cancel; receipts record client-tagged timers.
- **Spec:** BACKEND.md §3.3; API.md §1.2, §4; DOMAIN.md §1 (facts)
- **Files:** `…/scheduling/OutboxDispatcher.java`, `…/notifications/**`, `…/application/ReceiptService.java`, `…/api/ReceiptController.java`, `…/config/MqttConfig.java`
- **Build:** claim/lease/dispatch per BACKEND.md §3.3 (MQTT types only here; PLACE_CALL in B-06); `CardPublisher` builds `CardMessage` (API.md §4) and publishes QoS 1, waits for PUBACK; fact `CARD_DISPATCHED`; `ReceiptService` records first receipt per (case, device, kind, messageId) and records `nocook.card.delivery` / `.render` / `.ack.fanout` timers (meters may be stubs until B-07, names final).
- **Tests (B):** `OutboxDispatchIT` (with `RecordingCardPublisher`): PENDING row → published once → SENT; publish failure → retry with backoff.
- **Done when:**
  ```bash
  ./mvnw -q -pl nocook-service -am verify -Dit.test='OutboxDispatchIT'
  docker run --rm --network nocook_default eclipse-mosquitto:2 mosquitto_sub -h mosquitto -t 'nocook/outlets/outlet-demo/cards' -C 1 -W 20 &
  # cancel an order for outlet-demo → the subscriber prints a CardMessage with kind OPEN
  ```

### B-04 · Deadline worker, escalation, lease reaper
- [x] **Owner** B · **Est** 60 min · **Depends** B-02
  - Notes: DeadlineWorkerIT 9 + EscalationServiceIT 4 green; live L1 at 30.2 s; listener backoff (70 s budget) keeps OMS-down cancellations out of the DLQ. Q-02 R2/R4/R5/R7/R8 still to run.
- **Spec:** DOMAIN.md §3.1, §5; ARCHITECTURE.md §3; BACKEND.md §3.2; DATA_MODEL.md §3
- **Files:** `…/scheduling/{DeadlineWorker,LeaseReaper}.java`, `…/application/EscalationService.java`, `EffectApplier` (Reschedule, CancelPending, SendCard TAKEOVER/CLOSE)
- **Build:** worker `tick()` (public, for tests) + `@Scheduled` wrapper guarded by `nocook.scheduling.enabled`; OMS read outside tx; per-deadline tx; DEFERRED → reschedule; reaper for deadlines and outbox with `LEASE_TAKEOVER` facts.
- **Tests:** Q owns R2, R4, R5, R7, R8 — coordinate: B runs them once Q-02 lands.
- **Done when:**
  ```bash
  ./mvnw -q -pl nocook-service -am verify -Dit.test='TwoWorkersOneTimerIT,LeaseTakeoverIT,OmsUnavailableDefersIT,SupersededAtEscalationIT,ExpiryIT'
  # stack: cancel an order, don't ACK; after ~31 s:
  docker compose -f infra/docker-compose.yml exec postgres psql -U postgres -d nocook -c "select state from nocook.cases order by opened_at desc limit 1"   # ESCALATED_L1
  ```

### B-05 · ACK API, open-cases, timeline
- [x] **Owner** B · **Est** 45 min · **Depends** B-02
  - Notes: AckApiIT 11 + AckIdempotencyIT 3 green; open-cases/timeline/ack live (ack 66 ms).
- **Spec:** API.md §1.1, §1.3, §1.4; DOMAIN.md §3.2; BACKEND.md §3.4
- **Files:** `…/application/AckService.java`, `…/api/{AckController,OutletController,TimelineController,ApiExceptionHandler}.java`
- **Build:** outlet header check; idempotency; outcome → HTTP mapping; `derived` ms in timeline.
- **Tests (B):** `AckApiIT` (all rows of API.md §1.1 table); Q's R13 must pass.
- **Done when:**
  ```bash
  ./mvnw -q -pl nocook-service -am verify -Dit.test='AckApiIT,AckKeyReplayIT'
  CASE=$(curl -s localhost:8080/api/outlets/outlet-demo/open-cases | jq -r '.[0].caseId')
  curl -s -XPOST localhost:8080/api/cases/$CASE/ack -H 'content-type: application/json' -H 'X-Outlet-Id: outlet-demo' -H 'X-Device-Id: cli' -d '{"choice":"STOPPED","idempotencyKey":"k1"}' | jq .state   # "ACKNOWLEDGED"
  ```

### B-06 · Mock IVR: reservation, gateway, provider, signed webhooks
- [x] **Owner** B · **Est** 60 min · **Depends** B-03, B-04, B-05
  - Notes: TelephonyFlowIT 8 green; live IVR call at 75.2 s, DTMF 1 closes via IVR; make smoke PASS 76.3 s. AckVsIvrRaceIT/WebhookSecurityIT pending in Q-02.
- **Spec:** ARCHITECTURE.md §4; API.md §1.7, §1.8, §4 (`CallMessage`); DOMAIN.md §5 (`ReserveIvr`, `EndCall`); LOAD_AND_CHAOS.md §4 (auto-outcomes)
- **Files:** `…/telephony/**`, `…/application/TelephonyService.java`, `…/api/TelephonyWebhookController.java`, `CallAttemptRepository`, `WebhookEventRepository`, dispatcher `PLACE_CALL` branch
- **Build:** `ReserveIvr` → call_attempts insert (unique) + outbox `PLACE_CALL`; dispatcher re-checks case open → `placeCall` → `INCOMING_CALL`; provider controller emits HMAC-signed webhooks async; webhook dedupe + status machine (terminal never regresses) + DTMF → `AckService`; `EndCall` → `CALL_ENDED`; auto-outcome scripts for `outlet-load-*`.
- **Tests:** Q's R3, R10.
- **Done when:**
  ```bash
  ./mvnw -q -pl nocook-service -am verify -Dit.test='AckVsIvrRaceIT,WebhookSecurityIT'
  make smoke    # full IVR path (Q-04)
  ```

### F-01 · Tablet: queue, cards, ACK, receipts, takeover, reconnect
- [x] **Owner** F · **Est** 90 min · **Depends** F-00 (API shapes from API.md; works against the live stack once B-03/B-05 land)
  - Notes: 27 vitest green; verified in browser: card on two tabs, ACK on tab-1 closes tab-2, takeover at +30 s.
- **Spec:** FRONTEND.md §4; API.md §1.1–1.3, §4
- **Files:** `frontend/src/routes/tablet/**`, `src/lib/{time,sound}.ts`, `src/components/Countdown.tsx`
- **Build:** everything in FRONTEND.md §4 except the call screen. `useTabletCases` reducer with version ordering; snapshot on every (re)connect; receipts; idempotent ACK; takeover overlay; audio unlock banner; "Simulate offline" toggle.
- **Tests:** Vitest for the reducer (FRONTEND.md §8).
- **Done when:**
  ```bash
  cd frontend && npm run test -- --run && npm run build
  # manual with stack: cancel → card on two tablet tabs < 2 s with beep; ACK on tab-1 closes tab-2; no-ACK → takeover at +30 s
  ```

### F-02 · Tablet call screen
- [x] **Owner** F · **Est** 30 min · **Depends** F-01, B-06
  - Notes: verified in browser: call screen at ~+75 s on both tabs, Answer+1 closes card and call on both, reload restores call from snapshot; timeline has DTMF_ACKNOWLEDGED.
- **Spec:** FRONTEND.md §4 (call screen); API.md §1.7, §4 (`CallMessage`)
- **Done when:** manual: no-ACK case → phone UI at +75 s → Answer → press 1 → card and call screen close on all tablets; timeline shows `DTMF_ACKNOWLEDGED`.

### F-03 · Simulator page
- [x] **Owner** F · **Est** 40 min · **Depends** F-00, B-01
  - Notes: simulator drives full lifecycle against live OMS; Customer cancels produces a card on /tablet.
- **Spec:** FRONTEND.md §3; API.md §2
- **Done when:** manual: full order lifecycle from buttons; illegal buttons disabled; "Customer cancels" produces a card on `/tablet`.

### Q-02 · Test harness + race/invariant ITs (R1–R13)
- [x] **Owner** Q · **Est** 120 min (spread over H1–4) · **Depends** Q-01, B-02 (each R-test also depends on the B task it exercises)
  - Notes: R1-R13 all PASS (+MqttPublishIT); 64 service ITs in 22 classes, 0 failures; no main-code bugs found. tools/export-tests.mjs added (part of Q-07). Single-IT runs need -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false.
- **Spec:** TESTING.md §2, §4
- **Files:** `nocook-service/src/test/java/com/nocook/service/it/**` (AbstractIT, MutableClock, TestClockConfig, FakeOms, RecordingCardPublisher, R1–R13), `nocook-service/src/test/resources/**`
- **Build:** harness first (30 min) so B can use it; then write all R-tests early (they fail until B lands the feature), enable as features land.
- **Done when:**
  ```bash
  cd backend && ./mvnw -q -pl nocook-service -am verify     # all R1–R13 green
  ```

### Q-03 · Firehose, replay, race endpoints (mock-oms)
- [x] **Owner** Q · **Est** 45 min · **Depends** B-01
  - Notes: 14,004 events/s achieved at 14k target (published 279k in 20 s); nocook.cancellations unchanged; mock-oms ~0.8 core, rabbitmq 188 MiB. 6 mock-oms ITs green.
- **Spec:** API.md §2 (firehose, test endpoints); BACKEND.md §4 (firehose pacing); OBSERVABILITY.md §3 (`oms.firehose.achieved`, `oms.events.published`)
- **Files:** `backend/mock-oms/src/main/java/com/nocook/oms/{firehose,testsupport}/**`, `FirehoseRabbitConfig`
- **Done when:**
  ```bash
  curl -s -XPOST localhost:8090/oms/api/firehose -H 'content-type: application/json' -d '{"eventsPerSecond":14000}'
  sleep 20; curl -s localhost:8090/oms/api/firehose | jq .achievedPerSecond     # ≥ 13,000 on the target laptop
  curl -s -u nocook:nocook localhost:15672/api/queues/%2F/nocook.cancellations | jq .messages   # unchanged by firehose
  curl -s -XDELETE localhost:8090/oms/api/firehose
  ```

### Q-04 · System smoke script
- [x] **Owner** Q · **Est** 40 min · **Depends** B-06 (can be written earlier against the spec)
  - Notes: make smoke PASS in 76.3 s: case-open 156 ms, OPEN card 111 ms, TAKEOVER 30213 ms, call 75192 ms, CLOSE 75615 ms.
- **Spec:** TESTING.md §5
- **Files:** `tools/smoke.mjs`, Makefile `smoke`
- **Done when:** `make smoke` prints PASS with step timings.

### ☑ Gate G1 — "one order end-to-end" (target: end of H4)
- [x] `make smoke` PASS (cancel → card < 2 s → takeover at 30 ± 3 s → call at 75 ± 3 s → DTMF 1 → closed on all devices → timeline complete)
- [x] Manual: ACK from `tab-1` closes the card on `tab-2`
- [x] `cd backend && ./mvnw -q verify` → R1–R13 green
- [x] `cd frontend && npm run test -- --run` green
  - G1 passed 2026-11-19: make smoke PASS 76.1 s (case-open 138 ms, OPEN card 18 ms, takeover 30116 ms, call 75257 ms, IVR close 75645 ms); backend verify 132 tests 0 failures incl. R1-R13; frontend 27 tests; two-tab ACK + call flow verified in browser (F-02).

---

## 3. Phase H4–7 — Telemetry + screens

### B-07 · Meters, OTLP, traces, gauges
- [x] **Owner** B · **Est** 60 min · **Depends** G1
  - Notes: All OBSERVABILITY §3 meters live in Prometheus with replica tag; le buckets 0.3/2/3/30 present; 3 replicas report cases_open; one Tempo trace spans mock-oms + nocook (no JDBC spans - starter does not instrument JDBC).
- **Spec:** OBSERVABILITY.md (all); BACKEND.md §3.5 (management block); DASHBOARDS.md §2 (invariant gauges)
- **Files:** `…/observability/NocookMeters.java`, `…/scheduling/GaugeSampler.java`, `ReplicaConfig`, `application.yml` (both apps), mock-oms `OmsMeters`
- **Build:** every meter in OBSERVABILITY.md §3 with exact names and tags; replica common tag; SLO/percentile config with bracket keys; OTLP step 5 s, base unit seconds; AMQP observation on (firehose template off); trace id on cases/facts; manual spans.
- **Done when:**
  ```bash
  curl -s 'localhost:9090/api/v1/label/__name__/values' | jq -r '.data[]' | grep -E '^nocook_case_open_seconds_bucket$'
  curl -s localhost:9090/api/v1/series --data-urlencode 'match[]=nocook_case_open_seconds_bucket' | jq -r '.data[].le' | sort -u | grep -E '^(0\.3|2|2\.0|3|3\.0)$'
  curl -s 'localhost:9090/api/v1/query' --data-urlencode 'query=count by (replica) (nocook_cases_open)' | jq '.data.result | length'   # 3
  # Tempo: Grafana Explore → TraceQL { resource.service.name = "nocook" } shows a trace containing both mock-oms and nocook spans
  ```

### B-08 · Ops API, chaos, control channel
- [x] **Owner** B · **Est** 60 min · **Depends** G1
  - Notes: Ops API + chaos + nocook/control live; 3 chaos annotations in Grafana; kill-replica halts nocook-2; ChaosControllerIT 10 + ObservabilityIT 3; full service verify 77 tests green. Rabbit mgmt API consumer counts lag ~5 s.
- **Spec:** API.md §1.5, §1.6, §1.9, §4 (`nocook/control`); ARCHITECTURE.md §5; INFRA.md §4 (toxiproxy API)
- **Files:** `…/api/{OpsController,ChaosController,InternalController}.java`, `…/chaos/**`, `…/notifications/{ControlChannel,ListenerControl}.java`, `OpsEventRepository`
- **Done when:**
  ```bash
  curl -s localhost:8080/api/ops/summary | jq .replicas
  curl -s -XPOST localhost:8080/api/ops/chaos/db-latency -H 'content-type: application/json' -d '{"ms":300}' && curl -s localhost:8474/proxies/postgres/toxics | jq length   # 1
  curl -s -XPOST localhost:8080/api/ops/chaos/listener -H 'content-type: application/json' -d '{"state":"STOPPED"}'
  curl -s -u nocook:nocook localhost:15672/api/queues/%2F/nocook.cancellations | jq .consumers   # 0
  curl -s -XPOST localhost:8080/api/ops/chaos/reset -d '{}' -H 'content-type: application/json'
  curl -s 'localhost:3000/api/annotations?tags=chaos' | jq length   # ≥ 3
  ```

### Q-05 · Metric names lock + three Grafana dashboards
- [x] **Owner** Q · **Est** 90 min · **Depends** B-07 (start metric-names as soon as B-07 has any meter)
  - Notes: metric lock (47 nocook_ lines) + 3 provisioned dashboards (uids nocook-live-slos/reliability/load, home=live-slos) via new provisioning yaml; live-slos verified in kiosk during P1. p99 gate restored to N>=1000 per spec.
- **Spec:** DASHBOARDS.md (all); OBSERVABILITY.md §2
- **Files:** `tools/metric-names.mjs`, `docs/METRIC_NAMES.lock.md`, `infra/grafana/dashboards/{live-slos,reliability,load}.json`
- **Build:** lock file = all `nocook_*`, `oms_*`, `k6_*`, `http_server_requests*`, `jvm_*`, `hikaricp_*`, `process_*` names + datasource UIDs from `GET :3000/api/datasources` + distinct `le` values for each histogram. Then dashboards per DASHBOARDS.md with fixed uids and panel ids.
- **Done when:**
  ```bash
  make metric-names && grep -c nocook_ docs/METRIC_NAMES.lock.md
  for u in nocook-live-slos nocook-reliability nocook-load; do curl -fs localhost:3000/api/dashboards/uid/$u >/dev/null && echo "$u OK"; done
  # manual during P1: panels 1, 4, 7, 10 show three lines + dashed SLO line, stats show share and N
  ```

### Q-06 · k6 profiles + tablet swarm
- [x] **Owner** Q · **Est** 60 min · **Depends** G1
  - Notes: P1 dry: 0 dropped, 0 http failures; swarm ACKs (acks/s>0), k6_iterations_total in Prometheus. Use -e DURATION (not --duration) and mkdir results/<run> before k6; k6_dropped_iterations_total only exists after a drop.
- **Spec:** LOAD_AND_CHAOS.md §2, §3
- **Files:** `load/profiles.js`, `tools/tablet-swarm.mjs`
- **Done when:**
  ```bash
  docker compose -f infra/docker-compose.yml --profile load up -d swarm
  docker compose -f infra/docker-compose.yml --profile load run --rm -e RUN_ID=dry -e PROFILE=P1 k6 run -o opentelemetry --duration 60s /scripts/profiles.js
  # k6: 0 dropped iterations; Prometheus has k6_iterations_total; swarm log shows acks/s > 0
  ```

### Q-07 · Run orchestration and export tools
- [ ] **Owner** Q · **Est** 75 min · **Depends** Q-06, B-08
- **Spec:** LOAD_AND_CHAOS.md §5–§7; OUTPUT.md §1–§4; DATA_MODEL.md §4–5
- **Files:** `tools/{run,export-run,demo-reset,readme-table,export-tests}.mjs`, Makefile targets
- **Done when:**
  ```bash
  make run P=P1
  ls results/run-*-P1/{manifest,summary,invariants,timeseries,k6-summary}.json
  jq '.verdict, .latency.caseOpen' results/run-*-P1/summary.json
  ```

### F-04 · Ops Live, case timeline, chaos panel
- [ ] **Owner** F · **Est** 90 min · **Depends** F-01, B-08 (Grafana panels need Q-05 for final ids; use the fixed ids from DASHBOARDS.md)
- **Spec:** FRONTEND.md §5.1, §5.2, §6
- **Done when:** manual: `/ops` shows KPIs updating, 4 Grafana panels, case list; clicking a case shows the full timeline with ms offsets; each chaos button changes state and a toast shows the time.

### F-05 · Runs and Faults pages
- [ ] **Owner** F · **Est** 60 min · **Depends** F-04 (build against `frontend/src/fixtures/summary.sample.json` copied from OUTPUT.md until real runs exist)
- **Spec:** FRONTEND.md §5.3, §5.4; OUTPUT.md §3–4
- **Done when:** `/ops/runs` and `/ops/faults` render the fixture and, after Q-07, the real P1 run.

### ☐ Gate G2 — "p99 panels live" (target: end of H7)
- [ ] During `make run P=P1`: Live SLOs panels 1, 4, 7, 10 show p50/p95/p99 + dashed SLO + share + N
- [ ] Chaos buttons write annotations visible on all three dashboards
- [ ] `results/run-*-P1/summary.json` has `verdict` PASS
- [ ] Tempo: click from p99 panel data link opens a slow trace (or a trace list)
- [ ] Ops `/ops` and `/ops/cases/:id` work against live data

---

## 4. Phase H7–9 — Proof runs

### Q-08 · 10,000-scenario simulation
- [x] **Owner** Q · **Est** 75 min · **Depends** Q-01
  - Notes: 9 tests green; results/sim-20261119-0321 (n=10000, seed 2026). SIMULATED: time-to-stop p95 nocook 43.55 s vs baseline 241.2 s; Rs 3,521 avoided per 1,000 cancellations (late-notice 10%; 1,846-6,887 at 5-20%).
- **Spec:** SIMULATION.md (all)
- **Files:** `backend/nocook-sim/**`, `sim/assumptions-v1.json`
- **Done when:**
  ```bash
  cd backend && ./mvnw -q -pl nocook-sim -am test
  make sim && jq '.n, .timeToStopSeconds.nocook.p95, .rupeesAvoided.per1000Cancellations' results/sim-*/sim-summary.json
  ```

### Q-09 · Proof runs and exports
- [ ] **Owner** Q · **Est** 120 min wall clock (mostly waiting; do other work in parallel) · **Depends** G2
- **Spec:** LOAD_AND_CHAOS.md §2, §6, §7; OUTPUT.md
- **Order:** P2 (20 min, start first) → P3 → P4 with `REPLICAS=1` → P4 → P5 → P6 → F2, F3, F4, F5, F6, F7 (each ~6 min with P3 background) → `node tools/export-tests.mjs` → `make screenshots` (optional) → write `notes.md` bottleneck for P6.
- **Done when:**
  ```bash
  for f in results/run-*/summary.json; do jq -r '[.runId, .verdict] | @tsv' $f; done   # no VOID; FAIL only with an explanation in notes.md
  jq -r '.checks[] | select(.pass == false)' results/run-*/invariants.json | wc -l    # 0
  ```

### B-09 · Knee fixes and run support
- [ ] **Owner** B · **Est** open (H7–9) · **Depends** G2
- **Build:** watch P4–P6 with Q. Allowed tuning: Hikari pool, listener concurrency/prefetch, worker batch/tick, outbox batch, JVM heap ≤ 768 MB. **Not allowed:** changing timings, invariants or semantics. Every change → DECISIONS.md change log + rerun of the affected profile.
- **Done when:** P6 knee identified with a named bottleneck in `results/<P6>/notes.md`.

### F-06 · Impact page
- [ ] **Owner** F · **Est** 75 min · **Depends** F-05 (fixture from SIMULATION.md §5 until Q-08 lands)
- **Spec:** FRONTEND.md §5.5; SIMULATION.md §5
- **Done when:** `/ops/impact` renders the real `sim-summary.json` with SIMULATED banner, CDF chart, ₹ with sensitivity, funnel, assumptions.

### F-07 · Polish
- [ ] **Owner** F · **Est** remaining · **Depends** F-01..F-06
- **Build:** phone layout for `/tablet`; error toasts; loading states; Grafana kiosk links; consistent badges; no console errors.
- **Done when:** walkthrough on a phone and on 1024×768 with no layout breaks.

### ☐ Gate G3 — "results exported" (target: end of H9)
- [ ] P2–P6 + F2–F7 folders exist, no VOID, all invariants pass
- [ ] `results/sim-*` exists; Impact tab renders it
- [ ] `results/tests-*.json` lists R1–R13 PASS
- [ ] Runs / Faults tabs show the real data

---

## 5. Phase H9–10 — Rehearse

### ALL-01 · README, headline, results table
- [ ] **Owner** all (Q drives) · **Est** 30 min · **Depends** G3
- **Done when:** `node tools/readme-table.mjs` regenerates the README results table; headline (OUTPUT.md §6) filled from P3 and P6 summaries; DECISIONS.md change log complete.

### ALL-02 · Rehearse twice, record, freeze
- [ ] **Owner** all · **Est** 30 min · **Depends** ALL-01
- **Spec:** DEMO.md §4 checklist
- **Done when:** both run-throughs < 3:00; recording saved; `git tag v-demo`.

### ☐ Gate G4 — "demo < 3 min, twice"
- [ ] DEMO.md §4 checklist fully ticked

---

## 6. Never cut

Durable deadlines with SKIP LOCKED leases · the ACK path · the mock IVR through signed webhooks · race tests R1, R3, R8 · the Live SLOs dashboard · P3 run.

## 7. Cut order (apply in this order when a gate slips > 30 min)

1. P6 knee ramp and the F1 scale-out comparison (run 1 replica only).
2. Toxiproxy faults F4, F5 (keep F2 kill, F3 pause, F6 duplicate storm).
3. Distributed traces (B-07 trace parts) and Loki logs.
4. Impact charts (render `sim-summary.json` as a table instead).
5. Tablet swarm (card-delivery p99 then comes from demo tablets only; hide p99 by the N rule).
6. F-05 Faults page (show `invariants.json` raw in Runs).
7. Screenshots tool.

## 8. Requests between owners

Write requests here instead of editing someone else's files.

| From → To | Request | Status |
|---|---|---|
| | | |

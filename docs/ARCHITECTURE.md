# ARCHITECTURE.md

## 1. Containers (one laptop, Docker Compose)

```
                 ┌────────────┐  HTTP   ┌──────────────┐ publish (confirms) ┌──────────────────────────┐
  k6 ───────────►│  mock-oms  │────────►│   RabbitMQ   │───────────────────►│ queue nocook.cancellations│
  Simulator UI ─►│ :8090      │ outbox  │ exch order.events (topic)          │ bound to order.cancelled  │
                 └─────┬──────┘ relay   └──────────────┘                    └────────────┬─────────────┘
                       │ GET /oms/api/orders/{id}  (via Toxiproxy :18090)                 │ consume (container-acked after commit)
                       ▼                                                                  ▼
                 ┌──────────────┐  SQL (via Toxiproxy :15432)  ┌─────────────────────────────────────┐
                 │ PostgreSQL 18│◄─────────────────────────────│ nocook-1 / nocook-2 / nocook-3       │
                 │ schemas oms, │                              │ consumer · deadline worker · outbox  │
                 │ nocook       │                              │ dispatcher · ACK API · mock IVR      │
                 └──────────────┘                              └───────────────┬─────────────────────┘
                                                                               │ MQTT publish QoS 1
                                                                               ▼
  Tablet (browser) ◄── MQTT over WebSocket (/mqtt via nginx) ──── Mosquitto :1883 / :9001
  Tablet ── HTTPS /api (ACK, receipts, open-cases) ──► nginx :8080 ──► upstream nocook-1..3

  All Java apps + k6 ── OTLP ──► grafana/otel-lgtm (Collector → Prometheus, Tempo, Loki; Grafana :3000)
```

| Service | Image | Host port(s) | Role |
|---|---|---|---|
| `web` | built from `frontend/` (nginx) | 8080 | SPA; proxies `/api`→nocook upstream, `/oms`→mock-oms, `/mqtt`→mosquitto:9001 |
| `nocook-1..3` | built from `backend/nocook-service` | 8081, 8082, 8083 | Same image, `REPLICA_ID` differs |
| `mock-oms` | built from `backend/mock-oms` | 8090 | Order lifecycle, outbox relay, read API, firehose, test endpoints |
| `postgres` | `postgres:18` | 5432 | DB `nocook`, schemas `oms` and `nocook`, separate roles |
| `rabbitmq` | `rabbitmq:4.3-management` | 5672, 15672 | Broker + management UI |
| `mosquitto` | `eclipse-mosquitto:2` | 1883, 9001 | MQTT for services, WebSockets for browsers |
| `toxiproxy` | `ghcr.io/shopify/toxiproxy` | 8474 | Proxies `postgres` (15432) and `oms` (18090) for NoCook only |
| `lgtm` | `grafana/otel-lgtm` | 3000, 4317, 4318, 9090, 3200 | Telemetry backend + provisioned dashboards |
| `k6` | `grafana/k6` | — | Compose profile `load`; started per run |
| `swarm` | `node:24-alpine` running `tools/tablet-swarm.mjs` | — | Compose profile `load`; headless tablets for load outlets |

NoCook reaches Postgres and OMS **through Toxiproxy** so faults can be injected on exactly those paths. mock-oms talks to Postgres directly.

## 2. Main flow: cancel → ACK

```
mock-oms                RabbitMQ          nocook-N                         Postgres            Mosquitto        Tablet
   │ tx: order v→v+1,       │                  │                               │                   │               │
   │ status CANCELLED,      │                  │                               │                   │               │
   │ outbox row             │                  │                               │                   │               │
   │──commit──────────────────────────────────────────────────────────────────►│                   │               │
   │ relay (afterCommit wake│+ 20 ms poll)     │                               │                   │               │
   │──publish order.cancelled (confirm)──►     │                               │                   │               │
   │                        │──deliver────────►│                               │                   │               │
   │◄────── GET /oms/api/orders/{id} (fresh) ──│                               │                   │               │
   │                        │                  │ decideOpen() (pure)           │                   │               │
   │                        │                  │ tx: case + facts + 3 deadlines + outbox(MQTT_CARD)│               │
   │                        │                  │──commit──────────────────────►│                   │               │
   │                        │◄──ack (container)│ timer nocook.case.open        │                   │               │
   │                        │                  │ dispatcher claims outbox ────►│                   │               │
   │                        │                  │──publish nocook/outlets/{o}/cards (QoS1)─────────►│──────────────►│
   │                        │                  │◄────────────── POST /api/cases/{id}/receipts DELIVERED, RENDERED ──│
   │                        │                  │ timer nocook.card.delivery                        │               │
   │                        │                  │◄────────────── POST /api/cases/{id}/ack {STOPPED} ─────────────────│
   │                        │                  │ tx: lock case, transition → ACKNOWLEDGED,         │               │
   │                        │                  │     cancel deadlines, outbox(CARD_CLOSE)          │               │
   │                        │                  │──publish CARD_CLOSE ─────────────────────────────►│──────────────►│ all devices
   │                        │                  │◄────────────── receipts DISMISSED → nocook.ack.fanout ─────────────│
```

## 3. Escalation flow (deadline worker, every 250 ms on every replica)

```
claim ≤50 due deadlines (FOR UPDATE SKIP LOCKED, lease 10 s, :now from Clock)  → commit
for each claimed deadline:
    if kind == EXPIRY: no OMS read needed
    else: fresh = OmsReadClient.get(orderId)   (connect 300 ms, read 800 ms, no retry)
          if fresh unavailable → tx: deadline back to PENDING, due_at = now + 2 s, fact ESCALATION_DEFERRED; continue
    tx: SELECT case FOR UPDATE
        result = CasePolicy.transition(case, DeadlineDue(kind), omsView, now)
        apply result: update case (version+1), insert facts, cancel/insert deadlines, insert outbox rows
        deadline → DONE (or CANCELLED if result was NO_OP)
    commit → record nocook.deadline.lateness = commitInstant − due_at
lease reaper (every 2 s): LEASED rows with lease_until < :now → PENDING, count nocook.lease.takeovers
```

## 4. Mock IVR flow

```
IVR deadline fires → transition → ESCALATED_IVR, effect ReserveIvr
  tx: INSERT call_attempts(case_id UNIQUE, status RESERVED) + outbox(PLACE_CALL, action_key ivr:{caseId})
      unique violation on call_attempts → count nocook.ivr.duplicate_prevented, no outbox row
dispatcher claims PLACE_CALL:
  re-check case is open (SELECT state) → if closed: call_attempts.status = CANCELLED, done
  MockTelephonyGateway.placeCall() → providerCallId, status DIALING
  publish nocook/outlets/{o}/calls INCOMING_CALL
Tablet call screen → POST /api/mock-telephony/calls/{providerCallId}/{answer|reject|busy|no-answer|fail|dtmf}
  mock provider (same app, separate controller) builds a signed webhook:
  POST /api/webhooks/telephony/status  or  /dtmf   (X-Mock-Signature: HMAC-SHA256)
Webhook: verify signature → dedupe providerEventId (webhook_events PK) → 
  status → update call_attempts + fact; ANSWERED re-checks case open (closed → hang up + fact)
  dtmf 1|2|3 → MerchantAck(STOPPED|NOT_STARTED|ALREADY_COOKED, channel IVR) → same ACK path as tablet
```

Load outlets (`outlet-load-*`) have no human: the mock gateway auto-plays a provider outcome for them (see `LOAD_AND_CHAOS.md` §4).

## 5. Ops and chaos control

- Ops page → `POST /api/ops/chaos/{action}` on any replica (nginx picks one).
- Actions that must reach **all replicas** (listener stop/start) are broadcast on MQTT topic `nocook/control`; every replica subscribes.
- Toxiproxy actions call the Toxiproxy HTTP API once.
- `kill-replica` forwards to the target replica's `/internal/halt` (`Runtime.halt(1)`, no graceful shutdown).
- Every chaos action writes a Grafana annotation (`POST lgtm:3000/api/annotations`, tags `chaos`, action name).

## 6. Telemetry

No broker carries telemetry. Each Spring app pushes OTLP over HTTP to `http://lgtm:4318` (metrics every 5 s, traces batched). k6 pushes OTLP gRPC to `lgtm:4317`. Spring AMQP observation propagates `traceparent` through message headers, so OMS publish and NoCook consume share a trace. Detail in `OBSERVABILITY.md`.

## 7. Production mapping (for judge Q&A)

| Demo | Production at Zomato |
|---|---|
| mock-oms + outbox relay | Real OMS events (Kafka) |
| `CancellationListener` (RabbitMQ) | Same `CancellationIngestor` port, Kafka consumer-group adapter |
| `GET /oms/api/orders/{id}` | Read-only OMS API |
| Mosquitto | Pulse (Zomato's MQTT infra) / existing merchant notification layer |
| MockTelephonyGateway | Exotel/Plivo adapter behind the same interface |
| otel-lgtm | Same OTLP to New Relic (delta temporality) |
| 3 replicas on a laptop | N pods; deadlines and outbox already safe for competing workers |

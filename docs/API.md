# API.md — every contract between components

All JSON uses camelCase. Timestamps are ISO-8601 UTC with milliseconds (`2026-11-21T14:44:52.180Z`). IDs: case ids are UUIDv7 strings; order ids look like `ord-00004417`.

Error body for every 4xx/5xx:

```json
{ "error": "CASE_CLOSED", "message": "Case is EXPIRED", "caseId": "…", "state": "EXPIRED" }
```

---

## 1. NoCook REST (`nocook-service`, reached via nginx at `http://localhost:8080/api/...`)

### Identity (hackathon level, documented as such)

Tablet calls send `X-Outlet-Id` and `X-Device-Id`. The server checks `case.outlet_id == X-Outlet-Id` (else `403 OUTLET_MISMATCH`). Ops calls send nothing. Real auth is out of scope (ADR-012).

### 1.1 `POST /api/cases/{caseId}/ack`

Request

```json
{ "choice": "STOPPED", "idempotencyKey": "7d1c…uuid", "caseVersionSeen": 1 }
```

| Status | Body | When |
|---|---|---|
| 200 | `{ "caseId", "state": "ACKNOWLEDGED", "choice", "channel": "TABLET", "ackedAt", "duplicate": false }` | A1 |
| 200 | same with `"duplicate": true` | A2 (same idempotencyKey replayed) |
| 409 | `ALREADY_ACKNOWLEDGED` + `ackChoice`, `ackedAt` | A3 |
| 409 | `CASE_CLOSED` + `state`, `closeReason` | A4 |
| 409 | `NOT_ACTIONABLE` | A5 |
| 404 | `CASE_NOT_FOUND` | |
| 403 | `OUTLET_MISMATCH` | |
| 400 | `VALIDATION_FAILED` | choice not in enum, missing key |

Latency SLO: p99 < 300 ms (`http.server.requests{uri="/api/cases/{caseId}/ack"}`).

### 1.2 `POST /api/cases/{caseId}/receipts` → 202

```json
{ "kind": "DELIVERED", "deviceId": "tab-1", "messageId": "card:…:OPEN:1", "deviceAt": "…" }
```

`kind` ∈ `DELIVERED` (MQTT message handled by the app), `RENDERED` (card painted; send from `requestAnimationFrame` after mount), `DISMISSED` (card removed after a `CLOSE`). The first receipt of each `(caseId, deviceId, kind, messageId)` is recorded as a fact and drives a timer; repeats are ignored (still 202).

### 1.3 `GET /api/outlets/{outletId}/open-cases`

Used by the tablet on load and on every MQTT reconnect. Never returns closed cases.

```json
[ { "caseId": "…", "orderId": "ord-00004417", "orderRef": "4417", "orderVersion": 4,
    "state": "ESCALATED_L1", "caseVersion": 2,
    "cancelledAt": "…", "openedAt": "…", "l1At": "…", "ivrAt": "…", "expiresAt": "…",
    "call": { "providerCallId": "mock-…", "status": "RINGING" } } ]
```

`call` is null unless a call attempt exists and is not terminal.

### 1.4 `GET /api/cases/{caseId}/timeline`

```json
{ "case": { "caseId", "orderId", "orderRef", "outletId", "state", "caseVersion", "cancelledAt", "openedAt",
            "closedAt", "closeReason", "ackChoice", "ackChannel", "runId" },
  "events": [ { "at": "…", "kind": "CASE_OPENED", "channel": "SYSTEM", "deviceId": null,
                "metadata": { "eligible": "true" }, "traceId": "…" } ],
  "derived": { "caseOpenMs": 180, "firstDeliveredMs": 390, "firstRenderedMs": 1020, "ackMs": 87000 } }
```

`derived.*Ms` are milliseconds from `cancelledAt`. Events sorted by `at`.

### 1.5 Ops (read)

| Endpoint | Returns |
|---|---|
| `GET /api/ops/cases?state=&outlet=&limit=50&before=` | newest-first page of `case` objects (shape as in 1.4 `case`) |
| `GET /api/ops/summary` | `{ "openCases", "openedLast5m", "ackedLast5m", "expiredLast5m", "queueReady", "dlqReady", "outboxPending", "deadlinesOverdue", "replicas": [{"id":"nocook-1","up":true,"listener":"RUNNING"}] }` |
| `GET /api/ops/runs` | list of `results/*/summary.json` headers (runId, profile, startedAt, verdict) |
| `GET /api/ops/runs/{runId}` | full `summary.json` + `invariants.json` + `manifest.json` for that run |
| `GET /api/ops/simulations/latest` | latest `results/sim-*/sim-summary.json` |
| `GET /api/ops/chaos/state` | `{ "dbLatencyMs": 0, "omsDown": false, "listener": "RUNNING" }` |

`results/` is mounted read-only into every nocook container at `/results`.

### 1.6 Ops (chaos) — `POST /api/ops/chaos/{action}`

| Action | Body | Effect |
|---|---|---|
| `db-latency` | `{ "ms": 300 }` (0 removes) | Toxiproxy toxic `latency_downstream` on proxy `postgres` |
| `oms-down` | `{ "down": true }` | Toxiproxy proxy `oms` `enabled=false` / `true` |
| `listener` | `{ "state": "STOPPED" }` or `"RUNNING"` | MQTT `nocook/control` broadcast; every replica stops/starts its `RabbitListenerEndpointRegistry` containers |
| `kill-replica` | `{ "replica": "nocook-2" }` | `POST http://nocook-2:8080/internal/halt` (header `X-Internal-Token`) → `Runtime.getRuntime().halt(1)` |
| `duplicate-storm` | `{ "lastN": 50, "times": 20 }` | calls `POST /oms/api/test/replay-cancellations` |
| `reset` | `{}` | removes all toxics, enables `oms`, listener RUNNING |

Every action: insert `nocook.ops_events`, write a Grafana annotation (tags `["chaos", action]`, text = body JSON), return `{ "ok": true, "at": "…" }`.

### 1.7 Mock telephony provider (simulates the external provider; lives in nocook-service)

`POST /api/mock-telephony/calls/{providerCallId}/{action}` → 202

| action | body | Webhook it emits |
|---|---|---|
| `answer` | — | status `ANSWERED` |
| `reject` | — | status `FAILED` |
| `busy` | — | status `BUSY` |
| `no-answer` | — | status `NO_ANSWER` |
| `fail` | — | status `FAILED` |
| `dtmf` | `{ "digit": "1" }` | dtmf `digits=1`, then status `COMPLETED` |

The provider generates `providerEventId = uuidv4`, signs the raw JSON body and POSTs to the webhook asynchronously (virtual thread), so the tablet call screen behaves like a real provider round-trip.

### 1.8 Telephony webhooks (what a real provider would call)

Header `X-Mock-Signature: <hex HMAC-SHA256(MOCK_TELEPHONY_SECRET, raw body bytes)>`. Bad/missing signature → 401 `BAD_SIGNATURE`. Duplicate `providerEventId` → 200 `{ "duplicate": true }`, no effect.

`POST /api/webhooks/telephony/status`

```json
{ "providerCallId": "mock-…", "providerEventId": "…", "status": "ANSWERED", "at": "…" }
```

`status` ∈ `RINGING, ANSWERED, COMPLETED, NO_ANSWER, BUSY, FAILED, UNKNOWN`. Out-of-order callbacks are accepted; terminal statuses never move back to non-terminal.

`POST /api/webhooks/telephony/dtmf`

```json
{ "providerCallId": "mock-…", "providerEventId": "…", "digits": "1", "at": "…" }
```

Digit `1|2|3` → `MerchantAck(STOPPED|NOT_STARTED|ALREADY_COOKED, IVR, actor=providerCallId, idempotencyKey=providerEventId)`. Any other digit → fact only.

### 1.9 Internal

`POST /internal/halt` (header `X-Internal-Token: ${INTERNAL_TOKEN}`) — not routed by nginx.

---

## 2. Mock OMS REST (`mock-oms`, via nginx at `http://localhost:8080/oms/api/...`)

Optional header `X-Run-Id` on any create call; stored in `orders.run_id` and carried in events.

| Method + path | Body | Response |
|---|---|---|
| `POST /oms/api/orders` | `{ "outletId", "items": [{"name","qty","pricePaise"}] }` | 201 order snapshot (PLACED, v1) |
| `POST /oms/api/orders/{id}/accept` | — | 200 snapshot |
| `POST /oms/api/orders/{id}/stage` | `{ "stage": "PREP" \| "OVEN" }` | 200 |
| `POST /oms/api/orders/{id}/ready` | — | 200 |
| `POST /oms/api/orders/{id}/pickup` | — | 200 |
| `POST /oms/api/orders/{id}/deliver` | — | 200 |
| `POST /oms/api/orders/{id}/cancel` | `{ "initiatedBy": "CUSTOMER" }` | 200 |
| `GET /oms/api/orders/{id}` | — | 200 `OrderSnapshot` (**NoCook's only OMS dependency**) |
| `GET /oms/api/outlets/{outletId}/orders?active=true` | — | 200 list, newest first |
| `POST /oms/api/load/accepted-orders` | `{ "outletId", "stage": "PREP", "acceptedSecondsAgo": 120, "valuePaise": 45000 }` | 201 snapshot (ACCEPTED in one tx, one `order.accepted` event) |
| `POST /oms/api/firehose` | `{ "eventsPerSecond": 14000 }` | 200 `{ "running": true, "eventsPerSecond" }` |
| `DELETE /oms/api/firehose` | — | 200 `{ "running": false, "published": n }` |
| `GET /oms/api/firehose` | — | 200 `{ "running", "eventsPerSecond", "achievedPerSecond", "published" }` (achieved = last 10 s) |
| `POST /oms/api/test/replay-cancellations` | `{ "lastN": 50, "times": 20 }` | 200 `{ "republished": 1000 }` (same eventId, same payload, `x-replay=true` header) |
| `POST /oms/api/test/race/ready-then-cancel` | `{ "count": 500, "outletId": "outlet-race", "gapMillis": 5 }` | 200 `{ "orderIds": [...] }` |
| `POST /oms/api/orders/{id}/reinstate` | — | 200 (test only: CANCELLED → ACCEPTED, for the SUPERSEDED demo) |

`OrderSnapshot`:

```json
{ "orderId": "ord-00004417", "outletId": "outlet-demo", "version": 4, "status": "CANCELLED", "stage": "OVEN",
  "valuePaise": 45000, "orderRef": "4417", "acceptedAt": "…", "readyAt": null, "pickedUpAt": null,
  "cancelledAt": "…", "cancelInitiator": "CUSTOMER", "items": [ … ] }
```

Read-API latency budget: NoCook client uses connect timeout 300 ms, read timeout 800 ms, no retries.

---

## 3. AMQP (RabbitMQ 4.3)

Topology (declared by both apps as Spring `Declarables`; declarations are idempotent):

| Object | Name | Properties |
|---|---|---|
| Exchange | `order.events` | topic, durable |
| Queue | `nocook.cancellations` | durable, `x-queue-type=quorum`, `x-delivery-limit=10`, `x-dead-letter-exchange=order.events.dlx` |
| Binding | `order.events` → `nocook.cancellations` | routing key `order.cancelled` |
| Exchange | `order.events.dlx` | fanout, durable |
| Queue | `nocook.cancellations.dlq` | durable, quorum |

Routing keys published by mock-oms: `order.placed`, `order.accepted`, `order.stage_changed`, `order.ready`, `order.picked_up`, `order.delivered`, `order.cancelled`, `order.reinstated`. Only `order.cancelled` reaches NoCook; the rest are dropped by the broker (no binding).

Message properties: `content-type=application/json`, `delivery-mode=2` (persistent), `message-id=<eventId>`, header `x-published-at=<relay publish instant>`, `traceparent` (added by Spring AMQP observation). Firehose messages add header `x-synthetic=true`.

Payload (`schemaVersion` 1):

```json
{ "schemaVersion": 1, "eventId": "0193…", "type": "order.cancelled", "orderId": "ord-00004417",
  "outletId": "outlet-demo", "orderVersion": 4, "occurredAt": "2026-11-21T14:44:52.000Z",
  "initiatedBy": "CUSTOMER", "runId": null, "synthetic": false }
```

Publisher (mock-oms relay): `publisher-confirm-type=simple`; batch up to 200 outbox rows → `rabbitTemplate.invoke(ops -> { send each; ops.waitForConfirmsOrDie(5000); })` → mark rows `SENT`. On failure leave `PENDING`; next tick retries (duplicates are safe).

Consumer (nocook): container acknowledge mode `AUTO` (broker sees manual acks; the container acks after the listener method returns, i.e. after the DB transaction commits; an exception → nack + requeue; after 10 deliveries → DLQ). `prefetch=50`, `concurrency=4`, `max-concurrency=8`, single-message listener (no batch), `observation-enabled=true`. Schema-invalid payload → throw `AmqpRejectAndDontRequeueException` → DLQ immediately.

---

## 4. MQTT (Mosquitto 2, MQTT 5, QoS 1, retain=false)

| Topic | Publisher | Subscriber | Payload |
|---|---|---|---|
| `nocook/outlets/{outletId}/cards` | nocook dispatcher | tablets, swarm | `CardMessage` |
| `nocook/outlets/{outletId}/calls` | nocook dispatcher | tablets, swarm | `CallMessage` |
| `nocook/control` | nocook (ops API) | all nocook replicas | `{ "command": "LISTENER_STOP" \| "LISTENER_START", "at", "by" }` |

Backend client id: `nocook-${REPLICA_ID}` (clean start true). Browser client id: `tablet-${outletId}-${deviceId}-${random4}`.

`CardMessage`

```json
{ "messageId": "card:0193…:OPEN:1", "kind": "OPEN", "caseId": "0193…", "caseVersion": 1,
  "orderId": "ord-00004417", "orderRef": "4417", "orderVersion": 4, "outletId": "outlet-demo",
  "state": "AWAITING_ACK", "cancelledAt": "…", "openedAt": "…",
  "l1At": "…", "ivrAt": "…", "expiresAt": "…",
  "closeReason": null, "ackChoice": null, "ackChannel": null, "sentAt": "…" }
```

`kind` ∈ `OPEN`, `TAKEOVER` (state `ESCALATED_L1`), `CLOSE` (terminal state, with `closeReason`, `ackChoice`, `ackChannel`). Tablets **ignore any message whose `caseVersion` is lower than the one they hold** (out-of-order delivery). `messageId` equals the outbox `action_key`.

`CallMessage`

```json
{ "messageId": "call:…", "kind": "INCOMING_CALL", "caseId": "…", "providerCallId": "mock-0193…",
  "orderRef": "4417", "outletId": "outlet-demo",
  "script": "Kitchen cancellation alert. Order ending 4417 is cancelled. Press 1 if you stopped preparing; press 2 if preparation had not started; press 3 if it was already cooked.",
  "sentAt": "…" }
```

`kind` ∈ `INCOMING_CALL`, `CALL_ENDED` (case closed or call terminal; tablet closes the call screen).

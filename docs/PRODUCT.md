# PRODUCT.md — what NoCook is, and what it is not

## Problem

When a customer cancels a Zomato order, the cancellation is committed in the OMS and a push notification goes to the restaurant. Nothing checks that a human in the kitchen saw it. If the cook doesn't notice, the food gets made anyway: wasted prep cost for the restaurant, and no evidence for anyone about when the kitchen was told.

Delivered ≠ seen ≠ acknowledged. A push PUBACK proves one network hop. A device receipt proves the app got it. Only an explicit human response proves the kitchen knows.

## What NoCook does

For every **eligible** cancellation (accepted, not yet ready, not restaurant-initiated), NoCook opens an **assurance case** and runs a bounded clock:

| Time after cancel commit | Action |
|---|---|
| 0 s | Pinned cancellation card on every outlet tablet, with three ACK choices |
| 30 s | No ACK → full-screen takeover (L1) on tablets |
| 75 s | Still no ACK → exactly one mock IVR call; keypad 1/2/3 acknowledges |
| 10 min | Still open → EXPIRED, sent to review, never blamed |

The first valid human response closes the case everywhere. Every step is an append-only server fact with a server timestamp.

ACK choices: **STOPPED** (I stopped preparing), **NOT_STARTED** (hadn't started), **ALREADY_COOKED** (it was already done).

## Who benefits

- **Restaurant:** stops wasting prep on cancelled orders; has a record of when they were told.
- **Zomato:** fewer disputes, an auditable signal, a measurable "time to kitchen stop".
- **Customer:** indirect — less waste priced into the system.

## Scope for the hackathon (10 hours)

In: mock OMS, NoCook service ×3 replicas, RabbitMQ, PostgreSQL, MQTT tablets, mock IVR through real webhooks, Grafana dashboards, k6 load to 10× Zomato's NYE record, live fault injection, a 10,000-scenario simulated impact study.

Out (say so if asked): real telephony, real auth, multi-region, Kafka (production adapter only), mutating OMS in any way, inferring fault, monitoring kitchen staff.

## Proof labels

| Label | Meaning | Where it appears |
|---|---|---|
| **MEASURED** | Real wall-clock timings from the running stack under load | Grafana Live SLOs, Load dashboards; Runs tab |
| **TESTED** | Pass/fail of an invariant under an injected fault or race | Reliability dashboard; Faults tab; test reports |
| **SIMULATED** | Output of the virtual-time model with stated assumptions | Impact tab only |

Never mix them on one panel without the badge.

## Glossary

| Term | Meaning |
|---|---|
| Case / assurance case | One row in `nocook.cases` per `(order_id, order_version)` cancellation |
| Open case | State `AWAITING_ACK`, `ESCALATED_L1` or `ESCALATED_IVR` |
| Fact | Append-only row in `nocook.case_events` |
| Effect / intent | A side effect recorded as an outbox row before it happens |
| Deadline | Durable timer row (`L1`, `IVR`, `EXPIRY`) in `nocook.deadlines` |
| Lease | A worker's time-boxed claim on a deadline or outbox row |
| Fresh read | A synchronous `GET /oms/api/orders/{id}` made just before acting |
| Demo outlet | `outlet-demo`, the one judges watch |
| Load outlets | `outlet-load-0001` … `outlet-load-0500`, driven by k6 and the tablet swarm |
| Firehose | Synthetic non-cancel order events published to the broker to load it |
| Tablet swarm | Headless MQTT clients that act as tablets for load outlets |
| Run | One execution of a load profile, identified by `runId` |

# DECISIONS.md — ADR log

One entry per decision. Append; never rewrite history. Format: context → decision → consequence. Evidence links are in the team's "NoCook — Final Build Decisions" doc §9.

| ID | Decision | Why | Consequence |
|---|---|---|---|
| ADR-001 | **RabbitMQ 4.3** (quorum queue, topic exchange) as the event bus | Team knows it; one container; broker-side routing filters non-cancel events; quorum queue docs recommend publisher confirms + manual acks | No per-key ordering → safety comes from DB uniqueness + fresh OMS reads. Production path: Kafka consumer-group adapter behind `CancellationIngestor` |
| ADR-002 | **MQTT 5 over WebSockets via Mosquitto 2** for tablet push | Spring's simple STOMP broker "is not suitable for clustering"; any replica can publish; Pulse is MQTT | Extra container; RabbitMQ Web MQTT plugin considered and rejected (QoS 2 unsupported for MQTT 5, less tested with MQTT.js) |
| ADR-003 | **Own deadline table** claimed with `FOR UPDATE SKIP LOCKED` + 10 s leases | Joins the case transaction; fast takeover; same pattern as db-scheduler | We own the reaper and its tests (R2, R4) |
| ADR-004 | **JdbcClient + hand-written SQL**, no JPA | SKIP LOCKED, ON CONFLICT and partial unique indexes are explicit SQL anyway; less magic for generated code | More SQL in repositories |
| ADR-005 | **Explicit SLO buckets + percentiles histogram**, base time unit seconds, step 5 s | Exact share-under-SLO needs a bucket at the SLO; histograms aggregate across replicas; defaults (ms, 1 min) break dashboards | Bucket count bounded by min/max expected values |
| ADR-006 | **grafana/otel-lgtm** single container | One image for Collector, Prometheus, Tempo, Loki, Grafana; dev/demo use | Not production; same OTLP goes to New Relic in production |
| ADR-007 | **k6 arrival-rate executors** (open model) + firehose + tablet swarm | Closed-model tests hide overload; swarm gives N for delivery/ACK metrics at load | Swarm lag is included in `client="swarm"` series; reported separately |
| ADR-008 | **Mock IVR through signed webhooks** | Exercises the real reservation, outbox, webhook, dedupe and ACK path without a telecom account | Real provider = new `TelephonyGateway` impl |
| ADR-009 | **Separate DB roles**; `nocook_app` has no access to schema `oms` | Makes "NoCook never mutates OMS" a database fact (R11) | Invariant export connects as `postgres` |
| ADR-010 | Deadlines anchored to **`cancelledAt`** (OMS commit), not processing time | The kitchen clock starts at cancel; backlog must not stretch escalation | After an outage, overdue deadlines fire immediately; >10 min ones expire |
| ADR-011 | **ACK not gated on case version** | A card seen at v1 must remain acknowledgeable after L1 bumps to v2 | `caseVersionSeen` stored as metadata only |
| ADR-012 | Identity via `X-Outlet-Id`/`X-Device-Id` headers only | Hackathon scope | Documented as non-production; real auth = outlet-scoped tokens |
| ADR-013 | **Version-advance alone doesn't supersede**; reinstated/ready/picked-up does | A version bump without a state change shouldn't silence an escalation | `ORDER_VERSION_ADVANCED` fact recorded |
| ADR-014 | **Maven** multi-module, poms generated from start.spring.io | Boot 4 renamed/split starters; generated poms avoid guessed coordinates | — |
| ADR-015 | **Jackson 3** (`tools.jackson`, `JsonMapper`) | Boot 4 default | Generated code must not use `com.fasterxml.jackson.databind` |
| ADR-016 | Toxiproxy only on NoCook's DB and OMS paths | Faults must hit NoCook's dependencies, not the generators | mock-oms connects to Postgres directly |
| ADR-017 | Container ack mode `AUTO` (broker-level manual acks, acked after the transactional listener returns) | Equivalent to manual ack after commit with less code | Exception → requeue; delivery limit 10 → DLQ |

## Change log

| Time | Who | Change | Docs touched |
|---|---|---|---|
| 2026-11-19 | Q-01 | NotRead on an L1/IVR deadline defers like Unavailable (keeps I5). D4-D6 emit CancelPendingDeadlines + SendCard(CLOSE) but no EndCall (table says "same as D4"); service may need to end an active call on supersede. D5 checked before D6. A2 needs a non-null equal key. Rule 2: acceptedAt == cancelledAt is not-accepted-before-cancel. Fact metadata is Map<String,String>, instants as ISO strings. | DOMAIN.md (clarifications only) |
| 2026-11-19 | integrator | Pins: Boot 4.1.2 (Maven coordinate; start.spring.io prints 4.1.2.RELEASE), otel-lgtm 0.41.0, toxiproxy 2.13.0, k6 1.8.3, nginx stable-alpine3.24, hivemq-mqtt-client 1.3.17. Docker Desktop has 8 GB RAM; fall back to 1 replica if the stack does not fit. | CLAUDE.md §2 |
| 2026-11-19 | F-00 | Dockerfile declares ARG NGINX_IMAGE before the first FROM (Docker requirement); `import mqtt from 'mqtt'` because the named connect import breaks the Vite 8 build; API.md-undefined shapes (CaseState, RunDetail, SimulationSummary) typed loosely until F-04..F-06. | frontend only |
| 2026-11-19 | B-00/B-01 | JVM TZ forced to UTC (Postgres 18 rejects `Asia/Calcutta`). oms.outbox has no traceparent column: stored in payload JSON, relay moves it to the AMQP header. load/accepted-orders creates status ACCEPTED. Reinstate lives in OrderController. Failsafe in parent pom. Replica tag via management.metrics.tags.replica. | DATA_MODEL.md untouched |

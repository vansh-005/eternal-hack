# OBSERVABILITY.md — meters, traces, logs (the contract the dashboards depend on)

## 1. Pipeline

```
nocook-1..3, mock-oms ── Micrometer ──► OTLP/HTTP :4318 ─┐
                       └ Micrometer Tracing (OTel bridge) ─┤
k6 ── -o opentelemetry ──► OTLP/gRPC :4317 ────────────────┤
                                                           ▼
                                   grafana/otel-lgtm: OTel Collector → Prometheus (metrics) · Tempo (traces) · Loki (logs) → Grafana
```

No broker carries telemetry. Settings that matter (BACKEND.md §3.5 has the full block):

| Property | Value | Why |
|---|---|---|
| `management.otlp.metrics.export.step` | `5s` | Default is `1m`: dashboards would look frozen |
| `management.otlp.metrics.export.base-time-unit` | `seconds` | Default is `milliseconds`; Prometheus names and `le` labels must be seconds |
| `management.otlp.metrics.export.aggregation-temporality` | `cumulative` (default) | Prometheus needs cumulative; New Relic would use `delta` in production |
| `management.otlp.metrics.export.histogram-flavor` | `explicit-bucket-histogram` (default) | Exact SLO share needs a bucket boundary at each SLO |
| `management.metrics.distribution.percentiles-histogram.[nocook]` | `true` | Enough buckets for smooth p50/p95/p99 |
| `management.metrics.distribution.slo.[<meter>]` | `300ms,2s,3s` | Adds exact boundaries at the SLO thresholds |
| `management.metrics.distribution.minimum/maximum-expected-value.[nocook]` | `1ms` / `30s` | Bounds the bucket count |
| `management.tracing.sampling.probability` | `1.0` (P1–P3), `0.1` (P4–P6 via `TRACING_SAMPLE`) | Tempo volume at high rates |
| `spring.rabbitmq.listener.simple.observation-enabled` / `spring.rabbitmq.template.observation-enabled` | `true` | Publish and consume join one trace via `traceparent` header |
| Common tag | `replica=<REPLICA_ID>` via `MeterRegistryCustomizer` | Per-replica panels; aggregate with `sum by (le)` |

Firehose `RabbitTemplate` has observation **off** (14,000 msg/s of spans would swamp Tempo).

## 2. Name translation (verify, then lock)

Micrometer dot names become Prometheus names through the OTLP translator: dots → underscores, the unit is appended, counters get `_total`, histograms get `_bucket` / `_count` / `_sum`. Expected:

| Micrometer | Expected Prometheus series |
|---|---|
| Timer `nocook.case.open` (seconds) | `nocook_case_open_seconds_bucket{le=…}`, `_count`, `_sum` |
| Counter `nocook.cases.opened` | `nocook_cases_opened_total` |
| Gauge `nocook.queue.ready` | `nocook_queue_ready` |
| `http.server.requests` | `http_server_requests_seconds_bucket{uri, method, status, outcome}` |
| k6 Trend `http_req_duration` (ms) | `k6_http_req_duration_milliseconds_bucket` (unit may differ) |

**Task Q-05 runs `make metric-names` and writes `docs/METRIC_NAMES.lock.md`** (actual names, datasource UIDs, a sample of `le` values). Dashboards are written against the lock file, never against this table.

## 3. Meter catalogue (`NocookMeters`, `OmsMeters`)

Tags in `{}`. Every nocook meter also carries `replica`. Do not add high-cardinality tags (no caseId, orderId, outletId, runId).

### 3.1 Latency timers (MEASURED, SLO-bearing)

| Meter | Starts | Stops | Recorded where | Tags | SLO (p99) |
|---|---|---|---|---|---|
| `nocook.case.open` | event `occurredAt` (OMS commit) | case tx committed (afterCommit, Clock) | `CancellationIngestor` | — | ≤ 2 s |
| `nocook.card.delivery` | `cases.opened_at` | first `DELIVERED` receipt per (case, device) received | `ReceiptService` | `client=tablet\|swarm` | ≤ 2 s |
| `nocook.card.render` | `cases.opened_at` | first `RENDERED` receipt per (case, device) | `ReceiptService` | `client` | ≤ 2 s |
| `nocook.ack.fanout` | `cases.acked_at` | `DISMISSED` receipt per device (CLOSE with reason ACKNOWLEDGED) | `ReceiptService` | `client` | ≤ 2 s |
| `nocook.deadline.lateness` | `deadlines.due_at` | escalation tx committed | `DeadlineWorker` | `kind=L1\|IVR\|EXPIRY` | ≤ 3 s |
| `http.server.requests` | request in | response out | Spring auto | `uri, method, status, outcome` | ACK uri < 300 ms |
| `nocook.consume.lag` | `x-published-at` header (relay publish) | listener start | `CancellationIngestor` | — | report only |
| `nocook.oms.read` | read start | snapshot or failure | `OmsReadClient` | `outcome=ok\|timeout\|error`, `path=open\|escalation` | report only |
| `nocook.outbox.dispatch` | outbox `created_at` | `SENT` | `OutboxDispatcher` | `type` | report only |
| `oms.relay.publish` | outbox `created_at` | broker confirm | `OutboxRelay` | — | report only |

The `client` tag lets the dashboard show human-tablet and swarm numbers separately; the default panels aggregate both and say so.

### 3.2 Counters

| Meter | Tags | Incremented when |
|---|---|---|
| `nocook.events.consumed` | `outcome=opened\|not_actionable\|duplicate\|oms_unavailable\|invalid` | every delivery handled |
| `nocook.cases.opened` | — | eligible case committed |
| `nocook.cases.closed` | `state=ACKNOWLEDGED\|SUPERSEDED\|EXPIRED` | terminal transition committed |
| `nocook.acks` | `channel=TABLET\|IVR`, `choice` | A1 applied |
| `nocook.acks.rejected` | `code=ALREADY_ACKNOWLEDGED\|CASE_CLOSED\|NOT_ACTIONABLE` | A3–A5 (late ACKs) |
| `nocook.escalations` | `kind=L1\|IVR\|EXPIRY`, `outcome=fired\|blocked\|deferred\|noop` | each processed deadline |
| `nocook.ivr.reserved` | — | call_attempts insert ok |
| `nocook.ivr.duplicate_prevented` | — | unique violation on call_attempts |
| `nocook.calls` | `status` | webhook status applied |
| `nocook.lease.takeovers` | `table=deadlines\|outbox` | reaper returns an expired lease |
| `nocook.webhooks` | `kind=status\|dtmf`, `result=ok\|duplicate\|bad_signature` | webhook handled |
| `nocook.receipts` | `kind`, `client` | receipt recorded |
| `nocook.chaos` | `action` | chaos endpoint called |
| `oms.orders.created`, `oms.orders.cancelled` | — | OMS |
| `oms.events.published` | `routing_key`, `synthetic` | relay or firehose confirm/send |

### 3.3 Gauges (sampled every 5 s by `GaugeSampler`; every replica reports, dashboards use `max`)

| Meter | Source |
|---|---|
| `nocook.queue.ready` `{queue=nocook.cancellations\|nocook.cancellations.dlq}` | `RabbitAdmin.getQueueInfo(name).getMessageCount()` |
| `nocook.queue.consumers` `{queue}` | `getQueueInfo(name).getConsumerCount()` |
| `nocook.outbox.pending` | DATA_MODEL.md §3 gauge query |
| `nocook.deadlines.overdue` | same |
| `nocook.cases.open` | same |
| `nocook.listener.running` | 1/0 from `RabbitListenerEndpointRegistry` |
| `nocook.invariant.duplicate_cases` | DATA_MODEL.md §4 query, every 15 s (must be 0) |
| `nocook.invariant.max_ivr_per_case` | DATA_MODEL.md §4 query, every 15 s (must be ≤ 1) |
| `nocook.invariant.escalations_after_close` | DATA_MODEL.md §4 query, every 15 s (must be 0) |
| `oms.outbox.pending`, `oms.firehose.achieved` | mock-oms |

Plus Spring's automatic `jvm.*`, `process.cpu.usage`, `system.cpu.usage`, `hikaricp.connections.*`, `executor.*`.

## 4. Traces

- One trace per cancellation: `POST /oms/api/orders/{id}/cancel` (k6 or UI) → OMS tx → outbox relay publish (new trace linked by `traceparent` in the outbox payload headers: store `traceparent` in the outbox row and set it on the AMQP message so the consume span joins the request trace) → `nocook` consume span → `oms read` client span → JDBC → MQTT publish (manual span `mqtt.publish`, attribute `topic`).
- Store the trace id in `cases.trace_id` and on every fact (`case_events.trace_id`) so the Ops timeline links to Tempo.
- Tempo link from the p99 panel: TraceQL `{ resource.service.name = "nocook" && duration > 2s }`.
- Manual spans: `deadline.process` (attributes `deadline.kind`, `case.id`), `outbox.dispatch` (`outbox.type`).

## 5. Logs

`management.opentelemetry.logging.export.otlp.endpoint` → Loki. If OTLP log export is not working within 15 minutes, drop it (`docker compose logs` is enough); Loki is not on the judge path. Log lines carry `traceId`/`spanId` via MDC (Boot default pattern) and `caseId`.

## 6. Grafana annotations

`GrafanaAnnotator.annotate(String text, List<String> tags)` → `POST /api/annotations` `{ "time": <epoch ms>, "tags": [...], "text": "..." }`, basic auth admin. Tags: `chaos` + action for chaos; `run` + profile + runId for run start/end (written by `tools/run.mjs`). All three dashboards show annotations with tag filter `chaos` and `run`.

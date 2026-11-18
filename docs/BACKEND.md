# BACKEND.md — Maven modules, packages, classes, config

## 1. Maven layout

```
backend/
├── pom.xml                     parent: spring-boot-starter-parent 4.1.x, java.version=25, modules below
├── nocook-domain/              jar, no Spring.     deps: jspecify; test: junit-jupiter, assertj
├── nocook-service/             Spring Boot app.    deps: nocook-domain + Boot starters (backend/CLAUDE.md) + hivemq-mqtt-client
├── mock-oms/                   Spring Boot app.    deps: Boot starters (no domain module)
└── nocook-sim/                 jar with main().    deps: nocook-domain, jackson (plain, not Boot)
```

Plugins: `spring-boot-maven-plugin` (apps only), `maven-surefire-plugin` (`*Test`), `maven-failsafe-plugin` (`*IT`).

Docker: one `backend/Dockerfile` with build arg `MODULE`:

```dockerfile
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
ARG MODULE
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -pl ${MODULE} -am -DskipTests package

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
ARG MODULE
COPY --from=build /src/${MODULE}/target/*.jar /app/app.jar
ENV JAVA_TOOL_OPTIONS="-Xmx512m -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

---

## 2. `nocook-domain` (owner Q) — package `com.nocook.domain`

```
model/        CaseState, AckChoice, AckChannel, DeadlineKind, CancelInitiator, OrderStatus,
              NotActionableReason, CloseReason, CardKind, Outcome,
              CancellationEvent, OrderSnapshot, AssuranceCase, Timings
event/        CaseEvent (sealed: DeadlineDue, MerchantAck)
oms/          OmsView (sealed: Available, Unavailable, NotRead)
effect/       Effect (sealed), Fact, FactKind
policy/       EligibilityPolicy        → Optional<NotActionableReason> evaluate(CancellationEvent, OrderSnapshot, Instant now, Timings)
              CasePolicy               → OpenDecision decideOpen(...); TransitionResult transition(...)
              OpenDecision, TransitionResult
```

Every rule in `DOMAIN.md` §2–3 maps to one private method in `CasePolicy` named after its row (`d2ExpiryOrTooOld`, `d3OmsUnavailable`, …). The test class mirrors the row IDs.

---

## 3. `nocook-service` (owner B) — package `com.nocook.service`

```
NocookApplication
config/
    ClockConfig               @Bean Clock clock() → Clock.systemUTC()   (tests replace with MutableClock)
    TimingsConfig             @ConfigurationProperties("nocook.timing") → Timings
    RabbitTopologyConfig      Declarables per API.md §3; SimpleRabbitListenerContainerFactory with observation
    MqttConfig                HiveMQ Mqtt5AsyncClient bean, connect on startup, auto-reconnect
    ReplicaConfig             replicaId from REPLICA_ID; MeterRegistryCustomizer adds common tag replica
    WebConfig                 Jackson 3 JsonMapper settings (ISO dates)
ingestion/
    CancellationListener      @RabbitListener(id="cancellations", queues="nocook.cancellations") → CancellationIngestor
    CancellationIngestor      port: void ingest(CancellationEvent, Instant publishedAt)  (Kafka adapter in production)
    EventValidator            schemaVersion, required fields → reject to DLQ
oms/
    OmsReadClient             RestClient to ${OMS_BASE_URL}; get(orderId) → OmsView (Available | Unavailable(reason))
application/
    CaseOpeningService        @Transactional open(event, snapshot): decideOpen → persist case, facts, deadlines, outbox
    EscalationService         @Transactional applyDeadline(claimedDeadline, omsView)
    AckService                @Transactional ack(caseId, outletId, MerchantAck) → AckResult
    ReceiptService            records receipts, drives card timers
    EffectApplier             turns List<Effect> into repository calls (DOMAIN.md §5); used by the three services above
    TelephonyService          handles webhook status/dtmf → AckService for digits
scheduling/
    DeadlineWorker            @Scheduled(fixedDelay=250ms) tick(): claim → for each: read OMS (unless EXPIRY) → EscalationService
    LeaseReaper               @Scheduled(fixedDelay=2s) deadlines + outbox
    OutboxDispatcher          @Scheduled(fixedDelay=50ms) + wake() on afterCommit; claims batch 100, dispatches by type
    GaugeSampler              @Scheduled(fixedDelay=5s) DB gauges + RabbitAdmin.getQueueInfo()
persistence/
    CaseRepository, CaseEventRepository, DeadlineRepository, OutboxRepository,
    CallAttemptRepository, WebhookEventRepository, OpsEventRepository      (JdbcClient, SQL from DATA_MODEL.md)
notifications/
    CardPublisher             builds CardMessage / CallMessage (API.md §4), publishes QoS 1, returns on PUBACK
    ControlChannel            subscribes nocook/control → ListenerControl
    ListenerControl           RabbitListenerEndpointRegistry.getListenerContainer("cancellations").stop()/start()
telephony/
    TelephonyGateway          interface: CallResult placeCall(CallRequest); void hangup(String providerCallId)
    MockTelephonyGateway      providerCallId "mock-" + uuid; publishes INCOMING_CALL; auto-outcome for outlet-load-* (LOAD_AND_CHAOS.md §4)
    MockProviderController    /api/mock-telephony/** → builds signed webhook, POSTs it async
    WebhookSigner             HMAC-SHA256 sign/verify (constant-time compare)
api/
    AckController, ReceiptController, OutletController (open-cases), TimelineController,
    OpsController (cases, summary, runs, simulations), ChaosController, TelephonyWebhookController,
    InternalController (/internal/halt), ApiExceptionHandler
chaos/
    ToxiproxyClient           RestClient to ${TOXIPROXY_URL}
    GrafanaAnnotator          RestClient to ${GRAFANA_URL}/api/annotations (basic auth)
observability/
    NocookMeters              all meters from OBSERVABILITY.md, created once, injected where used
```

### 3.1 Consumer path (`CancellationIngestor.ingest`)

1. Validate. Parse `x-published-at` header → record `nocook.consume.lag` = now − publishedAt.
2. `OmsReadClient.get(orderId)`. If `Unavailable` → throw `OmsUnavailableException` → container nacks/requeues (counts toward delivery limit 10). Count `nocook.events.consumed{outcome="oms_unavailable"}`.
3. `CaseOpeningService.open(event, snapshot)` (one transaction). Outcomes: `opened`, `not_actionable`, `duplicate`.
4. After commit (`TransactionSynchronization.afterCommit`): record `nocook.case.open` = clock.instant() − event.occurredAt (only for `opened`); `OutboxDispatcher.wake()`.

### 3.2 Deadline worker

- `workerId = REPLICA_ID + "-" + uuid4 short`. Batch 50, lease 10 s, tick 250 ms.
- OMS reads happen **outside** the DB transaction (don't hold row locks during HTTP).
- Per claimed deadline, one transaction: lock case → `CasePolicy.transition` → `EffectApplier` → set deadline `DONE` (APPLIED/NO_OP) or `PENDING` with new `due_at` (DEFERRED).
- After commit: `nocook.deadline.lateness{kind}` = commit instant − due_at (APPLIED only); `nocook.escalations{kind, outcome}`.
- Exceptions: deadline stays LEASED; the reaper returns it after lease expiry. Log ERROR with caseId.

### 3.3 Outbox dispatcher

- Claim like deadlines (`status='PENDING' and available_at <= :now`, batch 100, lease 10 s).
- `MQTT_CARD` / `MQTT_CALL`: publish QoS 1, await PUBACK (timeout 2 s) → `SENT` + fact `CARD_DISPATCHED`. Failure → `PENDING`, `available_at = now + 500 ms × attempts`, after 10 attempts `FAILED`.
- `PLACE_CALL`: `SELECT state FROM cases` → closed ⇒ `call_attempts.status='CANCELLED'`, fact `CALL_CANCELLED`, row `SENT`. Open ⇒ `gateway.placeCall()` → `call_attempts` `DIALING` + `provider_call_id`, fact `CALL_DIALING`, row `SENT`. Gateway timeout ⇒ status `UNKNOWN`, **no retry** (UNKNOWN beats a duplicate call).

### 3.4 ACK path

`AckController` → `AckService.ack` (transaction: lock case → transition(MerchantAck) → EffectApplier → update `ack_*` columns) → map Outcome to HTTP per API.md §1.1 → after commit wake dispatcher, counter `nocook.acks{channel, choice}`.

### 3.5 `application.yml` (nocook-service)

```yaml
spring:
  application.name: nocook
  threads.virtual.enabled: true
  datasource:
    url: ${NOCOOK_DB_URL:jdbc:postgresql://toxiproxy:15432/nocook}
    username: nocook_app
    password: nocook
    hikari: { maximum-pool-size: 20, connection-timeout: 2000 }
  flyway: { schemas: nocook, default-schema: nocook, locations: classpath:db/migration/nocook }
  rabbitmq:
    host: ${RABBIT_HOST:rabbitmq}
    username: nocook
    password: nocook
    listener.simple: { acknowledge-mode: auto, prefetch: 50, concurrency: 4, max-concurrency: 8,
                       default-requeue-rejected: true, observation-enabled: true }
    template.observation-enabled: true

nocook:
  replica-id: ${REPLICA_ID:local}
  timing: { l1: 30s, ivr: 75s, max-age: 10m, defer-by: 2s }
  deadlines: { tick: 250ms, batch: 50, lease: 10s }
  outbox: { tick: 50ms, batch: 100, lease: 10s }
  oms: { base-url: ${OMS_BASE_URL:http://toxiproxy:18090}, connect-timeout: 300ms, read-timeout: 800ms }
  mqtt: { host: ${MQTT_HOST:mosquitto}, port: 1883 }
  telephony: { secret: ${MOCK_TELEPHONY_SECRET:dev-secret}, self-base-url: http://localhost:8080,
               auto-outcome-outlet-prefix: outlet-load- }
  chaos: { toxiproxy-url: ${TOXIPROXY_URL:http://toxiproxy:8474}, grafana-url: ${GRAFANA_URL:http://lgtm:3000},
           grafana-user: admin, grafana-password: admin, internal-token: ${INTERNAL_TOKEN:dev-internal} }
  results-dir: /results

management:
  endpoints.web.exposure.include: health,info,metrics
  otlp.metrics.export: { url: ${OTLP_HTTP:http://lgtm:4318}/v1/metrics, step: 5s, base-time-unit: seconds }
  opentelemetry:
    tracing.export.otlp.endpoint: ${OTLP_HTTP:http://lgtm:4318}/v1/traces
    logging.export.otlp.endpoint: ${OTLP_HTTP:http://lgtm:4318}/v1/logs
  tracing.sampling.probability: ${TRACING_SAMPLE:1.0}
  metrics.distribution:            # see OBSERVABILITY.md §3 for the full list
    # Map keys containing dots MUST use bracket notation in YAML, or Spring splits them into nested keys.
    percentiles-histogram:
      "[nocook]": true
      "[http.server.requests]": true
    slo:
      "[nocook.case.open]": 300ms,2s,3s
      "[nocook.card.delivery]": 300ms,2s,3s
      "[nocook.card.render]": 300ms,2s,3s
      "[nocook.ack.fanout]": 300ms,2s,3s
      "[nocook.deadline.lateness]": 300ms,2s,3s
      "[nocook.consume.lag]": 300ms,2s,3s
      "[http.server.requests]": 100ms,300ms,1s
    minimum-expected-value:
      "[nocook]": 1ms
      "[http.server.requests]": 1ms
    maximum-expected-value:
      "[nocook]": 30s
      "[http.server.requests]": 5s
```

Verify at G2 that buckets exist: `curl -s localhost:9090/api/v1/series --data-urlencode 'match[]=nocook_case_open_seconds_bucket' | jq '.data[].le' | sort -u` must include `"0.3"`, `"2.0"` (or `"2"`), `"3.0"`.

`self-base-url` is used by the mock provider to call its own webhook; inside Docker set it to `http://localhost:8080` (same container).

---

## 4. `mock-oms` (owner B; `firehose` and `testsupport` packages owner Q) — package `com.nocook.oms`

```
MockOmsApplication
config/       ClockConfig, RabbitTopologyConfig (same Declarables), FirehoseRabbitConfig (separate CachingConnectionFactory + RabbitTemplate, observation OFF, confirms OFF)
orders/       OrderController, OrderService (@Transactional: mutate + outbox), OrderRepository, OrderSnapshot
outbox/       OutboxRelay (@Scheduled 20 ms + wake() afterCommit; batch 200; confirms), OutboxRepository
load/         LoadController (/oms/api/load/accepted-orders)
firehose/     FirehoseController, FirehoseService (virtual-thread publishers; token-bucket pacing; outlets outlet-load-0001..0500; routing keys excluding order.cancelled)
testsupport/  ReplayController (replay-cancellations), RaceController (ready-then-cancel), ReinstateController
```

`application.yml` (mock-oms): port 8090, `spring.application.name=mock-oms`, datasource direct to `postgres:5432` as `oms_app`, `publisher-confirm-type: simple`, `template.observation-enabled: true`, same OTLP block, `management.metrics.distribution.percentiles-histogram.oms=true`.

Firehose pacing: `eventsPerSecond` split across `max(4, eps/2000)` virtual threads; each sleeps to its own schedule (no coordinated catch-up bursts); publishes ~300-byte JSON with `synthetic=true`. Gauge `oms.firehose.achieved` (events/s over last 5 s).

---

## 5. `nocook-sim` (owner Q) — see `SIMULATION.md`

```
SimMain             args: --seed 2026 --n 10000 --assumptions sim/assumptions-v1.json --out results/sim-<ts>
ScenarioGenerator   seeded SplittableRandom
KitchenModel        stage timeline + cost function
ResponseModel       baseline discovery, card/L1/IVR responses
PairRunner          runs baseline and NoCook per scenario; NoCook path drives CasePolicy with virtual Instants
Aggregator          distributions, ₹, funnel, sensitivity → sim-summary.json, scenarios.csv
```

# DATA_MODEL.md — PostgreSQL 18 schemas

One database `nocook`, two schemas, two roles. Each app runs Flyway only for its own schema.

| Schema | Owner role | Used by | Flyway location |
|---|---|---|---|
| `oms` | `oms_app` | mock-oms | `backend/mock-oms/src/main/resources/db/migration/oms` |
| `nocook` | `nocook_app` | nocook-service | `backend/nocook-service/src/main/resources/db/migration/nocook` |

`nocook_app` has **no** privileges on `oms` (invariant I7). The export tooling connects as `postgres` to run cross-schema invariant checks.

All timestamps are `timestamptz`. Decision queries take `:now` from the app's `Clock`; defaults like `clock_timestamp()` are only for audit columns.

---

## 0. `infra/postgres/init.sql` (runs once on an empty volume)

```sql
create role oms_app    login password 'oms';
create role nocook_app login password 'nocook';

create schema oms    authorization oms_app;
create schema nocook authorization nocook_app;

revoke all on schema oms    from public;
revoke all on schema nocook from public;
-- deliberately no grant on schema oms to nocook_app
```

Compose sets `POSTGRES_DB=nocook`, `POSTGRES_PASSWORD=postgres`, and mounts this file at `/docker-entrypoint-initdb.d/01-init.sql`.

Spring config per app:

```properties
# mock-oms
spring.datasource.url=jdbc:postgresql://postgres:5432/nocook
spring.datasource.username=oms_app
spring.flyway.schemas=oms
spring.flyway.default-schema=oms
spring.flyway.locations=classpath:db/migration/oms

# nocook-service (through Toxiproxy)
spring.datasource.url=jdbc:postgresql://toxiproxy:15432/nocook
spring.datasource.username=nocook_app
spring.flyway.schemas=nocook
spring.flyway.default-schema=nocook
spring.flyway.locations=classpath:db/migration/nocook
```

---

## 1. Schema `oms` — `V1__oms.sql`

```sql
create sequence oms.order_seq;

create table oms.orders (
    id               text primary key,                     -- 'ord-' || lpad(nextval('oms.order_seq')::text, 8, '0')
    outlet_id        text        not null,
    version          int         not null default 1,
    status           text        not null check (status in
                       ('PLACED','ACCEPTED','PREPARING','READY','PICKED_UP','DELIVERED','CANCELLED')),
    stage            text        not null default 'NOT_STARTED'
                       check (stage in ('NOT_STARTED','PREP','OVEN','READY')),
    value_paise      bigint      not null default 0,
    order_ref        text        not null,                 -- last 4 digits of id, read out by IVR
    items            jsonb       not null default '[]'::jsonb,
    accepted_at      timestamptz,
    ready_at         timestamptz,
    picked_up_at     timestamptz,
    delivered_at     timestamptz,
    cancelled_at     timestamptz,
    cancel_initiator text check (cancel_initiator in ('CUSTOMER','RESTAURANT','PLATFORM')),
    run_id           text,                                 -- from X-Run-Id header (k6), null for manual orders
    created_at       timestamptz not null,
    updated_at       timestamptz not null
);

create index orders_outlet_active on oms.orders (outlet_id, updated_at desc)
    where status not in ('DELIVERED','CANCELLED');
create index orders_run on oms.orders (run_id) where run_id is not null;

create table oms.outbox (
    id          uuid primary key default uuidv7(),
    event_id    uuid        not null unique,               -- becomes AMQP message-id and payload eventId
    routing_key text        not null,                      -- 'order.cancelled', 'order.accepted', …
    payload     jsonb       not null,
    status      text        not null default 'PENDING' check (status in ('PENDING','SENT')),
    created_at  timestamptz not null default clock_timestamp(),
    sent_at     timestamptz
);

create index oms_outbox_pending on oms.outbox (created_at) where status = 'PENDING';
```

Every order mutation is one transaction: `UPDATE orders SET …, version = version + 1, updated_at = :now` + `INSERT INTO outbox`. The event's `occurredAt` = the `:now` used in that transaction.

Status rules (mock-oms enforces; 409 on violation):

| From | Allowed to |
|---|---|
| PLACED | ACCEPTED, CANCELLED |
| ACCEPTED | PREPARING, READY, CANCELLED |
| PREPARING | READY, CANCELLED |
| READY | PICKED_UP, CANCELLED |
| PICKED_UP | DELIVERED, CANCELLED |
| DELIVERED, CANCELLED | — (test endpoint `reinstate` may move CANCELLED → ACCEPTED for the SUPERSEDED demo) |

`stage` changes (`NOT_STARTED → PREP → OVEN → READY`) bump `version` and set `status=PREPARING` (or `READY`, setting `ready_at`).

---

## 2. Schema `nocook` — `V1__nocook.sql`

```sql
create table nocook.cases (
    id                  uuid primary key default uuidv7(),
    order_id            text        not null,
    order_version       int         not null,
    outlet_id           text        not null,
    state               text        not null check (state in
                          ('NOT_ACTIONABLE','AWAITING_ACK','ESCALATED_L1','ESCALATED_IVR',
                           'ACKNOWLEDGED','SUPERSEDED','EXPIRED')),
    version             int         not null default 0,
    not_actionable_reason text,
    cancelled_at        timestamptz not null,              -- OMS commit time from the event
    opened_at           timestamptz not null,              -- NoCook commit time (Clock)
    closed_at           timestamptz,
    close_reason        text check (close_reason in
                          ('ACKNOWLEDGED','ORDER_REINSTATED','ORDER_READY','ORDER_PICKED_UP','EXPIRED')),
    ack_choice          text check (ack_choice in ('STOPPED','NOT_STARTED','ALREADY_COOKED')),
    ack_channel         text check (ack_channel in ('TABLET','IVR')),
    ack_actor           text,
    ack_idempotency_key text,
    acked_at            timestamptz,
    order_ref           text        not null,
    event_id            uuid        not null,
    run_id              text,
    trace_id            text,
    constraint cases_order_version_uq unique (order_id, order_version)
);

create unique index cases_one_open_per_order on nocook.cases (order_id)
    where state in ('AWAITING_ACK','ESCALATED_L1','ESCALATED_IVR');
create index cases_outlet_open on nocook.cases (outlet_id)
    where state in ('AWAITING_ACK','ESCALATED_L1','ESCALATED_IVR');
create index cases_opened_at on nocook.cases (opened_at desc);
create index cases_run on nocook.cases (run_id) where run_id is not null;

create table nocook.case_events (
    id         uuid primary key default uuidv7(),
    case_id    uuid        not null references nocook.cases (id),
    kind       text        not null,                       -- FactKind, see DOMAIN.md §1
    server_at  timestamptz not null,                       -- Clock instant inside the writing tx
    source_at  timestamptz,                                -- device/provider-reported time, informational
    channel    text,                                       -- TABLET | IVR | SYSTEM | OPS
    device_id  text,
    metadata   jsonb       not null default '{}'::jsonb,
    trace_id   text
);

create index case_events_timeline on nocook.case_events (case_id, server_at);
create index case_events_kind_time on nocook.case_events (kind, server_at);

create table nocook.deadlines (
    id          uuid primary key default uuidv7(),
    case_id     uuid        not null references nocook.cases (id),
    kind        text        not null check (kind in ('L1','IVR','EXPIRY')),
    due_at      timestamptz not null,
    status      text        not null default 'PENDING'
                  check (status in ('PENDING','LEASED','DONE','CANCELLED')),
    lease_owner text,
    lease_until timestamptz,
    attempts    int         not null default 0,
    last_error  text,
    done_at     timestamptz,
    constraint deadlines_case_kind_uq unique (case_id, kind)
);

create index deadlines_due   on nocook.deadlines (due_at)      where status = 'PENDING';
create index deadlines_lease on nocook.deadlines (lease_until) where status = 'LEASED';

create table nocook.outbox (
    id           uuid primary key default uuidv7(),
    action_key   text        not null,
    type         text        not null check (type in ('MQTT_CARD','MQTT_CALL','PLACE_CALL')),
    case_id      uuid        not null references nocook.cases (id),
    payload      jsonb       not null,
    status       text        not null default 'PENDING'
                   check (status in ('PENDING','LEASED','SENT','FAILED')),
    attempts     int         not null default 0,
    available_at timestamptz not null,
    lease_owner  text,
    lease_until  timestamptz,
    created_at   timestamptz not null,
    sent_at      timestamptz,
    last_error   text,
    constraint outbox_action_key_uq unique (action_key)
);

create index outbox_ready on nocook.outbox (available_at) where status = 'PENDING';
create index outbox_lease on nocook.outbox (lease_until)  where status = 'LEASED';

create table nocook.call_attempts (
    id               uuid primary key default uuidv7(),
    case_id          uuid        not null references nocook.cases (id),
    provider         text        not null default 'MOCK',
    provider_call_id text unique,
    status           text        not null check (status in
                       ('RESERVED','DIALING','RINGING','ANSWERED','COMPLETED',
                        'NO_ANSWER','BUSY','FAILED','UNKNOWN','CANCELLED')),
    outcome_digit    text,
    reserved_at      timestamptz not null,
    dialed_at        timestamptz,
    answered_at      timestamptz,
    ended_at         timestamptz,
    constraint call_attempts_case_uq unique (case_id)        -- I3: one IVR attempt per case
);

create table nocook.webhook_events (
    provider_event_id text primary key,                    -- dedupe of provider callbacks
    provider_call_id  text        not null,
    kind              text        not null,                -- STATUS | DTMF
    received_at       timestamptz not null
);

create table nocook.ops_events (
    id        uuid primary key default uuidv7(),
    kind      text        not null,                        -- chaos action name, RUN_START, RUN_END
    at        timestamptz not null,
    replica   text,
    metadata  jsonb       not null default '{}'::jsonb
);
```

---

## 3. Key SQL (put these verbatim in the repositories)

**Claim due deadlines** (`DeadlineRepository.claimDue`)

```sql
with due as (
    select id
    from nocook.deadlines
    where status = 'PENDING' and due_at <= :now
    order by due_at
    limit :batchSize
    for update skip locked
)
update nocook.deadlines d
set status = 'LEASED', lease_owner = :workerId, lease_until = :leaseUntil, attempts = d.attempts + 1
from due
where d.id = due.id
returning d.id, d.case_id, d.kind, d.due_at, d.attempts;
```

**Reap expired leases** (`DeadlineRepository.reapExpiredLeases`; `OutboxRepository` uses the same shape). The CTE captures the previous owner for the `LEASE_TAKEOVER` fact, because `RETURNING` on the update would only see the new (null) owner:

```sql
with expired as (
    select id, case_id, kind, lease_owner as previous_owner
    from nocook.deadlines
    where status = 'LEASED' and lease_until < :now
    for update skip locked
)
update nocook.deadlines d
set status = 'PENDING', lease_owner = null, lease_until = null
from expired
where d.id = expired.id
returning expired.case_id, expired.kind, expired.previous_owner;
```

**Lock the case for a transition**

```sql
select * from nocook.cases where id = :caseId for update;
```

**Open a case** (idempotent)

```sql
insert into nocook.cases (order_id, order_version, outlet_id, state, not_actionable_reason, cancelled_at,
                          opened_at, closed_at, order_ref, event_id, run_id, trace_id)
values (…)
on conflict (order_id, order_version) do nothing
returning id;
```

No row returned → duplicate: look up the existing case id, insert `DUPLICATE_EVENT` fact, increment `nocook.events.consumed{outcome=duplicate}`. A violation of `cases_one_open_per_order` (a newer version cancelled while the old case is open; should not happen in OMS rules) → treat the same as duplicate and log WARN.

**Gauges** (one query every 5 s, one replica is enough but all may run it)

```sql
select
  (select count(*) from nocook.outbox    where status in ('PENDING','LEASED'))                       as outbox_pending,
  (select count(*) from nocook.deadlines where status = 'PENDING' and due_at < :now - interval '1 second') as deadlines_overdue,
  (select count(*) from nocook.cases     where state in ('AWAITING_ACK','ESCALATED_L1','ESCALATED_IVR')) as cases_open;
```

---

## 4. Invariant checks (run by `tools/export-run.mjs` as `postgres`, see OUTPUT.md)

```sql
-- duplicate_cases (must be 0)
select count(*) from (select order_id, order_version from nocook.cases group by 1, 2 having count(*) > 1) d;

-- max_ivr_per_case (must be ≤ 1)
select coalesce(max(c), 0) from (select case_id, count(*) c from nocook.call_attempts group by 1) x;

-- escalations_after_close (must be 0)
select count(*) from nocook.case_events e join nocook.cases c on c.id = e.case_id
where e.kind in ('L1_TRIGGERED','IVR_ESCALATED','IVR_RESERVED') and c.closed_at is not null and e.server_at > c.closed_at;

-- escalations_after_max_age (must be 0)
select count(*) from nocook.case_events e join nocook.cases c on c.id = e.case_id
where e.kind in ('L1_TRIGGERED','IVR_ESCALATED') and e.server_at >= c.cancelled_at + interval '10 minutes';

-- escalations_on_not_actionable (must be 0)
select count(*) from nocook.case_events e join nocook.cases c on c.id = e.case_id
where c.state = 'NOT_ACTIONABLE' and e.kind in ('L1_TRIGGERED','IVR_ESCALATED','IVR_RESERVED');

-- lost_cases for a run (must be 0): every cancelled order of the run has a case
select count(*) from oms.orders o
where o.run_id = :runId and o.status = 'CANCELLED'
  and not exists (select 1 from nocook.cases c where c.order_id = o.id);

-- stuck_open (must be 0 after run end + 11 min, or reported with ages)
select count(*) from nocook.cases where state in ('AWAITING_ACK','ESCALATED_L1','ESCALATED_IVR')
  and cancelled_at < :now - interval '10 minutes 30 seconds';

-- outbox_stuck (must be 0 at export)
select count(*) from nocook.outbox where status in ('PENDING','LEASED') and created_at < :now - interval '30 seconds';
```

## 5. Reset (`make demo-reset`)

```sql
truncate nocook.case_events, nocook.deadlines, nocook.outbox, nocook.call_attempts,
         nocook.webhook_events, nocook.ops_events, nocook.cases;
truncate oms.outbox, oms.orders;
```

Plus purge `nocook.cancellations` and `nocook.cancellations.dlq` via the RabbitMQ HTTP API. Results in `results/` are kept.

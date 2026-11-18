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

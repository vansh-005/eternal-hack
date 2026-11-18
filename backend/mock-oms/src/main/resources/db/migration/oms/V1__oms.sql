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

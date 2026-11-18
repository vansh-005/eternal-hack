# DOMAIN.md — state machine, eligibility, invariants

**Highest-authority spec.** Lives in `backend/nocook-domain` (package `com.nocook.domain`). Pure Java: no Spring, no I/O, no clock reads. Every function takes `now` as a parameter. The simulator (`nocook-sim`) calls the same functions, so the A/B study and the live system cannot drift.

---

## 1. Types

```java
public enum CaseState {
    NOT_ACTIONABLE, AWAITING_ACK, ESCALATED_L1, ESCALATED_IVR, ACKNOWLEDGED, SUPERSEDED, EXPIRED;
    public boolean isOpen() { return this == AWAITING_ACK || this == ESCALATED_L1 || this == ESCALATED_IVR; }
}
public enum AckChoice { STOPPED, NOT_STARTED, ALREADY_COOKED;            // DTMF 1, 2, 3
    public static Optional<AckChoice> fromDigit(String digit) { … } }
public enum AckChannel { TABLET, IVR }
public enum DeadlineKind { L1, IVR, EXPIRY }
public enum CancelInitiator { CUSTOMER, RESTAURANT, PLATFORM }
public enum OrderStatus { PLACED, ACCEPTED, PREPARING, READY, PICKED_UP, DELIVERED, CANCELLED }
public enum NotActionableReason { STALE_EVENT, NOT_ACCEPTED_BEFORE_CANCEL, RESTAURANT_INITIATED, READY_OR_PICKED_UP, TOO_OLD }
public enum CloseReason { ACKNOWLEDGED, ORDER_REINSTATED, ORDER_READY, ORDER_PICKED_UP, EXPIRED }
public enum CardKind { OPEN, TAKEOVER, CLOSE }
public enum Outcome { APPLIED, NO_OP, DEFERRED, REJECTED }

public record CancellationEvent(String eventId, String orderId, String outletId, int orderVersion,
                                Instant cancelledAt, CancelInitiator initiatedBy, @Nullable String runId) {}

public record OrderSnapshot(String orderId, String outletId, int version, OrderStatus status, String stage,
                            @Nullable Instant acceptedAt, @Nullable Instant readyAt, @Nullable Instant pickedUpAt,
                            @Nullable Instant cancelledAt, @Nullable CancelInitiator cancelInitiator,
                            long valuePaise, String orderRef) {}

public record AssuranceCase(UUID id, String orderId, int orderVersion, String outletId, CaseState state, int version,
                            Instant cancelledAt, Instant openedAt, @Nullable Instant closedAt,
                            @Nullable CloseReason closeReason, @Nullable String ackIdempotencyKey) {}

public sealed interface OmsView {
    record Available(OrderSnapshot snapshot) implements OmsView {}
    record Unavailable(String reason) implements OmsView {}        // timeout, 5xx, connection refused
    record NotRead() implements OmsView {}                         // ACK and EXPIRY don't need a read
}

public sealed interface CaseEvent {
    record DeadlineDue(DeadlineKind kind, Instant dueAt) implements CaseEvent {}
    record MerchantAck(AckChoice choice, AckChannel channel, String actor, String idempotencyKey) implements CaseEvent {}
}

public sealed interface Effect {
    record ScheduleDeadline(DeadlineKind kind, Instant dueAt) implements Effect {}
    record RescheduleDeadline(DeadlineKind kind, Instant dueAt) implements Effect {}   // deferral
    record CancelPendingDeadlines() implements Effect {}                               // all PENDING for case
    record SendCard(CardKind kind) implements Effect {}
    record ReserveIvr() implements Effect {}
    record EndCall() implements Effect {}
}

public record Fact(FactKind kind, Map<String, String> metadata) {}

public record Timings(Duration l1, Duration ivr, Duration maxAge, Duration deferBy) {
    public static Timings defaults() { return new Timings(Duration.ofSeconds(30), Duration.ofSeconds(75),
                                                          Duration.ofMinutes(10), Duration.ofSeconds(2)); }
}

public record OpenDecision(CaseState initialState, @Nullable NotActionableReason reason,
                           List<Fact> facts, List<Effect> effects) {}

public record TransitionResult(Outcome outcome, CaseState nextState, @Nullable CloseReason closeReason,
                               List<Fact> facts, List<Effect> effects, @Nullable String rejectionCode) {}
```

### FactKind (complete list; `case_events.kind` uses these strings)

| Kind | Written by | Metadata keys |
|---|---|---|
| `CASE_OPENED` | domain | `eligible=true`, `eventId` |
| `NOT_ACTIONABLE` | domain | `reason` |
| `DUPLICATE_EVENT` | service (unique violation on open) | `eventId` |
| `CARD_DISPATCHED` | service (dispatcher, after MQTT PUBACK) | `cardKind`, `messageId` |
| `CARD_DELIVERED` | service (receipt) | `deviceId`, `messageId` |
| `CARD_RENDERED` | service (receipt) | `deviceId`, `messageId` |
| `CARD_DISMISSED` | service (receipt) | `deviceId`, `messageId` |
| `L1_TRIGGERED` | domain | `dueAt` |
| `IVR_ESCALATED` | domain | `dueAt` |
| `IVR_RESERVED` | service (call_attempts insert ok) | `callAttemptId` |
| `IVR_DUPLICATE_PREVENTED` | service (unique violation) | — |
| `CALL_DIALING` / `CALL_RINGING` / `CALL_ANSWERED` / `CALL_COMPLETED` / `CALL_NO_ANSWER` / `CALL_BUSY` / `CALL_FAILED` / `CALL_UNKNOWN` | service (webhook) | `providerCallId`, `providerEventId` |
| `CALL_CANCELLED` | service (dispatcher found case closed) | `providerCallId?` |
| `TABLET_ACKNOWLEDGED` | domain | `choice`, `actor` (deviceId), `idempotencyKey` |
| `DTMF_ACKNOWLEDGED` | domain | `choice`, `actor` (providerCallId), `digit` |
| `LATE_ACK_IGNORED` | domain | `state`, `channel` |
| `ESCALATION_DEFERRED` | domain | `kind`, `reason=OMS_UNAVAILABLE`, `retryAt` |
| `ESCALATION_BLOCKED` | domain | `kind`, `reason` (a CloseReason) |
| `ORDER_VERSION_ADVANCED` | domain | `from`, `to` |
| `CASE_SUPERSEDED` | domain | `reason` |
| `CASE_EXPIRED` | domain | — |
| `CASE_CLOSED` | domain | `reason` |
| `LEASE_TAKEOVER` | service (reaper) | `deadlineKind`, `previousOwner` |

---

## 2. Opening a case — `CasePolicy.decideOpen(CancellationEvent e, OrderSnapshot s, Instant now, Timings t)`

Called after the consumer has done a **fresh read** of the order. Evaluate rules **in this order**; first failure wins and yields `NOT_ACTIONABLE`:

| # | Rule (eligible only if true) | Failure reason |
|---|---|---|
| 1 | `s.status == CANCELLED` | `STALE_EVENT` (order was reinstated or event is out of date) |
| 2 | `s.acceptedAt != null && s.acceptedAt < e.cancelledAt` | `NOT_ACCEPTED_BEFORE_CANCEL` |
| 3 | initiator (`s.cancelInitiator`, else `e.initiatedBy`) `!= RESTAURANT` | `RESTAURANT_INITIATED` |
| 4 | `s.readyAt == null && s.pickedUpAt == null` | `READY_OR_PICKED_UP` (nothing left to save, before or after cancel) |
| 5 | `now − e.cancelledAt < t.maxAge` | `TOO_OLD` |

**Eligible →**
- `initialState = AWAITING_ACK`
- facts: `CASE_OPENED{eligible=true, eventId}`
- effects, in order: `ScheduleDeadline(L1, e.cancelledAt + t.l1)`, `ScheduleDeadline(IVR, e.cancelledAt + t.ivr)`, `ScheduleDeadline(EXPIRY, e.cancelledAt + t.maxAge)`, `SendCard(OPEN)`

**Not eligible →** `initialState = NOT_ACTIONABLE`, fact `NOT_ACTIONABLE{reason}`, no effects. The row is still inserted (audit + dedupe), with `closed_at = now`.

Deadlines are anchored to **`cancelledAt`** (OMS commit time, authoritative), not to when NoCook processed the event. If NoCook is behind, an overdue deadline fires on the next worker tick. That is correct: the kitchen clock started at cancel.

---

## 3. Transitions — `CasePolicy.transition(AssuranceCase c, CaseEvent ev, OmsView oms, Instant now, Timings t)`

Every `APPLIED` result increments `c.version` by 1 (done by the service when persisting).

### 3.1 `DeadlineDue(kind, dueAt)`

Evaluate top to bottom; the first matching row decides.

| # | Condition | Outcome | Next state | Facts | Effects |
|---|---|---|---|---|---|
| D1 | `!c.state.isOpen()` | `NO_OP` | unchanged | — | — |
| D2 | `kind == EXPIRY` **or** `now − c.cancelledAt ≥ t.maxAge` | `APPLIED` | `EXPIRED` (closeReason `EXPIRED`) | `CASE_EXPIRED`, `CASE_CLOSED{reason=EXPIRED}` | `CancelPendingDeadlines`, `SendCard(CLOSE)`, `EndCall` if state was `ESCALATED_IVR` |
| D3 | `oms instanceof Unavailable` | `DEFERRED` | unchanged | `ESCALATION_DEFERRED{kind, reason=OMS_UNAVAILABLE, retryAt=now+t.deferBy}` | `RescheduleDeadline(kind, now + t.deferBy)` |
| D4 | snapshot `status != CANCELLED` | `APPLIED` | `SUPERSEDED` (`ORDER_REINSTATED`) | `ESCALATION_BLOCKED{kind, reason}`, `CASE_SUPERSEDED{reason}`, `CASE_CLOSED{reason}` | `CancelPendingDeadlines`, `SendCard(CLOSE)` |
| D5 | snapshot `pickedUpAt != null` | `APPLIED` | `SUPERSEDED` (`ORDER_PICKED_UP`) | same as D4 | same as D4 |
| D6 | snapshot `readyAt != null` | `APPLIED` | `SUPERSEDED` (`ORDER_READY`) | same as D4 | same as D4 |
| D7 | `kind == L1 && c.state == AWAITING_ACK` | `APPLIED` | `ESCALATED_L1` | `L1_TRIGGERED{dueAt}` (+ `ORDER_VERSION_ADVANCED` if `snapshot.version > c.orderVersion`) | `SendCard(TAKEOVER)` |
| D8 | `kind == L1` (state already L1/IVR) | `NO_OP` | unchanged | — | — |
| D9 | `kind == IVR && c.state ∈ {AWAITING_ACK, ESCALATED_L1}` | `APPLIED` | `ESCALATED_IVR` | `IVR_ESCALATED{dueAt}` (+ `ORDER_VERSION_ADVANCED` if applicable) | `CancelPendingDeadlines` is **not** emitted; emit `ReserveIvr` only. (The service marks a still-pending L1 deadline `CANCELLED`.) |
| D10 | `kind == IVR && c.state == ESCALATED_IVR` | `NO_OP` | unchanged | — | — |

D3 sits **after** D2 on purpose: expiry never needs OMS, and a case past 10 minutes must close even if OMS is down.

A version bump alone (snapshot `version > c.orderVersion`, still `CANCELLED`, not ready, not picked up) does **not** supersede; it records `ORDER_VERSION_ADVANCED` and escalation continues.

### 3.2 `MerchantAck(choice, channel, actor, idempotencyKey)`

ACK never needs an OMS read (`OmsView.NotRead`). Recording a human response is always safe.

| # | Condition | Outcome | Next state | Facts | Effects | HTTP (see API.md) |
|---|---|---|---|---|---|---|
| A1 | `c.state.isOpen()` | `APPLIED` | `ACKNOWLEDGED` (closeReason `ACKNOWLEDGED`) | `TABLET_ACKNOWLEDGED` or `DTMF_ACKNOWLEDGED` `{choice, actor, …}`, `CASE_CLOSED{reason=ACKNOWLEDGED}` | `CancelPendingDeadlines`, `SendCard(CLOSE)`, `EndCall` if state was `ESCALATED_IVR` | 200 |
| A2 | `c.state == ACKNOWLEDGED && idempotencyKey.equals(c.ackIdempotencyKey)` | `NO_OP` | unchanged | — | — | 200, `duplicate=true` |
| A3 | `c.state == ACKNOWLEDGED` (different key) | `REJECTED` `ALREADY_ACKNOWLEDGED` | unchanged | `LATE_ACK_IGNORED{state, channel}` | — | 409 |
| A4 | `c.state ∈ {SUPERSEDED, EXPIRED}` | `REJECTED` `CASE_CLOSED` | unchanged | `LATE_ACK_IGNORED{state, channel}` | — | 409 |
| A5 | `c.state == NOT_ACTIONABLE` | `REJECTED` `NOT_ACTIONABLE` | unchanged | — | — | 409 |

ACK is **not** gated on the case version the tablet saw. A card shown at v1 must still be acknowledgeable after L1 bumps the case to v2. `caseVersionSeen` is stored as metadata only.

### 3.3 State diagram

```
           decideOpen ──eligible──► AWAITING_ACK ──L1 due──► ESCALATED_L1 ──IVR due──► ESCALATED_IVR
               │                       │   ╲                    │                          │
          not eligible                 │    ╲──IVR due (L1 deferred)────────────────────►  │
               ▼                       ▼                        ▼                          ▼
        NOT_ACTIONABLE        any open state ──ACK──► ACKNOWLEDGED
                              any open state ──OMS shows reinstated / ready / picked up at an escalation──► SUPERSEDED
                              any open state ──EXPIRY due or age ≥ 10 min──► EXPIRED
```

Terminal states: `NOT_ACTIONABLE`, `ACKNOWLEDGED`, `SUPERSEDED`, `EXPIRED`.

---

## 4. Invariants (each one has a test and a dashboard signal)

| ID | Invariant | Enforced by | Test (TESTING.md) | Live signal |
|---|---|---|---|---|
| I1 | At most one case per `(order_id, order_version)` | `UNIQUE` constraint | R1 duplicate storm | `nocook_events_consumed_total{outcome="duplicate"}` vs `nocook_cases_opened_total` |
| I2 | At most one open case per order | partial unique index | R1 | invariant export |
| I3 | At most one IVR attempt per case | `UNIQUE(call_attempts.case_id)` | R3 ACK-vs-IVR | `nocook_ivr_duplicate_prevented_total`; export `max_ivr_per_case ≤ 1` |
| I4 | No escalation on a closed case | `SELECT … FOR UPDATE` + D1 | R3, R8 | export `escalations_after_close = 0` |
| I5 | No escalation without a fresh OMS read | D3 + `OmsReadClient` call inside the deadline path | R5 | `nocook_escalations_total{outcome="deferred"}` |
| I6 | No escalation after 10 min | D2 | R8 | export `escalations_after_max_age = 0` |
| I7 | NoCook never writes OMS | DB role grants; no OMS write client exists | R11 (role test) | — |
| I8 | Every side effect has a durable intent | outbox written in the transition transaction | R9 crash-after-commit | `nocook_outbox_pending` returns to 0 |
| I9 | No lost cancellations | container ack only after commit; quorum queue; DLQ | R12 kill during consume | export `lost_cases = 0` |
| I10 | Transport receipts never close a case | only A1 closes as `ACKNOWLEDGED` | API test | — |

---

## 5. What the service does with effects (contract for `nocook-service`)

Inside the **same transaction** that persists the transition:

| Effect | Service action |
|---|---|
| `ScheduleDeadline(kind, dueAt)` | `INSERT INTO deadlines (case_id, kind, due_at) … ON CONFLICT (case_id, kind) DO NOTHING` |
| `RescheduleDeadline(kind, dueAt)` | `UPDATE deadlines SET status='PENDING', due_at=:dueAt, lease_owner=NULL, lease_until=NULL WHERE case_id=… AND kind=…` |
| `CancelPendingDeadlines` | `UPDATE deadlines SET status='CANCELLED' WHERE case_id=… AND status IN ('PENDING','LEASED')` (excluding the deadline currently being processed, which the worker marks `DONE`) |
| `SendCard(kind)` | outbox row `type=MQTT_CARD`, `action_key = card:{caseId}:{kind}:{caseVersionAfter}` |
| `ReserveIvr` | `INSERT call_attempts (case_id, status='RESERVED')`; on success fact `IVR_RESERVED` + outbox `PLACE_CALL` with `action_key = ivr:{caseId}`; on unique violation fact `IVR_DUPLICATE_PREVENTED`, counter++, no outbox |
| `EndCall` | outbox row `type=MQTT_CALL` (`CALL_ENDED`), `action_key = callend:{caseId}` |

Facts → `INSERT INTO case_events`. Case row → `UPDATE cases SET state, version = version + 1, closed_at, close_reason, ack_* … WHERE id = :id AND version = :expectedVersion` (expect 1 row; 0 rows = bug, throw).

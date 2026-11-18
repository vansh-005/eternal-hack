package com.nocook.domain.policy;

import com.nocook.domain.effect.Effect;
import com.nocook.domain.effect.Fact;
import com.nocook.domain.effect.FactKind;
import com.nocook.domain.event.CaseEvent;
import com.nocook.domain.event.CaseEvent.DeadlineDue;
import com.nocook.domain.event.CaseEvent.MerchantAck;
import com.nocook.domain.model.*;
import com.nocook.domain.oms.OmsView;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Pure state machine, DOMAIN.md sections 2-3. One private method per table row. */
public final class CasePolicy {
    private CasePolicy() {}

    // ------------------------------------------------------------------ open

    public static OpenDecision decideOpen(CancellationEvent e, OrderSnapshot s, Instant now, Timings t) {
        Optional<NotActionableReason> failure = EligibilityPolicy.evaluate(e, s, now, t);
        if (failure.isPresent()) {
            NotActionableReason r = failure.get();
            return new OpenDecision(CaseState.NOT_ACTIONABLE, r,
                    List.of(fact(FactKind.NOT_ACTIONABLE, "reason", r.name())), List.of());
        }
        return new OpenDecision(CaseState.AWAITING_ACK, null,
                List.of(fact(FactKind.CASE_OPENED, "eligible", "true", "eventId", e.eventId())),
                List.of(new Effect.ScheduleDeadline(DeadlineKind.L1, e.cancelledAt().plus(t.l1())),
                        new Effect.ScheduleDeadline(DeadlineKind.IVR, e.cancelledAt().plus(t.ivr())),
                        new Effect.ScheduleDeadline(DeadlineKind.EXPIRY, e.cancelledAt().plus(t.maxAge())),
                        new Effect.SendCard(CardKind.OPEN)));
    }

    // ------------------------------------------------------------ transition

    public static TransitionResult transition(AssuranceCase c, CaseEvent ev, OmsView oms, Instant now, Timings t) {
        return switch (ev) {
            case DeadlineDue d -> onDeadline(c, d, oms, now, t);
            case MerchantAck a -> onAck(c, a);
        };
    }

    private static TransitionResult onDeadline(AssuranceCase c, DeadlineDue d, OmsView oms, Instant now, Timings t) {
        TransitionResult r;
        if ((r = d1NotOpen(c)) != null) return r;
        if ((r = d2ExpiryOrTooOld(c, d, now, t)) != null) return r;
        if ((r = d3OmsUnavailable(d, oms, now, c, t)) != null) return r;
        OrderSnapshot snap = ((OmsView.Available) oms).snapshot();   // D3 handled every non-Available view
        if ((r = d4OrderReinstated(c, d, snap)) != null) return r;
        if ((r = d5PickedUp(c, d, snap)) != null) return r;
        if ((r = d6Ready(c, d, snap)) != null) return r;
        if ((r = d7L1FromAwaitingAck(c, d, snap)) != null) return r;
        if ((r = d8L1AlreadyEscalated(c, d)) != null) return r;
        if ((r = d9IvrEscalation(c, d, snap)) != null) return r;
        if ((r = d10IvrAlreadyEscalated(c, d)) != null) return r;
        throw new IllegalStateException("unreachable: no transition row matched " + c.state() + " " + d);
    }

    private static TransitionResult d1NotOpen(AssuranceCase c) {
        if (c.state().isOpen()) return null;
        return noOp(c);
    }

    private static TransitionResult d2ExpiryOrTooOld(AssuranceCase c, DeadlineDue d, Instant now, Timings t) {
        boolean tooOld = Duration.between(c.cancelledAt(), now).compareTo(t.maxAge()) >= 0;
        if (d.kind() != DeadlineKind.EXPIRY && !tooOld) return null;
        return closed(c, CaseState.EXPIRED, CloseReason.EXPIRED,
                List.of(fact(FactKind.CASE_EXPIRED), fact(FactKind.CASE_CLOSED, "reason", "EXPIRED")));
    }

    private static TransitionResult d3OmsUnavailable(DeadlineDue d, OmsView oms, Instant now, AssuranceCase c, Timings t) {
        if (oms instanceof OmsView.Available) return null;
        // Unavailable per spec; NotRead is treated the same (never escalate without a fresh read, I5).
        Instant retryAt = now.plus(t.deferBy());
        return new TransitionResult(Outcome.DEFERRED, c.state(), null,
                List.of(fact(FactKind.ESCALATION_DEFERRED, "kind", d.kind().name(),
                        "reason", "OMS_UNAVAILABLE", "retryAt", retryAt.toString())),
                List.of(new Effect.RescheduleDeadline(d.kind(), retryAt)), null);
    }

    private static TransitionResult d4OrderReinstated(AssuranceCase c, DeadlineDue d, OrderSnapshot s) {
        if (s.status() == OrderStatus.CANCELLED) return null;
        return superseded(c, d, CloseReason.ORDER_REINSTATED);
    }

    private static TransitionResult d5PickedUp(AssuranceCase c, DeadlineDue d, OrderSnapshot s) {
        if (s.pickedUpAt() == null) return null;
        return superseded(c, d, CloseReason.ORDER_PICKED_UP);
    }

    private static TransitionResult d6Ready(AssuranceCase c, DeadlineDue d, OrderSnapshot s) {
        if (s.readyAt() == null) return null;
        return superseded(c, d, CloseReason.ORDER_READY);
    }

    private static TransitionResult d7L1FromAwaitingAck(AssuranceCase c, DeadlineDue d, OrderSnapshot s) {
        if (d.kind() != DeadlineKind.L1 || c.state() != CaseState.AWAITING_ACK) return null;
        List<Fact> facts = new ArrayList<>();
        facts.add(fact(FactKind.L1_TRIGGERED, "dueAt", d.dueAt().toString()));
        addVersionAdvance(facts, c, s);
        return new TransitionResult(Outcome.APPLIED, CaseState.ESCALATED_L1, null, facts,
                List.of(new Effect.SendCard(CardKind.TAKEOVER)), null);
    }

    private static TransitionResult d8L1AlreadyEscalated(AssuranceCase c, DeadlineDue d) {
        if (d.kind() != DeadlineKind.L1) return null;
        return noOp(c);
    }

    private static TransitionResult d9IvrEscalation(AssuranceCase c, DeadlineDue d, OrderSnapshot s) {
        if (d.kind() != DeadlineKind.IVR
                || (c.state() != CaseState.AWAITING_ACK && c.state() != CaseState.ESCALATED_L1)) return null;
        List<Fact> facts = new ArrayList<>();
        facts.add(fact(FactKind.IVR_ESCALATED, "dueAt", d.dueAt().toString()));
        addVersionAdvance(facts, c, s);
        return new TransitionResult(Outcome.APPLIED, CaseState.ESCALATED_IVR, null, facts,
                List.of(new Effect.ReserveIvr()), null);
    }

    private static TransitionResult d10IvrAlreadyEscalated(AssuranceCase c, DeadlineDue d) {
        if (d.kind() != DeadlineKind.IVR || c.state() != CaseState.ESCALATED_IVR) return null;
        return noOp(c);
    }

    // ------------------------------------------------------------------- ack

    private static TransitionResult onAck(AssuranceCase c, MerchantAck a) {
        TransitionResult r;
        if ((r = a1AckOpenCase(c, a)) != null) return r;
        if ((r = a2DuplicateAck(c, a)) != null) return r;
        if ((r = a3AlreadyAcknowledged(c, a)) != null) return r;
        if ((r = a4SupersededOrExpired(c, a)) != null) return r;
        if ((r = a5NotActionable(c)) != null) return r;
        throw new IllegalStateException("unreachable: no ack row matched " + c.state());
    }

    private static TransitionResult a1AckOpenCase(AssuranceCase c, MerchantAck a) {
        if (!c.state().isOpen()) return null;
        Map<String, String> md = new java.util.LinkedHashMap<>();
        md.put("choice", a.choice().name());
        md.put("actor", a.actor());
        FactKind kind;
        if (a.channel() == AckChannel.TABLET) {
            kind = FactKind.TABLET_ACKNOWLEDGED;
            md.put("idempotencyKey", a.idempotencyKey());
        } else {
            kind = FactKind.DTMF_ACKNOWLEDGED;
            md.put("digit", a.choice().digit());
        }
        return closed(c, CaseState.ACKNOWLEDGED, CloseReason.ACKNOWLEDGED,
                List.of(new Fact(kind, md), fact(FactKind.CASE_CLOSED, "reason", "ACKNOWLEDGED")));
    }

    private static TransitionResult a2DuplicateAck(AssuranceCase c, MerchantAck a) {
        if (c.state() != CaseState.ACKNOWLEDGED || a.idempotencyKey() == null
                || !a.idempotencyKey().equals(c.ackIdempotencyKey())) return null;
        return noOp(c);
    }

    private static TransitionResult a3AlreadyAcknowledged(AssuranceCase c, MerchantAck a) {
        if (c.state() != CaseState.ACKNOWLEDGED) return null;
        return rejected(c, a, "ALREADY_ACKNOWLEDGED");
    }

    private static TransitionResult a4SupersededOrExpired(AssuranceCase c, MerchantAck a) {
        if (c.state() != CaseState.SUPERSEDED && c.state() != CaseState.EXPIRED) return null;
        return rejected(c, a, "CASE_CLOSED");
    }

    private static TransitionResult a5NotActionable(AssuranceCase c) {
        if (c.state() != CaseState.NOT_ACTIONABLE) return null;
        return new TransitionResult(Outcome.REJECTED, c.state(), null, List.of(), List.of(), "NOT_ACTIONABLE");
    }

    // --------------------------------------------------------------- helpers

    private static TransitionResult noOp(AssuranceCase c) {
        return new TransitionResult(Outcome.NO_OP, c.state(), null, List.of(), List.of(), null);
    }

    private static TransitionResult rejected(AssuranceCase c, MerchantAck a, String code) {
        return new TransitionResult(Outcome.REJECTED, c.state(), null,
                List.of(fact(FactKind.LATE_ACK_IGNORED, "state", c.state().name(), "channel", a.channel().name())),
                List.of(), code);
    }

    private static TransitionResult closed(AssuranceCase c, CaseState next, CloseReason reason, List<Fact> facts) {
        List<Effect> effects = new ArrayList<>();
        effects.add(new Effect.CancelPendingDeadlines());
        effects.add(new Effect.SendCard(CardKind.CLOSE));
        if (c.state() == CaseState.ESCALATED_IVR) effects.add(new Effect.EndCall());
        return new TransitionResult(Outcome.APPLIED, next, reason, facts, effects, null);
    }

    /** D4-D6 shape: no EndCall (follows the table literally). */
    private static TransitionResult superseded(AssuranceCase c, DeadlineDue d, CloseReason reason) {
        String r = reason.name();
        return new TransitionResult(Outcome.APPLIED, CaseState.SUPERSEDED, reason,
                List.of(fact(FactKind.ESCALATION_BLOCKED, "kind", d.kind().name(), "reason", r),
                        fact(FactKind.CASE_SUPERSEDED, "reason", r),
                        fact(FactKind.CASE_CLOSED, "reason", r)),
                List.of(new Effect.CancelPendingDeadlines(), new Effect.SendCard(CardKind.CLOSE)), null);
    }

    private static void addVersionAdvance(List<Fact> facts, AssuranceCase c, OrderSnapshot s) {
        if (s.version() > c.orderVersion()) {
            facts.add(fact(FactKind.ORDER_VERSION_ADVANCED,
                    "from", Integer.toString(c.orderVersion()), "to", Integer.toString(s.version())));
        }
    }

    private static Fact fact(FactKind kind, String... kv) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return new Fact(kind, m);
    }
}

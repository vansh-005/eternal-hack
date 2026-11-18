package com.nocook.domain.policy;

import com.nocook.domain.effect.Effect;
import com.nocook.domain.effect.FactKind;
import com.nocook.domain.event.CaseEvent;
import com.nocook.domain.event.CaseEvent.DeadlineDue;
import com.nocook.domain.event.CaseEvent.MerchantAck;
import com.nocook.domain.model.*;
import com.nocook.domain.oms.OmsView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static com.nocook.domain.policy.Fx.*;
import static org.assertj.core.api.Assertions.assertThat;

class CasePolicyTransitionTest {

    private static final OmsView NOT_READ = new OmsView.NotRead();

    private TransitionResult tr(AssuranceCase c, CaseEvent ev, OmsView oms, long nowSec) {
        return CasePolicy.transition(c, ev, oms, at(nowSec), T);
    }

    private static OmsView avail(OrderSnapshot s) { return new OmsView.Available(s); }

    private static OmsView stillCancelled() { return avail(cancelledSnapshot()); }

    private static DeadlineDue due(DeadlineKind k, long dueSec) { return new DeadlineDue(k, at(dueSec)); }

    private static MerchantAck ack(AckChannel ch, String key) {
        return new MerchantAck(AckChoice.STOPPED, ch, ch == AckChannel.TABLET ? "dev-1" : "call-1", key);
    }

    private static List<FactKind> kinds(TransitionResult r) {
        return r.facts().stream().map(f -> f.kind()).toList();
    }

    // ---- D1
    @ParameterizedTest
    @EnumSource(value = CaseState.class, names = {"NOT_ACTIONABLE", "ACKNOWLEDGED", "SUPERSEDED", "EXPIRED"})
    void d1_closed_state_is_no_op(CaseState s) {
        for (DeadlineKind k : DeadlineKind.values()) {
            TransitionResult r = tr(aCase(s), due(k, 30), stillCancelled(), 30);
            assertThat(r.outcome()).isEqualTo(Outcome.NO_OP);
            assertThat(r.nextState()).isEqualTo(s);
            assertThat(r.facts()).isEmpty();
            assertThat(r.effects()).isEmpty();
        }
    }

    // ---- D2
    @ParameterizedTest
    @EnumSource(value = CaseState.class, names = {"AWAITING_ACK", "ESCALATED_L1", "ESCALATED_IVR"})
    void d2_expiry_deadline_expires_case(CaseState s) {
        for (OmsView v : List.of(NOT_READ, new OmsView.Unavailable("x"), stillCancelled())) {
            TransitionResult r = tr(aCase(s), due(DeadlineKind.EXPIRY, 600), v, 600);
            assertThat(r.outcome()).isEqualTo(Outcome.APPLIED);
            assertThat(r.nextState()).isEqualTo(CaseState.EXPIRED);
            assertThat(r.closeReason()).isEqualTo(CloseReason.EXPIRED);
            assertThat(kinds(r)).containsExactly(FactKind.CASE_EXPIRED, FactKind.CASE_CLOSED);
            assertThat(fact(r, FactKind.CASE_CLOSED).metadata()).containsEntry("reason", "EXPIRED");
            List<Effect> expected = s == CaseState.ESCALATED_IVR
                    ? List.of(new Effect.CancelPendingDeadlines(), new Effect.SendCard(CardKind.CLOSE), new Effect.EndCall())
                    : List.of(new Effect.CancelPendingDeadlines(), new Effect.SendCard(CardKind.CLOSE));
            assertThat(r.effects()).isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @EnumSource(value = DeadlineKind.class, names = {"L1", "IVR"})
    void d2_age_at_or_over_max_expires_even_for_l1_ivr(DeadlineKind k) {
        TransitionResult r = tr(aCase(CaseState.ESCALATED_L1), due(k, 30), new OmsView.Unavailable("down"), 600);
        assertThat(r.nextState()).isEqualTo(CaseState.EXPIRED);
        TransitionResult before = CasePolicy.transition(aCase(CaseState.ESCALATED_L1), due(k, 30),
                stillCancelled(), T0.plusMillis(599_999), T);
        assertThat(before.nextState()).isNotEqualTo(CaseState.EXPIRED);
    }

    // ---- D3
    @ParameterizedTest
    @EnumSource(value = DeadlineKind.class, names = {"L1", "IVR"})
    void d3_oms_unavailable_defers(DeadlineKind k) {
        TransitionResult r = tr(aCase(CaseState.AWAITING_ACK), due(k, 30), new OmsView.Unavailable("timeout"), 30);
        assertThat(r.outcome()).isEqualTo(Outcome.DEFERRED);
        assertThat(r.nextState()).isEqualTo(CaseState.AWAITING_ACK);
        assertThat(r.closeReason()).isNull();
        assertThat(r.facts()).hasSize(1);
        assertThat(r.facts().get(0).kind()).isEqualTo(FactKind.ESCALATION_DEFERRED);
        assertThat(r.facts().get(0).metadata()).containsEntry("kind", k.name())
                .containsEntry("reason", "OMS_UNAVAILABLE").containsEntry("retryAt", at(32).toString());
        assertThat(r.effects()).containsExactly(new Effect.RescheduleDeadline(k, at(32)));
    }

    @Test
    void d3_not_read_is_treated_as_unavailable_never_escalates_without_a_read() {
        TransitionResult r = tr(aCase(CaseState.AWAITING_ACK), due(DeadlineKind.L1, 30), NOT_READ, 30);
        assertThat(r.outcome()).isEqualTo(Outcome.DEFERRED);
        assertThat(r.nextState()).isEqualTo(CaseState.AWAITING_ACK);
    }

    // ---- D4 / D5 / D6
    private void assertSuperseded(TransitionResult r, CloseReason reason, DeadlineKind k) {
        assertThat(r.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(r.nextState()).isEqualTo(CaseState.SUPERSEDED);
        assertThat(r.closeReason()).isEqualTo(reason);
        assertThat(kinds(r)).containsExactly(FactKind.ESCALATION_BLOCKED, FactKind.CASE_SUPERSEDED, FactKind.CASE_CLOSED);
        assertThat(fact(r, FactKind.ESCALATION_BLOCKED).metadata())
                .containsEntry("kind", k.name()).containsEntry("reason", reason.name());
        assertThat(fact(r, FactKind.CASE_SUPERSEDED).metadata()).containsEntry("reason", reason.name());
        assertThat(fact(r, FactKind.CASE_CLOSED).metadata()).containsEntry("reason", reason.name());
        assertThat(r.effects()).containsExactly(new Effect.CancelPendingDeadlines(), new Effect.SendCard(CardKind.CLOSE));
    }

    @ParameterizedTest
    @EnumSource(value = DeadlineKind.class, names = {"L1", "IVR"})
    void d4_order_reinstated_supersedes(DeadlineKind k) {
        OrderSnapshot s = snap(OrderStatus.PREPARING, T0.minusSeconds(60), null, null, null, 2);
        assertSuperseded(tr(aCase(CaseState.AWAITING_ACK), due(k, 30), avail(s), 30), CloseReason.ORDER_REINSTATED, k);
    }

    @ParameterizedTest
    @EnumSource(value = DeadlineKind.class, names = {"L1", "IVR"})
    void d5_picked_up_supersedes(DeadlineKind k) {
        OrderSnapshot s = snap(OrderStatus.CANCELLED, T0.minusSeconds(60), null, T0.plusSeconds(10), CancelInitiator.CUSTOMER, 1);
        assertSuperseded(tr(aCase(CaseState.ESCALATED_L1), due(k, 30), avail(s), 30), CloseReason.ORDER_PICKED_UP, k);
    }

    @ParameterizedTest
    @EnumSource(value = DeadlineKind.class, names = {"L1", "IVR"})
    void d6_ready_supersedes(DeadlineKind k) {
        OrderSnapshot s = snap(OrderStatus.CANCELLED, T0.minusSeconds(60), T0.plusSeconds(10), null, CancelInitiator.CUSTOMER, 1);
        assertSuperseded(tr(aCase(CaseState.AWAITING_ACK), due(k, 30), avail(s), 30), CloseReason.ORDER_READY, k);
    }

    @Test
    void d5_wins_over_d6_when_both_set() {
        OrderSnapshot s = snap(OrderStatus.CANCELLED, T0.minusSeconds(60), T0.plusSeconds(5), T0.plusSeconds(10), CancelInitiator.CUSTOMER, 1);
        TransitionResult r = tr(aCase(CaseState.AWAITING_ACK), due(DeadlineKind.L1, 30), avail(s), 30);
        assertThat(r.closeReason()).isEqualTo(CloseReason.ORDER_PICKED_UP);
    }

    // ---- D7
    @Test
    void d7_l1_from_awaiting_ack_escalates_and_sends_takeover() {
        TransitionResult r = tr(aCase(CaseState.AWAITING_ACK), due(DeadlineKind.L1, 30), stillCancelled(), 30);
        assertThat(r.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(r.nextState()).isEqualTo(CaseState.ESCALATED_L1);
        assertThat(r.closeReason()).isNull();
        assertThat(r.facts()).hasSize(1);
        assertThat(r.facts().get(0).kind()).isEqualTo(FactKind.L1_TRIGGERED);
        assertThat(r.facts().get(0).metadata()).containsEntry("dueAt", at(30).toString());
        assertThat(r.effects()).containsExactly(new Effect.SendCard(CardKind.TAKEOVER));
    }

    @Test
    void d7_records_version_advance_when_snapshot_version_is_higher() {
        OrderSnapshot s = snap(OrderStatus.CANCELLED, T0.minusSeconds(60), null, null, CancelInitiator.CUSTOMER, 3);
        TransitionResult r = tr(aCase(CaseState.AWAITING_ACK), due(DeadlineKind.L1, 30), avail(s), 30);
        assertThat(kinds(r)).containsExactly(FactKind.L1_TRIGGERED, FactKind.ORDER_VERSION_ADVANCED);
        assertThat(fact(r, FactKind.ORDER_VERSION_ADVANCED).metadata()).containsEntry("from", "1").containsEntry("to", "3");
    }

    // ---- D8
    @ParameterizedTest
    @EnumSource(value = CaseState.class, names = {"ESCALATED_L1", "ESCALATED_IVR"})
    void d8_l1_when_already_escalated_is_no_op(CaseState s) {
        TransitionResult r = tr(aCase(s), due(DeadlineKind.L1, 30), stillCancelled(), 40);
        assertThat(r.outcome()).isEqualTo(Outcome.NO_OP);
        assertThat(r.nextState()).isEqualTo(s);
        assertThat(r.facts()).isEmpty();
        assertThat(r.effects()).isEmpty();
    }

    // ---- D9
    @ParameterizedTest
    @EnumSource(value = CaseState.class, names = {"AWAITING_ACK", "ESCALATED_L1"})
    void d9_ivr_escalates_and_reserves_without_cancelling_deadlines(CaseState s) {
        TransitionResult r = tr(aCase(s), due(DeadlineKind.IVR, 75), stillCancelled(), 75);
        assertThat(r.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(r.nextState()).isEqualTo(CaseState.ESCALATED_IVR);
        assertThat(r.facts()).hasSize(1);
        assertThat(r.facts().get(0).kind()).isEqualTo(FactKind.IVR_ESCALATED);
        assertThat(r.facts().get(0).metadata()).containsEntry("dueAt", at(75).toString());
        assertThat(r.effects()).containsExactly(new Effect.ReserveIvr());
    }

    @Test
    void d9_records_version_advance() {
        OrderSnapshot s = snap(OrderStatus.CANCELLED, T0.minusSeconds(60), null, null, CancelInitiator.CUSTOMER, 2);
        TransitionResult r = tr(aCase(CaseState.ESCALATED_L1), due(DeadlineKind.IVR, 75), avail(s), 75);
        assertThat(kinds(r)).containsExactly(FactKind.IVR_ESCALATED, FactKind.ORDER_VERSION_ADVANCED);
    }

    // ---- D10
    @Test
    void d10_ivr_when_already_ivr_is_no_op() {
        TransitionResult r = tr(aCase(CaseState.ESCALATED_IVR), due(DeadlineKind.IVR, 75), stillCancelled(), 80);
        assertThat(r.outcome()).isEqualTo(Outcome.NO_OP);
        assertThat(r.nextState()).isEqualTo(CaseState.ESCALATED_IVR);
        assertThat(r.facts()).isEmpty();
        assertThat(r.effects()).isEmpty();
    }

    // ---- A1
    @ParameterizedTest
    @EnumSource(value = CaseState.class, names = {"AWAITING_ACK", "ESCALATED_L1", "ESCALATED_IVR"})
    void a1_ack_on_open_case_acknowledges(CaseState s) {
        for (AckChannel ch : AckChannel.values()) {
            TransitionResult r = tr(aCase(s), ack(ch, "k-9"), NOT_READ, 20);
            assertThat(r.outcome()).isEqualTo(Outcome.APPLIED);
            assertThat(r.nextState()).isEqualTo(CaseState.ACKNOWLEDGED);
            assertThat(r.closeReason()).isEqualTo(CloseReason.ACKNOWLEDGED);
            FactKind expected = ch == AckChannel.TABLET ? FactKind.TABLET_ACKNOWLEDGED : FactKind.DTMF_ACKNOWLEDGED;
            assertThat(kinds(r)).containsExactly(expected, FactKind.CASE_CLOSED);
            assertThat(fact(r, expected).metadata()).containsEntry("choice", "STOPPED")
                    .containsEntry("actor", ch == AckChannel.TABLET ? "dev-1" : "call-1");
            if (ch == AckChannel.TABLET) {
                assertThat(fact(r, expected).metadata()).containsEntry("idempotencyKey", "k-9");
            } else {
                assertThat(fact(r, expected).metadata()).containsEntry("digit", "1");
            }
            assertThat(fact(r, FactKind.CASE_CLOSED).metadata()).containsEntry("reason", "ACKNOWLEDGED");
            List<Effect> exp = s == CaseState.ESCALATED_IVR
                    ? List.of(new Effect.CancelPendingDeadlines(), new Effect.SendCard(CardKind.CLOSE), new Effect.EndCall())
                    : List.of(new Effect.CancelPendingDeadlines(), new Effect.SendCard(CardKind.CLOSE));
            assertThat(r.effects()).isEqualTo(exp);
        }
    }

    // ---- A2
    @Test
    void a2_same_key_on_acknowledged_is_duplicate_no_op() {
        TransitionResult r = tr(aCase(CaseState.ACKNOWLEDGED), ack(AckChannel.TABLET, "key-1"), NOT_READ, 50);
        assertThat(r.outcome()).isEqualTo(Outcome.NO_OP);
        assertThat(r.nextState()).isEqualTo(CaseState.ACKNOWLEDGED);
        assertThat(r.facts()).isEmpty();
        assertThat(r.effects()).isEmpty();
        assertThat(r.rejectionCode()).isNull();
    }

    // ---- A3
    @Test
    void a3_different_key_on_acknowledged_is_rejected() {
        TransitionResult r = tr(aCase(CaseState.ACKNOWLEDGED), ack(AckChannel.IVR, "other"), NOT_READ, 50);
        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.rejectionCode()).isEqualTo("ALREADY_ACKNOWLEDGED");
        assertThat(r.nextState()).isEqualTo(CaseState.ACKNOWLEDGED);
        assertThat(r.effects()).isEmpty();
        assertThat(r.facts()).hasSize(1);
        assertThat(r.facts().get(0).kind()).isEqualTo(FactKind.LATE_ACK_IGNORED);
        assertThat(r.facts().get(0).metadata()).containsEntry("state", "ACKNOWLEDGED").containsEntry("channel", "IVR");
    }

    // ---- A4
    @ParameterizedTest
    @EnumSource(value = CaseState.class, names = {"SUPERSEDED", "EXPIRED"})
    void a4_ack_on_superseded_or_expired_is_rejected(CaseState s) {
        TransitionResult r = tr(aCase(s), ack(AckChannel.TABLET, "k"), NOT_READ, 50);
        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.rejectionCode()).isEqualTo("CASE_CLOSED");
        assertThat(r.nextState()).isEqualTo(s);
        assertThat(r.effects()).isEmpty();
        assertThat(r.facts()).hasSize(1);
        assertThat(r.facts().get(0).kind()).isEqualTo(FactKind.LATE_ACK_IGNORED);
        assertThat(r.facts().get(0).metadata()).containsEntry("state", s.name()).containsEntry("channel", "TABLET");
    }

    // ---- A5
    @Test
    void a5_ack_on_not_actionable_is_rejected() {
        TransitionResult r = tr(aCase(CaseState.NOT_ACTIONABLE), ack(AckChannel.TABLET, "k"), NOT_READ, 50);
        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.rejectionCode()).isEqualTo("NOT_ACTIONABLE");
        assertThat(r.nextState()).isEqualTo(CaseState.NOT_ACTIONABLE);
        assertThat(r.facts()).isEmpty();
        assertThat(r.effects()).isEmpty();
    }

    // ---- extras
    @Test
    void l1_boundary_fires_at_exactly_30s() {
        TransitionResult r = tr(aCase(CaseState.AWAITING_ACK), due(DeadlineKind.L1, 30), stillCancelled(), 30);
        assertThat(r.nextState()).isEqualTo(CaseState.ESCALATED_L1);
    }

    @Test
    void expiry_wins_over_oms_unavailable() {
        TransitionResult r = tr(aCase(CaseState.ESCALATED_L1), due(DeadlineKind.EXPIRY, 600),
                new OmsView.Unavailable("down"), 600);
        assertThat(r.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(r.nextState()).isEqualTo(CaseState.EXPIRED);
    }

    @Test
    void version_advance_alone_does_not_supersede() {
        OrderSnapshot s = snap(OrderStatus.CANCELLED, T0.minusSeconds(60), null, null, CancelInitiator.CUSTOMER, 5);
        TransitionResult r = tr(aCase(CaseState.AWAITING_ACK), due(DeadlineKind.L1, 30), avail(s), 30);
        assertThat(r.nextState()).isEqualTo(CaseState.ESCALATED_L1);
        assertThat(hasFact(r, FactKind.ORDER_VERSION_ADVANCED)).isTrue();
        assertThat(hasFact(r, FactKind.CASE_SUPERSEDED)).isFalse();
    }

    @Test
    void ivr_from_awaiting_ack_when_l1_was_deferred() {
        TransitionResult r = tr(aCase(CaseState.AWAITING_ACK), due(DeadlineKind.IVR, 75), stillCancelled(), 75);
        assertThat(r.nextState()).isEqualTo(CaseState.ESCALATED_IVR);
        assertThat(r.effects()).containsExactly(new Effect.ReserveIvr());
    }

    @Test
    void ack_not_gated_on_case_version() {
        AssuranceCase v2 = aCase(CaseState.ESCALATED_L1);
        AssuranceCase bumped = new AssuranceCase(v2.id(), v2.orderId(), v2.orderVersion(), v2.outletId(), v2.state(),
                7, v2.cancelledAt(), v2.openedAt(), null, null, null);
        TransitionResult r = tr(bumped, ack(AckChannel.TABLET, "k"), NOT_READ, 40);
        assertThat(r.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(r.nextState()).isEqualTo(CaseState.ACKNOWLEDGED);
    }

    @Test
    void every_applied_result_has_at_least_one_fact() {
        for (CaseState s : CaseState.values()) {
            for (CaseEvent ev : TransitionTableCoverageTest.events()) {
                for (OmsView v : TransitionTableCoverageTest.views()) {
                    TransitionResult r = tr(aCase(s), ev, v, 30);
                    if (r.outcome() == Outcome.APPLIED || r.outcome() == Outcome.DEFERRED) {
                        assertThat(r.facts()).as("%s %s %s", s, ev, v).isNotEmpty();
                    }
                }
            }
        }
    }

    @Test
    void no_effects_on_terminal_states() {
        for (CaseState s : CaseState.values()) {
            if (s.isOpen()) continue;
            for (CaseEvent ev : TransitionTableCoverageTest.events()) {
                for (OmsView v : TransitionTableCoverageTest.views()) {
                    TransitionResult r = tr(aCase(s), ev, v, 30);
                    assertThat(r.effects()).as("%s %s %s", s, ev, v).isEmpty();
                    assertThat(r.nextState()).isEqualTo(s);
                    assertThat(r.outcome()).isNotEqualTo(Outcome.APPLIED);
                }
            }
        }
    }

    @Test
    void from_digit_maps_dtmf() {
        assertThat(AckChoice.fromDigit("1")).contains(AckChoice.STOPPED);
        assertThat(AckChoice.fromDigit("2")).contains(AckChoice.NOT_STARTED);
        assertThat(AckChoice.fromDigit("3")).contains(AckChoice.ALREADY_COOKED);
        assertThat(AckChoice.fromDigit("9")).isEmpty();
        assertThat(AckChoice.fromDigit(null)).isEmpty();
    }
}

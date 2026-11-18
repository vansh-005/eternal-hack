package com.nocook.sim;

import com.nocook.domain.effect.Effect;
import com.nocook.domain.event.CaseEvent;
import com.nocook.domain.model.AckChannel;
import com.nocook.domain.model.AckChoice;
import com.nocook.domain.model.AssuranceCase;
import com.nocook.domain.model.CancelInitiator;
import com.nocook.domain.model.CancellationEvent;
import com.nocook.domain.model.CaseState;
import com.nocook.domain.model.DeadlineKind;
import com.nocook.domain.model.OrderSnapshot;
import com.nocook.domain.model.Outcome;
import com.nocook.domain.model.Timings;
import com.nocook.domain.oms.OmsView;
import com.nocook.domain.policy.CasePolicy;
import com.nocook.domain.policy.OpenDecision;
import com.nocook.domain.policy.TransitionResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** NOCOOK: drives the real CasePolicy with virtual Instants (SIMULATION.md section 3). */
public final class NoCookArm implements Arm<NoCookArm.Result> {

    /**
     * @param ackAtSeconds    when the case was closed by an applied ACK, else null
     * @param ackLabel        CARD_ACK, L1_ACK, IVR_ACK or LATE_TABLET_ACK; null without ACK
     * @param ackChannel      channel of the applied ACK, else null
     * @param finalState      state of the case at the end (NOT_ACTIONABLE when never opened)
     * @param statePath       states visited, joined by '>'
     * @param ivrOutcome      null if no call; ANSWERED_DTMF, ANSWERED_NO_DTMF, NO_ANSWER, BUSY or FAILED
     */
    public record Result(boolean eligible, Double ackAtSeconds, String ackLabel, AckChannel ackChannel,
                          CaseState finalState, String statePath, boolean ivrCalled, String ivrOutcome) {}

    private record Step(double atSeconds, int priority, DeadlineKind deadline, AckChannel ackChannel) {}

    private final ResponseModel responses;
    private final Assumptions assumptions;
    private final Timings timings;

    public NoCookArm(Assumptions assumptions, Timings timings) {
        this.assumptions = assumptions;
        this.responses = new ResponseModel(assumptions);
        this.timings = timings;
    }

    @Override
    public Result run(ScenarioDraws d) {
        KitchenModel kitchen = KitchenModel.from(d, assumptions);
        CancelInitiator initiator = responses.initiator(d);
        long valuePaise = responses.orderValuePaise(d);
        Instant cancelAt = KitchenModel.CANCEL_AT;
        Instant openAt = at(assumptions.openLatencySeconds());

        CancellationEvent event = new CancellationEvent("evt-" + d.id(), "ord-" + d.id(), "outlet-sim", 1,
                cancelAt, initiator, "sim");
        OrderSnapshot openSnapshot = kitchen.snapshotAt(d, initiator, assumptions.openLatencySeconds(), valuePaise);
        OpenDecision decision = CasePolicy.decideOpen(event, openSnapshot, openAt, timings);
        if (decision.initialState() != CaseState.AWAITING_ACK) {
            return new Result(false, null, null, null, CaseState.NOT_ACTIONABLE, "NOT_ACTIONABLE", false, null);
        }

        List<Step> steps = scheduleSteps(decision, d);
        AssuranceCase current = new AssuranceCase(new UUID(0, d.id()), event.orderId(), 1, event.outletId(),
                CaseState.AWAITING_ACK, 1, cancelAt, openAt, null, null, null);
        List<String> states = new ArrayList<>(List.of(CaseState.AWAITING_ACK.name()));
        Double ackAt = null;
        String ackLabel = null;
        AckChannel ackChannelUsed = null;
        boolean ivrCalled = false;

        for (Step step : steps) {
            if (!current.state().isOpen()) break;   // closing cancels every pending deadline and response
            if (step.ackChannel() == AckChannel.IVR && !ivrCalled) continue;   // nobody to pick up a call never placed
            Instant now = at(step.atSeconds());
            TransitionResult result;
            if (step.deadline() != null) {
                OmsView oms = new OmsView.Available(kitchen.snapshotAt(d, initiator, step.atSeconds(), valuePaise));
                result = CasePolicy.transition(current, new CaseEvent.DeadlineDue(step.deadline(), now), oms, now, timings);
            } else {
                String key = "ack-" + d.id();
                result = CasePolicy.transition(current,
                        new CaseEvent.MerchantAck(AckChoice.STOPPED, step.ackChannel(), "sim", key),
                        new OmsView.NotRead(), now, timings);
            }
            if (result.outcome() == Outcome.DEFERRED) {
                throw new IllegalStateException("OMS is always available in the simulation: " + result);
            }
            if (result.outcome() != Outcome.APPLIED) continue;
            CaseState before = current.state();
            boolean closed = !result.nextState().isOpen();
            current = new AssuranceCase(current.id(), current.orderId(), 1, current.outletId(), result.nextState(),
                    current.version() + 1, cancelAt, openAt, closed ? now : null, result.closeReason(),
                    step.ackChannel() == AckChannel.TABLET ? "ack-" + d.id() : null);
            states.add(result.nextState().name());
            if (hasReserveIvr(result)) ivrCalled = true;
            if (step.ackChannel() != null) {
                ackAt = step.atSeconds();
                ackChannelUsed = step.ackChannel();
                ackLabel = ackLabel(before, step.ackChannel());
            }
        }
        return new Result(true, ackAt, ackLabel, ackChannelUsed, current.state(), String.join(">", states),
                ivrCalled, ivrOutcome(ivrCalled, ackChannelUsed, responses.plan(d)));
    }

    private List<Step> scheduleSteps(OpenDecision decision, ScenarioDraws d) {
        List<Step> steps = new ArrayList<>();
        for (Effect effect : decision.effects()) {
            if (effect instanceof Effect.ScheduleDeadline s) {
                double seconds = Duration.between(KitchenModel.CANCEL_AT, s.dueAt()).toNanos() / 1e9;
                steps.add(new Step(seconds, 0, s.kind(), null));
            }
        }
        ResponseModel.ResponsePlan plan = responses.plan(d);
        double reachableAt = plan.offline() ? assumptions.openLatencySeconds() + plan.reconnectSeconds()
                : assumptions.openLatencySeconds();
        double l1Due = timings.l1().toNanos() / 1e9;
        double ivrDue = timings.ivr().toNanos() / 1e9;
        if (plan.cardSeen()) {
            steps.add(new Step(reachableAt + plan.cardDelaySeconds(), 1, null, AckChannel.TABLET));
        } else if (plan.takeoverAck()) {
            steps.add(new Step(Math.max(l1Due, reachableAt) + plan.takeoverDelaySeconds(), 1, null, AckChannel.TABLET));
        } else if (plan.ivrAnswered()) {
            steps.add(new Step(ivrDue + plan.dtmfDelaySeconds(), 1, null, AckChannel.IVR));
        }
        steps.sort(Comparator.comparingDouble(Step::atSeconds).thenComparingInt(Step::priority));
        return steps;
    }

    private static boolean hasReserveIvr(TransitionResult result) {
        return result.effects().stream().anyMatch(e -> e instanceof Effect.ReserveIvr);
    }

    private static String ackLabel(CaseState stateBefore, AckChannel channel) {
        if (channel == AckChannel.IVR) return "IVR_ACK";
        return switch (stateBefore) {
            case AWAITING_ACK -> "CARD_ACK";
            case ESCALATED_L1 -> "L1_ACK";
            default -> "LATE_TABLET_ACK";
        };
    }

    private static String ivrOutcome(boolean called, AckChannel ackChannel, ResponseModel.ResponsePlan plan) {
        if (!called) return null;
        if (ackChannel == AckChannel.IVR) return "ANSWERED_DTMF";
        if (plan.ivrAnswered()) return "ANSWERED_NO_DTMF";
        return plan.ivrOutcomeIfNotAnswered();
    }

    private static Instant at(double secondsAfterCancel) {
        return KitchenModel.CANCEL_AT.plusNanos(Math.round(secondsAfterCancel * 1e9));
    }
}

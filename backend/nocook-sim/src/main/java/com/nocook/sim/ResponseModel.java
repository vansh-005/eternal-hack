package com.nocook.sim;

import com.nocook.domain.model.CancelInitiator;

/** Turns draws into human behaviour: kitchen discovery, card/takeover/IVR responses, stop delay, offline tablet. */
public final class ResponseModel {
    private final Assumptions a;

    public ResponseModel(Assumptions a) {
        this.a = a;
    }

    public boolean isLate(ScenarioDraws d) {
        return d.lateU() < a.lateNoticeRate();
    }

    /** Seconds after cancel until somebody in the kitchen notices on their own (baseline stop time). */
    public double baselineDiscoverySeconds(ScenarioDraws d) {
        if (isLate(d)) {
            return Dist.lognormal(d.lateDiscoveryU(), a.baselineDiscovery().late().medianSeconds(),
                    a.baselineDiscovery().late().sigma());
        }
        return Dist.lognormal(d.notLateDiscoveryU(), a.baselineDiscovery().notLate().medianSeconds(),
                a.baselineDiscovery().notLate().sigma());
    }

    public CancelInitiator initiator(ScenarioDraws d) {
        return CancelInitiator.valueOf(Dist.pick(a.initiator(), d.initiatorU()));
    }

    public long orderValuePaise(ScenarioDraws d) {
        return Math.round(Dist.uniform(d.valueU(), a.orderValueRupees()) * 100);
    }

    public ResponsePlan plan(ScenarioDraws d) {
        boolean offline = d.offlineU() < a.deviceOffline().probability();
        double reconnect = offline ? Dist.uniform(d.reconnectU(), a.deviceOffline().reconnectSeconds()) : 0;
        var card = a.cardAckBeforeL1();
        var takeover = a.ackAfterTakeover();
        var ivr = a.ivr();
        boolean cardSeen = d.cardSeenU() < card.probability();
        boolean takeoverAck = !cardSeen && d.l1AckU() < takeover.probability();
        boolean ivrAnswered = !cardSeen && !takeoverAck && d.ivrAnsweredU() < ivr.answeredProbabilityOfRemaining();
        return new ResponsePlan(offline, reconnect,
                cardSeen, Dist.truncatedLognormal(d.cardDelayU(), card.medianSeconds(), card.sigma(),
                card.truncatedBelowSeconds()),
                takeoverAck, Dist.truncatedLognormal(d.l1DelayU(), takeover.medianSeconds(), takeover.sigma(),
                takeover.truncatedBelowSeconds()),
                ivrAnswered, Dist.lognormal(d.dtmfDelayU(), ivr.dtmfMedianSecondsAfterCallStart(), ivr.dtmfSigma()),
                Dist.pick(ivr.outcomeIfNotAnswered(), d.ivrOutcomeU()),
                Dist.uniform(d.stopDelayU(), a.stopDelayAfterAckSeconds()));
    }

    /**
     * Who would respond, and how fast. The three response chances are applied to what remains
     * (card first, then takeover, then IVR), as in SIMULATION.md section 2.
     */
    public record ResponsePlan(boolean offline, double reconnectSeconds,
                               boolean cardSeen, double cardDelaySeconds,
                               boolean takeoverAck, double takeoverDelaySeconds,
                               boolean ivrAnswered, double dtmfDelaySeconds, String ivrOutcomeIfNotAnswered,
                               double stopDelaySeconds) {}
}

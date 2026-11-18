package com.nocook.sim;

import java.util.Map;

/** Typed view of sim/assumptions-v1.json. Every number in it is an assumption, not a measurement. */
public record Assumptions(
        String version,
        Map<KitchenStage, Double> stageAtCancel,
        Map<String, Double> initiator,
        Range orderValueRupees,
        KitchenSeconds kitchenSecondsToNextStage,
        Map<KitchenStage, Integer> stageCostRupees,
        double lateNoticeRate,
        double[] lateNoticeSensitivity,
        double lateDiscoveryThresholdSeconds,
        BaselineDiscovery baselineDiscovery,
        TruncatedAck cardAckBeforeL1,
        TruncatedAck ackAfterTakeover,
        Ivr ivr,
        Range stopDelayAfterAckSeconds,
        DeviceOffline deviceOffline,
        double openLatencySeconds,
        double ivrCostPerCallPaise) {

    public record Range(double min, double max) {}

    public record Lognormal(double medianSeconds, double sigma) {}

    public record KitchenSeconds(Range notStartedToPrep, Range prepToOven, Range ovenToReady) {}

    public record BaselineDiscovery(Lognormal notLate, Lognormal late) {}

    public record TruncatedAck(double probability, double medianSeconds, double sigma, double truncatedBelowSeconds) {}

    public record Ivr(double answeredProbabilityOfRemaining, double dtmfMedianSecondsAfterCallStart,
                      double dtmfSigma, Map<String, Double> outcomeIfNotAnswered) {}

    public record DeviceOffline(double probability, Range reconnectSeconds) {}

    /** Same assumptions with a different baseline late-notice rate (sensitivity reruns). */
    public Assumptions withLateNoticeRate(double rate) {
        return new Assumptions(version, stageAtCancel, initiator, orderValueRupees, kitchenSecondsToNextStage,
                stageCostRupees, rate, lateNoticeSensitivity, lateDiscoveryThresholdSeconds, baselineDiscovery,
                cardAckBeforeL1, ackAfterTakeover, ivr, stopDelayAfterAckSeconds, deviceOffline,
                openLatencySeconds, ivrCostPerCallPaise);
    }
}

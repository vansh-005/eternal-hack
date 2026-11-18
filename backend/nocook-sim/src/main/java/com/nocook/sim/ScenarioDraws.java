package com.nocook.sim;

/**
 * Every random number of one scenario, drawn up front as uniforms in [0,1). Both arms read only this record,
 * so a scenario is identical under BASELINE and NOCOOK. Interpretation (distributions) lives in the models.
 */
public record ScenarioDraws(
        int id,
        double stageU, double initiatorU, double valueU,
        double notStartedToPrepU, double prepToOvenU, double ovenToReadyU,
        double lateU, double notLateDiscoveryU, double lateDiscoveryU,
        double cardSeenU, double cardDelayU,
        double l1AckU, double l1DelayU,
        double ivrAnsweredU, double dtmfDelayU, double ivrOutcomeU,
        double stopDelayU,
        double offlineU, double reconnectU) {}

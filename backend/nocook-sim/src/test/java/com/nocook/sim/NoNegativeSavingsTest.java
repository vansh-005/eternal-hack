package com.nocook.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoNegativeSavingsTest {
    @Test
    void avoidedRupeesAreNeverNegativeAtAnyLateNoticeRate() throws Exception {
        Assumptions a = TestSupport.assumptions();
        List<ScenarioDraws> draws = ScenarioGenerator.generate(2026, 10_000);
        for (double rate : new double[]{0.0, 0.05, 0.10, 0.20, 1.0}) {
            for (PairRunner.ScenarioResult r : SimRunner.runAll(draws, a.withLateNoticeRate(rate))) {
                assertThat(r.avoidedRupees()).as("scenario %d rate %s", r.id(), rate).isGreaterThanOrEqualTo(0);
                assertThat(r.nocookStopS()).isLessThanOrEqualTo(r.baselineStopS());
            }
        }
    }
}

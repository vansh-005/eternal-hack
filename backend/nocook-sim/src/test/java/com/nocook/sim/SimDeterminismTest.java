package com.nocook.sim;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SimDeterminismTest {
    @Test
    void sameSeedGivesByteIdenticalSummaryAndCsv() throws Exception {
        byte[] assumptions = TestSupport.assumptionsBytes();
        SimRunner.Output first = SimRunner.run(2026, 2000, assumptions, "sha", "sim-test", "abc1234");
        SimRunner.Output second = SimRunner.run(2026, 2000, assumptions, "sha", "sim-test", "abc1234");
        assertThat(first.summaryJson()).isEqualTo(second.summaryJson());
        assertThat(first.scenariosCsv()).isEqualTo(second.scenariosCsv());
    }

    @Test
    void differentSeedChangesOutput() throws Exception {
        byte[] assumptions = TestSupport.assumptionsBytes();
        SimRunner.Output a = SimRunner.run(1, 500, assumptions, "sha", "sim-test", "abc1234");
        SimRunner.Output b = SimRunner.run(2, 500, assumptions, "sha", "sim-test", "abc1234");
        assertThat(a.scenariosCsv()).isNotEqualTo(b.scenariosCsv());
    }
}

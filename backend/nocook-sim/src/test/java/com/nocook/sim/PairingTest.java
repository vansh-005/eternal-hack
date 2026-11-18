package com.nocook.sim;

import com.nocook.domain.model.Timings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PairingTest {
    @Test
    void bothArmsSeeTheIdenticalDrawsOfEveryScenario() throws Exception {
        Assumptions a = TestSupport.assumptions();
        List<ScenarioDraws> seenByBaseline = new ArrayList<>();
        List<ScenarioDraws> seenByNoCook = new ArrayList<>();
        BaselineArm baseline = new BaselineArm(new ResponseModel(a));
        NoCookArm noCook = new NoCookArm(a, Timings.defaults());
        PairRunner runner = new PairRunner(a,
                d -> { seenByBaseline.add(d); return baseline.run(d); },
                d -> { seenByNoCook.add(d); return noCook.run(d); });

        List<ScenarioDraws> generated = ScenarioGenerator.generate(2026, 1000);
        for (ScenarioDraws d : generated) runner.run(d);

        assertThat(seenByBaseline).containsExactlyElementsOf(generated);
        assertThat(seenByNoCook).containsExactlyElementsOf(generated);
        assertThat(ScenarioGenerator.generate(2026, 1000)).isEqualTo(generated);
    }

    @Test
    void drawsDoNotDependOnAssumptions() throws Exception {
        Assumptions a = TestSupport.assumptions();
        List<ScenarioDraws> draws = ScenarioGenerator.generate(7, 200);
        List<PairRunner.ScenarioResult> low = SimRunner.runAll(draws, a.withLateNoticeRate(0.05));
        List<PairRunner.ScenarioResult> high = SimRunner.runAll(draws, a.withLateNoticeRate(0.20));
        for (int i = 0; i < draws.size(); i++) {
            assertThat(low.get(i).statePath()).isEqualTo(high.get(i).statePath());
            assertThat(low.get(i).stage()).isEqualTo(high.get(i).stage());
        }
    }
}

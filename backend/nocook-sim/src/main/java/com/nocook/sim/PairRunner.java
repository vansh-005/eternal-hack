package com.nocook.sim;

import com.nocook.domain.model.CaseState;
import com.nocook.domain.model.Timings;

/** Runs both arms on the same ScenarioDraws and compares them. */
public final class PairRunner {

    /** One row of scenarios.csv. Costs are rupees; avoided is the raw difference (never clamped). */
    public record ScenarioResult(int id, KitchenStage stage, String initiator, boolean eligible,
                                 double baselineStopS, double nocookStopS,
                                 int baselineCost, int nocookCost, int avoidedRupees,
                                 String path, boolean ivrCalled, String ivrOutcome,
                                 String statePath, String ackLabel, String ackChannel, boolean baselineLate) {}

    private final Assumptions assumptions;
    private final Arm<BaselineArm.Outcome> baselineArm;
    private final Arm<NoCookArm.Result> noCookArm;

    public PairRunner(Assumptions assumptions, Arm<BaselineArm.Outcome> baselineArm, Arm<NoCookArm.Result> noCookArm) {
        this.assumptions = assumptions;
        this.baselineArm = baselineArm;
        this.noCookArm = noCookArm;
    }

    public static PairRunner standard(Assumptions assumptions) {
        return new PairRunner(assumptions,
                new BaselineArm(new ResponseModel(assumptions)),
                new NoCookArm(assumptions, Timings.defaults()));
    }

    public ScenarioResult run(ScenarioDraws draws) {
        BaselineArm.Outcome baseline = baselineArm.run(draws);
        NoCookArm.Result noCook = noCookArm.run(draws);
        KitchenModel kitchen = KitchenModel.from(draws, assumptions);
        ResponseModel responses = new ResponseModel(assumptions);

        double baselineStop = baseline.discoverySeconds();
        double nocookStop = baselineStop;
        if (noCook.ackAtSeconds() != null) {
            double ackedStop = noCook.ackAtSeconds() + responses.plan(draws).stopDelaySeconds();
            nocookStop = Math.min(baselineStop, ackedStop);
        }
        int baselineCost = kitchen.costRupeesAt(baselineStop);
        int nocookCost = kitchen.costRupeesAt(nocookStop);
        return new ScenarioResult(draws.id(), kitchen.stageAtCancel(), responses.initiator(draws).name(),
                noCook.eligible(), baselineStop, nocookStop, baselineCost, nocookCost, baselineCost - nocookCost,
                pathOf(noCook), noCook.ivrCalled(), noCook.ivrOutcome(), noCook.statePath(), noCook.ackLabel(),
                noCook.ackChannel() == null ? null : noCook.ackChannel().name(), baseline.late());
    }

    private static String pathOf(NoCookArm.Result noCook) {
        if (noCook.ackLabel() != null) return noCook.ackLabel();
        CaseState state = noCook.finalState();
        return state.name();
    }
}

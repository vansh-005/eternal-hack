package com.nocook.sim;

/** BASELINE: the kitchen stops when somebody notices on their own. */
public final class BaselineArm implements Arm<BaselineArm.Outcome> {
    public record Outcome(double discoverySeconds, boolean late) {}

    private final ResponseModel responses;

    public BaselineArm(ResponseModel responses) {
        this.responses = responses;
    }

    @Override
    public Outcome run(ScenarioDraws d) {
        return new Outcome(responses.baselineDiscoverySeconds(d), responses.isLate(d));
    }
}

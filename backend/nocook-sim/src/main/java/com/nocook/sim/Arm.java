package com.nocook.sim;

/** One side of the A/B pair. Reads only the pre-drawn ScenarioDraws. */
public interface Arm<T> {
    T run(ScenarioDraws draws);
}

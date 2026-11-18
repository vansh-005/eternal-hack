package com.nocook.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** Seeded generator: same seed and n give the same draws, independent of assumptions. */
public final class ScenarioGenerator {
    private ScenarioGenerator() {}

    public static List<ScenarioDraws> generate(long seed, int n) {
        SplittableRandom rng = new SplittableRandom(seed);
        List<ScenarioDraws> draws = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            draws.add(new ScenarioDraws(i,
                    rng.nextDouble(), rng.nextDouble(), rng.nextDouble(),
                    rng.nextDouble(), rng.nextDouble(), rng.nextDouble(),
                    rng.nextDouble(), rng.nextDouble(), rng.nextDouble(),
                    rng.nextDouble(), rng.nextDouble(),
                    rng.nextDouble(), rng.nextDouble(),
                    rng.nextDouble(), rng.nextDouble(), rng.nextDouble(),
                    rng.nextDouble(),
                    rng.nextDouble(), rng.nextDouble()));
        }
        return draws;
    }
}

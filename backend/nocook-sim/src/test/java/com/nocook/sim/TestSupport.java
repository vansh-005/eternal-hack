package com.nocook.sim;

import java.nio.file.Files;
import java.nio.file.Path;

final class TestSupport {
    private TestSupport() {}

    static byte[] assumptionsBytes() throws Exception {
        for (String p : new String[]{"../../sim/assumptions-v1.json", "../sim/assumptions-v1.json", "sim/assumptions-v1.json"}) {
            if (Files.exists(Path.of(p))) return Files.readAllBytes(Path.of(p));
        }
        throw new IllegalStateException("assumptions-v1.json not found");
    }

    static Assumptions assumptions() throws Exception {
        return SimRunner.mapper().readValue(assumptionsBytes(), Assumptions.class);
    }

    /** An eligible CUSTOMER cancel where nobody ever responds. */
    static ScenarioDraws silentDraws(double stageU, double ovenToReadyU) {
        return new ScenarioDraws(1, stageU, 0.1, 0.5, 0.99, 0.99, ovenToReadyU, 0.99, 0.5, 0.5,
                0.99, 0.5, 0.99, 0.5, 0.99, 0.5, 0.5, 0.5, 0.99, 0.5);
    }
}

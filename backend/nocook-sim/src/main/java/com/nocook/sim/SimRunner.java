package com.nocook.sim;

import com.nocook.sim.PairRunner.ScenarioResult;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic core: seed + assumptions bytes + runId + commit in, summary and CSV bytes out.
 * Nothing wall-clock dependent is produced here (wallMs and startedAt live in manifest.json only).
 */
public final class SimRunner {
    private SimRunner() {}

    public record Output(byte[] summaryJson, byte[] scenariosCsv, List<ScenarioResult> results) {}

    static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public static JsonMapper mapper() {
        return MAPPER;
    }

    public static List<ScenarioResult> runAll(List<ScenarioDraws> draws, Assumptions assumptions) {
        PairRunner runner = PairRunner.standard(assumptions);
        List<ScenarioResult> results = new ArrayList<>(draws.size());
        for (ScenarioDraws d : draws) results.add(runner.run(d));
        return results;
    }

    @SuppressWarnings("unchecked")
    public static Output run(long seed, int n, byte[] assumptionsJson, String assumptionsSha256,
                             String runId, String commit) {
        Assumptions base = MAPPER.readValue(assumptionsJson, Assumptions.class);
        Map<String, Object> rawAssumptions = MAPPER.readValue(assumptionsJson, Map.class);
        List<ScenarioDraws> draws = ScenarioGenerator.generate(seed, n);
        List<ScenarioResult> results = runAll(draws, base);

        Map<String, Object> late = new LinkedHashMap<>();
        Map<String, Object> timeToStop = Aggregator.timeToStop(results, base.lateDiscoveryThresholdSeconds(), late);

        double ivrCallsPer1000 = Aggregator.ivrCallsPer1000(results);
        List<Map<String, Object>> sensitivity = new ArrayList<>();
        for (double rate : base.lateNoticeSensitivity()) {
            List<ScenarioResult> rerun = runAll(draws, base.withLateNoticeRate(rate));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lateNoticeRate", rate);
            row.put("per1000", Aggregator.round(Aggregator.per1000(rerun), 2));
            sensitivity.add(row);
        }

        Map<String, Object> rupees = new LinkedHashMap<>();
        rupees.put("total", Aggregator.totalAvoidedRupees(results));
        rupees.put("per1000Cancellations", Aggregator.round(Aggregator.per1000(results), 2));
        rupees.put("sensitivity", sensitivity);
        rupees.put("breakEven", breakEven(draws, base, ivrCallsPer1000));

        Map<String, Object> funnel = Aggregator.funnel(results);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("kind", "SIMULATED");
        summary.put("runId", runId);
        summary.put("seed", seed);
        summary.put("n", n);
        summary.put("assumptionsVersion", base.version());
        summary.put("assumptionsSha256", assumptionsSha256);
        summary.put("eligible", Aggregator.eligibleCount(results));
        summary.put("timeToStopSeconds", timeToStop);
        summary.put("lateDiscoveryRate", late);
        summary.put("rupeesAvoided", rupees);
        summary.put("funnel", funnel);
        summary.put("ivrCallsPer1000", Aggregator.round(ivrCallsPer1000, 2));
        summary.put("ivrOutcomes", Aggregator.ivrOutcomes(results));
        summary.put("statePaths", Aggregator.statePaths(results));
        summary.put("assumptions", Aggregator.flagAssumed(rawAssumptions));
        summary.put("commit", commit);

        byte[] summaryBytes = (MAPPER.writeValueAsString(summary) + "\n").getBytes(StandardCharsets.UTF_8);
        return new Output(summaryBytes, csv(results), results);
    }

    /** Late-notice rate at which rupees avoided per 1000 cancellations equals the assumed IVR spend per 1000. */
    private static Map<String, Object> breakEven(List<ScenarioDraws> draws, Assumptions base, double ivrCallsPer1000) {
        double ivrCostRupeesPer1000 = ivrCallsPer1000 * base.ivrCostPerCallPaise() / 100.0;
        double lo = 0.0;
        double hi = 1.0;
        Double rate;
        if (netAt(draws, base, lo, ivrCostRupeesPer1000) >= 0) {
            rate = 0.0;
        } else if (netAt(draws, base, hi, ivrCostRupeesPer1000) < 0) {
            rate = null;
        } else {
            for (int i = 0; i < 30; i++) {
                double mid = (lo + hi) / 2;
                if (netAt(draws, base, mid, ivrCostRupeesPer1000) >= 0) hi = mid; else lo = mid;
            }
            rate = Aggregator.round(hi, 5);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ivrCostPerCallPaise", base.ivrCostPerCallPaise());
        m.put("ivrCostRupeesPer1000Cancellations", Aggregator.round(ivrCostRupeesPer1000, 2));
        m.put("lateNoticeRate", rate);
        return m;
    }

    private static double netAt(List<ScenarioDraws> draws, Assumptions base, double rate, double ivrCost) {
        return Aggregator.per1000(runAll(draws, base.withLateNoticeRate(rate))) - ivrCost;
    }

    private static byte[] csv(List<ScenarioResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("id,stage,initiator,eligible,baselineStopS,nocookStopS,baselineCost,nocookCost,avoided,")
                .append("path,ivrCalled,ivrOutcome,statePath,baselineLate\n");
        for (ScenarioResult r : results) {
            sb.append(r.id()).append(',').append(r.stage()).append(',').append(r.initiator()).append(',')
                    .append(r.eligible()).append(',').append(Aggregator.round(r.baselineStopS(), 3)).append(',')
                    .append(Aggregator.round(r.nocookStopS(), 3)).append(',').append(r.baselineCost()).append(',')
                    .append(r.nocookCost()).append(',').append(r.avoidedRupees()).append(',').append(r.path())
                    .append(',').append(r.ivrCalled()).append(',').append(r.ivrOutcome() == null ? "" : r.ivrOutcome())
                    .append(',').append(r.statePath()).append(',').append(r.baselineLate()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}

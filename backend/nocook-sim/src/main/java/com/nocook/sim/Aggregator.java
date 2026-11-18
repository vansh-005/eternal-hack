package com.nocook.sim;

import com.nocook.sim.PairRunner.ScenarioResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure statistics over scenario results; builds the sim-summary.json structure. */
public final class Aggregator {
    private Aggregator() {}

    public static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    public static long totalAvoidedRupees(List<ScenarioResult> results) {
        long total = 0;
        for (ScenarioResult r : results) total += r.avoidedRupees();
        return total;
    }

    public static double per1000(List<ScenarioResult> results) {
        return totalAvoidedRupees(results) * 1000.0 / results.size();
    }

    public static double ivrCallsPer1000(List<ScenarioResult> results) {
        return results.stream().filter(ScenarioResult::ivrCalled).count() * 1000.0 / results.size();
    }

    /** Nearest-rank percentile of a sorted array. */
    static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }

    private static Map<String, Object> armStats(double[] sorted) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("p50", round(percentile(sorted, 0.50), 3));
        m.put("p95", round(percentile(sorted, 0.95), 3));
        m.put("p99", round(percentile(sorted, 0.99), 3));
        List<List<Number>> cdf = new ArrayList<>();
        int idx = 0;
        for (int x = 0; x <= 600; x += 5) {
            while (idx < sorted.length && sorted[idx] <= x) idx++;
            cdf.add(List.of(x, sorted.length == 0 ? 0.0 : round((double) idx / sorted.length, 5)));
        }
        m.put("cdf", cdf);
        return m;
    }

    private static double lateRate(double[] sorted, double threshold) {
        if (sorted.length == 0) return 0;
        int idx = 0;
        while (idx < sorted.length && sorted[idx] <= threshold) idx++;
        return round((double) (sorted.length - idx) / sorted.length, 5);
    }

    public static Map<String, Object> timeToStop(List<ScenarioResult> results, double lateThreshold,
                                                 Map<String, Object> lateOut) {
        double[] baseline = results.stream().filter(ScenarioResult::eligible)
                .mapToDouble(ScenarioResult::baselineStopS).sorted().toArray();
        double[] nocook = results.stream().filter(ScenarioResult::eligible)
                .mapToDouble(ScenarioResult::nocookStopS).sorted().toArray();
        lateOut.put("thresholdSeconds", (int) lateThreshold);
        lateOut.put("baseline", lateRate(baseline, lateThreshold));
        lateOut.put("nocook", lateRate(nocook, lateThreshold));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseline", armStats(baseline));
        out.put("nocook", armStats(nocook));
        return out;
    }

    public static Map<String, Object> funnel(List<ScenarioResult> results) {
        long opened = 0, card = 0, l1 = 0, lateTablet = 0, ivrCalled = 0, ivrAck = 0, expired = 0, superseded = 0;
        for (ScenarioResult r : results) {
            if (!r.eligible()) continue;
            opened++;
            if (r.ivrCalled()) ivrCalled++;
            switch (r.path()) {
                case "CARD_ACK" -> card++;
                case "L1_ACK" -> l1++;
                case "LATE_TABLET_ACK" -> lateTablet++;
                case "IVR_ACK" -> ivrAck++;
                case "EXPIRED" -> expired++;
                case "SUPERSEDED" -> superseded++;
                default -> throw new IllegalStateException("unexpected path " + r.path());
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("opened", opened);
        m.put("ackedOnCard", card);
        m.put("ackedAfterL1", l1);
        m.put("ivrCalled", ivrCalled);
        m.put("ackedByIvr", ivrAck);
        m.put("ackedTabletAfterIvr", lateTablet);
        m.put("expired", expired);
        m.put("superseded", superseded);
        return m;
    }

    public static Map<String, Object> ivrOutcomes(List<ScenarioResult> results) {
        Map<String, Long> counts = new TreeMap<>();
        for (ScenarioResult r : results) {
            if (r.ivrOutcome() != null) counts.merge(r.ivrOutcome(), 1L, Long::sum);
        }
        return new LinkedHashMap<>(counts);
    }

    public static Map<String, Object> statePaths(List<ScenarioResult> results) {
        Map<String, Long> counts = new TreeMap<>();
        for (ScenarioResult r : results) counts.merge(r.statePath(), 1L, Long::sum);
        return new LinkedHashMap<>(counts);
    }

    public static long eligibleCount(List<ScenarioResult> results) {
        return results.stream().filter(ScenarioResult::eligible).count();
    }

    /** Wrap every leaf of the assumptions tree as {"value": x, "assumed": true}. */
    @SuppressWarnings("unchecked")
    public static Object flagAssumed(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                out.put(e.getKey(), flagAssumed(e.getValue()));
            }
            return out;
        }
        Map<String, Object> leaf = new LinkedHashMap<>();
        leaf.put("value", node);
        leaf.put("assumed", true);
        return leaf;
    }

    public static double[] copy(double[] a) {
        return Arrays.copyOf(a, a.length);
    }
}

package com.nocook.sim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** CLI: java -jar nocook-sim.jar --seed 2026 --n 10000 --out ../results/sim-YYYYMMDD-HHMM [--assumptions file]. */
public final class SimMain {
    private SimMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        long seed = Long.parseLong(opts.getOrDefault("seed", "2026"));
        int n = Integer.parseInt(opts.getOrDefault("n", "10000"));
        Path out = Path.of(opts.getOrDefault("out", "results/sim-run"));
        Path assumptionsPath = resolveAssumptions(opts.get("assumptions"));

        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        byte[] assumptionsBytes = Files.readAllBytes(assumptionsPath);
        String sha = sha256(assumptionsBytes);
        String commit = gitCommit();
        String dirName = out.toAbsolutePath().normalize().getFileName().toString();
        String runId = opts.getOrDefault("run-id", dirName.startsWith("sim-") ? dirName : "sim-" + dirName);

        SimRunner.Output output = SimRunner.run(seed, n, assumptionsBytes, sha, runId, commit);
        long wallMs = (System.nanoTime() - startNanos) / 1_000_000;

        Files.createDirectories(out);
        Files.write(out.resolve("sim-summary.json"), output.summaryJson());
        Files.write(out.resolve("scenarios.csv"), output.scenariosCsv());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("kind", "SIMULATED");
        manifest.put("runId", runId);
        manifest.put("seed", seed);
        manifest.put("n", n);
        manifest.put("assumptionsFile", assumptionsPath.getFileName().toString());
        manifest.put("assumptionsSha256", sha);
        manifest.put("commit", commit);
        manifest.put("startedAt", startedAt.toString());
        manifest.put("wallMs", wallMs);
        Files.writeString(out.resolve("manifest.json"), SimRunner.mapper().writeValueAsString(manifest) + "\n");

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = SimRunner.mapper().readValue(output.summaryJson(), Map.class);
        System.out.println("runId " + runId + " -> " + out);
        System.out.println("n " + summary.get("n"));
        System.out.println("wallMs " + wallMs);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("usage: --seed N --n N --out DIR [--assumptions FILE] [--run-id ID]");
            }
            opts.put(args[i].substring(2), args[++i]);
        }
        return opts;
    }

    private static Path resolveAssumptions(String given) {
        if (given != null) return Path.of(given);
        for (String candidate : new String[]{"sim/assumptions-v1.json", "../sim/assumptions-v1.json"}) {
            if (Files.exists(Path.of(candidate))) return Path.of(candidate);
        }
        throw new IllegalArgumentException("assumptions-v1.json not found; pass --assumptions FILE");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Best effort: "unknown" when git is missing or this is not a checkout. */
    private static String gitCommit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes()).trim();
            return p.waitFor() == 0 && !text.isEmpty() ? text : "unknown";
        } catch (IOException | InterruptedException e) {
            return "unknown";
        }
    }
}

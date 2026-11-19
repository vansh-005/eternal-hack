package com.nocook.service.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the exported runs under {@code nocook.results-dir} (mounted read-only at /results). A run is a directory with a
 * {@code summary.json}; {@code sim-*} directories hold simulations and {@code tests-*.json} files are ignored here.
 */
@Component
public class ResultsReader {

    private static final Logger log = LoggerFactory.getLogger(ResultsReader.class);
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,120}");

    private final Path root;
    private final JsonMapper jsonMapper;

    public ResultsReader(@Value("${nocook.results-dir:/results}") String resultsDir, JsonMapper jsonMapper) {
        this.root = Path.of(resultsDir);
        this.jsonMapper = jsonMapper;
    }

    /** Headers (runId, profile, startedAt, verdict) of every run, newest directory name first. */
    public List<JsonNode> runHeaders() {
        List<JsonNode> headers = new ArrayList<>();
        for (Path dir : directories()) {
            String name = dir.getFileName().toString();
            if (name.startsWith("sim-")) {
                continue;
            }
            Optional<JsonNode> summary = read(dir.resolve("summary.json"));
            if (summary.isEmpty()) {
                continue;
            }
            JsonNode body = summary.get();
            ObjectNode header = jsonMapper.createObjectNode();
            header.put("runId", text(body, "runId", name));
            header.put("profile", text(body, "profile", ""));
            header.put("startedAt", text(body, "startedAt", ""));
            header.put("verdict", text(body, "verdict", ""));
            headers.add(header);
        }
        return headers;
    }

    /** {summary, invariants, manifest} of one run; empty when the id is unsafe or the run does not exist. */
    public Optional<JsonNode> run(String runId) {
        if (!SAFE_NAME.matcher(runId).matches()) {
            return Optional.empty();
        }
        Path dir = root.resolve(runId).normalize();
        if (!dir.startsWith(root.normalize())) {
            return Optional.empty();
        }
        Optional<JsonNode> summary = read(dir.resolve("summary.json"));
        if (summary.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode bundle = jsonMapper.createObjectNode();
        bundle.set("summary", summary.get());
        bundle.set("invariants", read(dir.resolve("invariants.json")).orElse(null));
        bundle.set("manifest", read(dir.resolve("manifest.json")).orElse(null));
        return Optional.of(bundle);
    }

    /** The newest {@code sim-*}/sim-summary.json. */
    public Optional<JsonNode> latestSimulation() {
        for (Path dir : directories()) {
            if (dir.getFileName().toString().startsWith("sim-")) {
                Optional<JsonNode> summary = read(dir.resolve("sim-summary.json"));
                if (summary.isPresent()) {
                    return summary;
                }
            }
        }
        return Optional.empty();
    }

    private List<Path> directories() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException ex) {
            log.warn("results dir unreadable dir={} reason={}", root, ex.toString());
            return List.of();
        }
    }

    private Optional<JsonNode> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(jsonMapper.readTree(Files.readString(file)));
        } catch (IOException | RuntimeException ex) {
            log.warn("results file unreadable file={} reason={}", file, ex.toString());
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : fallback;
    }
}

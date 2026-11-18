package com.nocook.service.it;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** TESTING.md section 6: one JSON line per R-test, collected later by tools/export-tests.mjs. */
public final class ItSummary {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ItSummary() {}

    public static void print(String test, Map<String, ?> measured, boolean passed) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("test", test);
        line.putAll(measured);
        line.put("result", passed ? "PASS" : "FAIL");
        System.out.println(JSON.writeValueAsString(line));
    }
}

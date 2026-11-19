package com.nocook.service.api;

import com.nocook.service.chaos.ChaosException;
import com.nocook.service.chaos.ChaosService;
import com.nocook.service.config.Iso;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** POST /api/ops/chaos/{action} (API.md section 1.6). The body is optional (reset sends {}). */
@RestController
@RequestMapping("/api/ops/chaos")
public class ChaosController {

    private final ChaosService chaos;

    public ChaosController(ChaosService chaos) {
        this.chaos = chaos;
    }

    @PostMapping("/{action}")
    public Map<String, Object> chaos(@PathVariable String action, @RequestBody(required = false) @Nullable JsonNode body) {
        Instant at = chaos.apply(action, body);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("at", Iso.format(at));
        return response;
    }

    @ExceptionHandler(ChaosException.class)
    public ResponseEntity<Map<String, Object>> onChaosFailure(ChaosException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiExceptionHandler.body("DEPENDENCY_UNAVAILABLE", ex.getMessage()));
    }
}

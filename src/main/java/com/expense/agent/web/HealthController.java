package com.expense.agent.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.Map;

@RestController
public class HealthController {
    private final DataSource ds;

    public HealthController(DataSource ds) {
        this.ds = ds;
    }

    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "expense-agent");
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> ready() {
        try (var c = ds.getConnection()) {
            if (c.isValid(2)) return ResponseEntity.ok(Map.of("status", "ready"));
            return ResponseEntity.status(503).body(Map.of("status", "db-invalid"));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("status", "db-down"));
        }
    }
}

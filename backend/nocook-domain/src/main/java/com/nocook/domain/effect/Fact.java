package com.nocook.domain.effect;

import java.util.Map;

public record Fact(FactKind kind, Map<String, String> metadata) {
    public Fact {
        metadata = Map.copyOf(metadata);
    }
}

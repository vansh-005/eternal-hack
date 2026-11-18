package com.nocook.domain.model;

import java.time.Duration;

public record Timings(Duration l1, Duration ivr, Duration maxAge, Duration deferBy) {
    public static Timings defaults() {
        return new Timings(Duration.ofSeconds(30), Duration.ofSeconds(75), Duration.ofMinutes(10), Duration.ofSeconds(2));
    }
}

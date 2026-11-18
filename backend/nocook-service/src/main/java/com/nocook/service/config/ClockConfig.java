package com.nocook.service.config;

import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Millisecond-truncated so every timestamp we emit round-trips through ISO-8601 with 3 digits. */
    @Bean
    public Clock clock() {
        return Clock.tickMillis(ZoneOffset.UTC);
    }
}

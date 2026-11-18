package com.nocook.service.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Switches {@code @Scheduled} workers (deadline worker, outbox dispatcher, reapers, gauge sampler) on or off.
 * {@code nocook.scheduling.enabled=false} lets tests drive {@code tick()} by hand. Default: enabled.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "nocook.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}

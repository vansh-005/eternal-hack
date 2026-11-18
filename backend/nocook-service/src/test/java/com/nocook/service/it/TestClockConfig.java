package com.nocook.service.it;

import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the production Clock, OMS port and card publisher in every IT (imported by {@link AbstractIT}). */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    public static final Instant T0 = Instant.parse("2026-11-21T14:45:00.000Z");

    @Bean
    public MutableClock mutableClock() {
        return new MutableClock(T0);
    }

    /** The Clock every service injects: the same instance as {@link #mutableClock()}. */
    @Bean
    @Primary
    public Clock testClock(MutableClock mutableClock) {
        return mutableClock;
    }

    @Bean
    @Primary
    public FakeOms fakeOms() {
        return new FakeOms();
    }

    @Bean
    @Primary
    public RecordingCardPublisher recordingCardPublisher() {
        return new RecordingCardPublisher();
    }
}

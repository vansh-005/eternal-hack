package com.nocook.oms.config;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** UUIDv7 generator driven by the injected Clock (48-bit unix ms, version 7, random tail). */
@Component
public class Ids {

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public Ids(Clock clock) {
        this.clock = clock;
    }

    public UUID uuidV7() {
        long millis = clock.millis();
        long randomHigh = random.nextLong();
        long randomLow = random.nextLong();
        long mostSignificant = (millis << 16) | 0x7000L | (randomHigh & 0x0FFFL);
        long leastSignificant = (randomLow & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}

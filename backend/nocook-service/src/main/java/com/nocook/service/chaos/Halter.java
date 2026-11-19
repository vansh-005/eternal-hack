package com.nocook.service.chaos;

import org.springframework.stereotype.Component;

/** Seam for {@code Runtime.halt(1)} so tests can observe the call instead of killing the JVM. */
@Component
public class Halter {

    /** No shutdown hooks, no graceful stop: this is the crash the kill-replica chaos action simulates. */
    public void halt() {
        Runtime.getRuntime().halt(1);
    }
}

package com.nocook.domain.policy;

import com.nocook.domain.effect.Fact;
import com.nocook.domain.effect.FactKind;
import com.nocook.domain.model.*;

import java.time.Instant;
import java.util.UUID;

/** Test fixtures. */
final class Fx {
    static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");
    static final Timings T = Timings.defaults();

    private Fx() {}

    static CancellationEvent event() {
        return new CancellationEvent("evt-1", "ord-1", "out-1", 1, T0, CancelInitiator.CUSTOMER, null);
    }

    static OrderSnapshot cancelledSnapshot() {
        return new OrderSnapshot("ord-1", "out-1", 1, OrderStatus.CANCELLED, "CANCELLED",
                T0.minusSeconds(60), null, null, T0, CancelInitiator.CUSTOMER, 45000, "ref-1");
    }

    static OrderSnapshot snap(OrderStatus status, Instant accepted, Instant ready, Instant picked,
                              CancelInitiator init, int version) {
        return new OrderSnapshot("ord-1", "out-1", version, status, status.name(), accepted, ready, picked,
                status == OrderStatus.CANCELLED ? T0 : null, init, 45000, "ref-1");
    }

    static AssuranceCase aCase(CaseState state) {
        boolean closed = !state.isOpen();
        return new AssuranceCase(UUID.fromString("00000000-0000-0000-0000-000000000001"), "ord-1", 1, "out-1",
                state, 1, T0, T0.plusSeconds(1), closed ? T0.plusSeconds(5) : null, null,
                state == CaseState.ACKNOWLEDGED ? "key-1" : null);
    }

    static boolean hasFact(TransitionResult r, FactKind k) {
        return r.facts().stream().anyMatch(f -> f.kind() == k);
    }

    static Fact fact(TransitionResult r, FactKind k) {
        return r.facts().stream().filter(f -> f.kind() == k).findFirst().orElseThrow();
    }

    static Instant at(long seconds) { return T0.plusSeconds(seconds); }
}

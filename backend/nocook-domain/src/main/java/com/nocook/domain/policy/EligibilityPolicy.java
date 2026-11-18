package com.nocook.domain.policy;

import com.nocook.domain.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** DOMAIN.md section 2: eligibility rules 1-5, evaluated in order; first failure wins. */
public final class EligibilityPolicy {
    private EligibilityPolicy() {}

    public static Optional<NotActionableReason> evaluate(CancellationEvent e, OrderSnapshot s, Instant now, Timings t) {
        if (s.status() != OrderStatus.CANCELLED) return Optional.of(NotActionableReason.STALE_EVENT);
        if (s.acceptedAt() == null || !s.acceptedAt().isBefore(e.cancelledAt()))
            return Optional.of(NotActionableReason.NOT_ACCEPTED_BEFORE_CANCEL);
        CancelInitiator initiator = s.cancelInitiator() != null ? s.cancelInitiator() : e.initiatedBy();
        if (initiator == CancelInitiator.RESTAURANT) return Optional.of(NotActionableReason.RESTAURANT_INITIATED);
        if (s.readyAt() != null || s.pickedUpAt() != null) return Optional.of(NotActionableReason.READY_OR_PICKED_UP);
        if (Duration.between(e.cancelledAt(), now).compareTo(t.maxAge()) >= 0)
            return Optional.of(NotActionableReason.TOO_OLD);
        return Optional.empty();
    }
}

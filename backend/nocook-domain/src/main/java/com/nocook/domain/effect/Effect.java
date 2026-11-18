package com.nocook.domain.effect;

import com.nocook.domain.model.CardKind;
import com.nocook.domain.model.DeadlineKind;

import java.time.Instant;

public sealed interface Effect {
    record ScheduleDeadline(DeadlineKind kind, Instant dueAt) implements Effect {}

    /** deferral. */
    record RescheduleDeadline(DeadlineKind kind, Instant dueAt) implements Effect {}

    /** all PENDING for case. */
    record CancelPendingDeadlines() implements Effect {}

    record SendCard(CardKind kind) implements Effect {}

    record ReserveIvr() implements Effect {}

    record EndCall() implements Effect {}
}

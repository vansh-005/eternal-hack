package com.nocook.domain.event;

import com.nocook.domain.model.AckChannel;
import com.nocook.domain.model.AckChoice;
import com.nocook.domain.model.DeadlineKind;

import java.time.Instant;

public sealed interface CaseEvent {
    record DeadlineDue(DeadlineKind kind, Instant dueAt) implements CaseEvent {}

    record MerchantAck(AckChoice choice, AckChannel channel, String actor, String idempotencyKey) implements CaseEvent {}
}

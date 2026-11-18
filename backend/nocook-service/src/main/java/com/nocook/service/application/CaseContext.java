package com.nocook.service.application;

import com.nocook.domain.model.AckChannel;
import com.nocook.domain.model.AckChoice;
import com.nocook.domain.model.CaseState;
import com.nocook.domain.model.CloseReason;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What {@link EffectApplier} needs to know about the case a batch of effects belongs to, AFTER the transition
 * (state and version are the new ones). Card payloads are built from it.
 */
public record CaseContext(
        UUID caseId,
        String orderId,
        int orderVersion,
        String orderRef,
        String outletId,
        CaseState state,
        int caseVersionAfter,
        Instant cancelledAt,
        Instant openedAt,
        @Nullable CloseReason closeReason,
        @Nullable AckChoice ackChoice,
        @Nullable AckChannel ackChannel) {}

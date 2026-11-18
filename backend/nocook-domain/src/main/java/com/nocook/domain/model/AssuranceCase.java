package com.nocook.domain.model;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record AssuranceCase(UUID id, String orderId, int orderVersion, String outletId, CaseState state, int version,
                            Instant cancelledAt, Instant openedAt, @Nullable Instant closedAt,
                            @Nullable CloseReason closeReason, @Nullable String ackIdempotencyKey) {}

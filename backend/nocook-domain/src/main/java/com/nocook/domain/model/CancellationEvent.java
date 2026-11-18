package com.nocook.domain.model;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

public record CancellationEvent(String eventId, String orderId, String outletId, int orderVersion,
                                Instant cancelledAt, CancelInitiator initiatedBy, @Nullable String runId) {}

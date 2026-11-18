package com.nocook.domain.model;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

public record OrderSnapshot(String orderId, String outletId, int version, OrderStatus status, String stage,
                            @Nullable Instant acceptedAt, @Nullable Instant readyAt, @Nullable Instant pickedUpAt,
                            @Nullable Instant cancelledAt, @Nullable CancelInitiator cancelInitiator,
                            long valuePaise, String orderRef) {}

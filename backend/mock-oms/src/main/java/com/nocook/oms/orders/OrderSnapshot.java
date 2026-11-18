package com.nocook.oms.orders;

import com.nocook.oms.config.IsoMillis;
import com.nocook.oms.orders.OrderModel.CancelInitiator;
import com.nocook.oms.orders.OrderModel.OrderStatus;
import com.nocook.oms.orders.OrderModel.Stage;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** API.md section 2 OrderSnapshot. runId is internal (carried in events), not serialized. */
public record OrderSnapshot(
        String orderId,
        String outletId,
        int version,
        OrderStatus status,
        Stage stage,
        long valuePaise,
        String orderRef,
        @IsoMillis @Nullable Instant acceptedAt,
        @IsoMillis @Nullable Instant readyAt,
        @IsoMillis @Nullable Instant pickedUpAt,
        @IsoMillis @Nullable Instant deliveredAt,
        @IsoMillis @Nullable Instant cancelledAt,
        @Nullable CancelInitiator cancelInitiator,
        JsonNode items,
        @com.fasterxml.jackson.annotation.JsonIgnore @Nullable String runId) {}

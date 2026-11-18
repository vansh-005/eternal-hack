package com.nocook.oms.orders;

import java.util.List;

/** Enums and small value types of the order lifecycle (DATA_MODEL.md section 1). */
public final class OrderModel {

    private OrderModel() {}

    public enum OrderStatus {
        PLACED,
        ACCEPTED,
        PREPARING,
        READY,
        PICKED_UP,
        DELIVERED,
        CANCELLED;

        /** Legal targets per DATA_MODEL.md status table (reinstate is a separate test-only path). */
        public List<OrderStatus> allowedTargets() {
            return switch (this) {
                case PLACED -> List.of(ACCEPTED, CANCELLED);
                case ACCEPTED -> List.of(PREPARING, READY, CANCELLED);
                case PREPARING -> List.of(READY, CANCELLED);
                case READY -> List.of(PICKED_UP, CANCELLED);
                case PICKED_UP -> List.of(DELIVERED, CANCELLED);
                case DELIVERED, CANCELLED -> List.of();
            };
        }

        public boolean canMoveTo(OrderStatus target) {
            return allowedTargets().contains(target);
        }
    }

    public enum Stage {
        NOT_STARTED,
        PREP,
        OVEN,
        READY
    }

    public enum CancelInitiator {
        CUSTOMER,
        RESTAURANT,
        PLATFORM
    }

    public record OrderItem(String name, int qty, long pricePaise) {}
}

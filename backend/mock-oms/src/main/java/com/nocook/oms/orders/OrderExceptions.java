package com.nocook.oms.orders;

import com.nocook.oms.orders.OrderModel.OrderStatus;

public final class OrderExceptions {

    private OrderExceptions() {}

    public static class OrderNotFoundException extends RuntimeException {
        private final String orderId;

        public OrderNotFoundException(String orderId) {
            super("Order " + orderId + " not found");
            this.orderId = orderId;
        }

        public String orderId() {
            return orderId;
        }
    }

    public static class IllegalTransitionException extends RuntimeException {
        private final String orderId;
        private final OrderStatus state;

        public IllegalTransitionException(String orderId, OrderStatus state, String attempted) {
            super("Cannot " + attempted + " order in state " + state);
            this.orderId = orderId;
            this.state = state;
        }

        public String orderId() {
            return orderId;
        }

        public OrderStatus state() {
            return state;
        }
    }

    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }
}

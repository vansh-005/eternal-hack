package com.nocook.service.oms;

/** Thrown by the ingestor so the AMQP container requeues the delivery (fail closed, counts toward delivery limit). */
public class OmsUnavailableException extends RuntimeException {

    public OmsUnavailableException(String orderId, String reason) {
        super("OMS unavailable orderId=" + orderId + " reason=" + reason);
    }
}

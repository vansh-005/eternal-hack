package com.nocook.service.notifications;

/**
 * Publishes outbox payloads to MQTT at QoS 1 and returns only after PUBACK. B-03 provides the implementation
 * (HiveMQ client); the dispatcher stamps {@code sentAt} into the JSON before calling. Failures throw
 * {@link CardPublishException} and the dispatcher reschedules the outbox row. A PUBACK is a transport receipt, not
 * a human ACK (CLAUDE.md rule 5).
 */
public interface CardPublisher {

    /** Topic {@code nocook/outlets/{outletId}/cards}. */
    void publishCard(String outletId, String cardJson);

    /** Topic {@code nocook/outlets/{outletId}/calls}. */
    void publishCall(String outletId, String callJson);

    class CardPublishException extends RuntimeException {
        public CardPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

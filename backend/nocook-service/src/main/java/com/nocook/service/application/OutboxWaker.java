package com.nocook.service.application;

/**
 * Asks the outbox dispatcher to drain now instead of waiting for its next tick. Called after commit.
 * B-03's OutboxDispatcher implements this as a bean. Until then (or in tests) callers fall back to {@link #NOOP}
 * through {@code ObjectProvider<OutboxWaker>.getIfAvailable(() -> OutboxWaker.NOOP)}.
 */
public interface OutboxWaker {

    OutboxWaker NOOP = () -> {};

    void wake();
}

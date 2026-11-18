package com.nocook.domain.oms;

import com.nocook.domain.model.OrderSnapshot;

public sealed interface OmsView {
    record Available(OrderSnapshot snapshot) implements OmsView {}

    /** timeout, 5xx, connection refused. */
    record Unavailable(String reason) implements OmsView {}

    /** ACK and EXPIRY do not need a read. */
    record NotRead() implements OmsView {}
}

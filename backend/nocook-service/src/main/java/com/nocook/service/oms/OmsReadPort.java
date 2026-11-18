package com.nocook.service.oms;

import com.nocook.domain.oms.OmsView;

/**
 * The single OMS dependency (CLAUDE.md rules 1 and 3): a fresh read of one order, never cached, never retried.
 * Implementations never throw for transport problems; they return {@link OmsView.Unavailable}.
 * The result is {@link OmsView.Available} or {@link OmsView.Unavailable}, never {@link OmsView.NotRead}.
 * Tests swap this port for a fake bean.
 */
public interface OmsReadPort {

    OmsView get(String orderId);

    /** Same read, metered as path=escalation. Used by the deadline worker (B-04). */
    default OmsView getForEscalation(String orderId) {
        return get(orderId);
    }
}

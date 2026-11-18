package com.nocook.service.it;

import com.nocook.domain.model.OrderSnapshot;
import com.nocook.domain.oms.OmsView;
import com.nocook.service.oms.OmsReadPort;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/** In-memory OMS. Unknown orders and the global switch both answer {@link OmsView.Unavailable}. */
public class FakeOms implements OmsReadPort {

    private final Map<String, OrderSnapshot> snapshots = new ConcurrentHashMap<>();
    private volatile boolean unavailable;
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger escalationReads = new AtomicInteger();

    public void put(OrderSnapshot snapshot) {
        snapshots.put(snapshot.orderId(), snapshot);
    }

    public void update(String orderId, UnaryOperator<OrderSnapshot> change) {
        snapshots.computeIfPresent(orderId, (id, current) -> change.apply(current));
    }

    public void setUnavailable(boolean value) {
        this.unavailable = value;
    }

    public int reads() {
        return reads.get();
    }

    public int escalationReads() {
        return escalationReads.get();
    }

    public void reset() {
        snapshots.clear();
        unavailable = false;
        reads.set(0);
        escalationReads.set(0);
    }

    @Override
    public OmsView get(String orderId) {
        reads.incrementAndGet();
        return lookup(orderId);
    }

    @Override
    public OmsView getForEscalation(String orderId) {
        escalationReads.incrementAndGet();
        return lookup(orderId);
    }

    private OmsView lookup(String orderId) {
        if (unavailable) {
            return new OmsView.Unavailable("fake OMS switched off");
        }
        OrderSnapshot snapshot = snapshots.get(orderId);
        if (snapshot == null) {
            return new OmsView.Unavailable("unknown order " + orderId);
        }
        return new OmsView.Available(snapshot);
    }
}

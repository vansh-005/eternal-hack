package com.nocook.oms.orders;

import com.nocook.oms.config.Ids;
import com.nocook.oms.config.OmsMeters;
import com.nocook.oms.config.Timestamps;
import com.nocook.oms.config.Traceparent;
import com.nocook.oms.orders.OrderExceptions.IllegalTransitionException;
import com.nocook.oms.orders.OrderExceptions.InvalidRequestException;
import com.nocook.oms.orders.OrderExceptions.OrderNotFoundException;
import com.nocook.oms.orders.OrderModel.CancelInitiator;
import com.nocook.oms.orders.OrderModel.OrderItem;
import com.nocook.oms.orders.OrderModel.OrderStatus;
import com.nocook.oms.orders.OrderModel.Stage;
import com.nocook.oms.orders.OrderRepository.Mutation;
import com.nocook.oms.outbox.OutboxRelay;
import com.nocook.oms.outbox.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every public mutation is one transaction: update the order row (version + 1, timestamps from the Clock) and
 * insert the matching outbox event. The relay is woken after commit.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final OutboxRelay outboxRelay;
    private final Clock clock;
    private final Ids ids;
    private final JsonMapper jsonMapper;
    private final Traceparent traceparent;
    private final OmsMeters meters;

    public OrderService(
            OrderRepository orderRepository,
            OutboxRepository outboxRepository,
            OutboxRelay outboxRelay,
            Clock clock,
            Ids ids,
            JsonMapper jsonMapper,
            Traceparent traceparent,
            OmsMeters meters) {
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
        this.outboxRelay = outboxRelay;
        this.clock = clock;
        this.ids = ids;
        this.jsonMapper = jsonMapper;
        this.traceparent = traceparent;
        this.meters = meters;
    }

    @Transactional
    public OrderSnapshot create(String outletId, List<OrderItem> items, @Nullable String runId) {
        Instant now = clock.instant();
        long valuePaise = items.stream().mapToLong(item -> item.qty() * item.pricePaise()).sum();
        OrderSnapshot created = orderRepository.insert(
                outletId, OrderStatus.PLACED, Stage.NOT_STARTED, valuePaise, jsonMapper.writeValueAsString(items),
                runId, null, now);
        recordEvent("order.placed", created, now, null);
        meters.orderCreated();
        return created;
    }

    /** Load helper: an order that is already ACCEPTED (with a stage) in one transaction and one order.accepted event. */
    @Transactional
    public OrderSnapshot createAccepted(
            String outletId, Stage stage, long acceptedSecondsAgo, long valuePaise, @Nullable String runId) {
        Instant now = clock.instant();
        Instant acceptedAt = now.minusSeconds(acceptedSecondsAgo);
        String itemsJson = jsonMapper.writeValueAsString(List.of(new OrderItem("Margherita", 1, valuePaise)));
        OrderSnapshot created = orderRepository.insert(
                outletId, OrderStatus.ACCEPTED, stage, valuePaise, itemsJson, runId, acceptedAt, now);
        recordEvent("order.accepted", created, now, null);
        meters.orderCreated();
        return created;
    }

    @Transactional
    public OrderSnapshot accept(String orderId) {
        return moveTo(orderId, OrderStatus.ACCEPTED, "accept", "order.accepted", null);
    }

    @Transactional
    public OrderSnapshot setStage(String orderId, Stage targetStage) {
        if (targetStage != Stage.PREP && targetStage != Stage.OVEN) {
            throw new InvalidRequestException("stage must be PREP or OVEN");
        }
        Instant now = clock.instant();
        OrderSnapshot current = lock(orderId);
        boolean statusAllowsStage = current.status() == OrderStatus.ACCEPTED || current.status() == OrderStatus.PREPARING;
        boolean movesForward = targetStage.ordinal() > current.stage().ordinal();
        if (!statusAllowsStage || !movesForward) {
            throw new IllegalTransitionException(orderId, current.status(), "set stage " + targetStage);
        }
        Mutation mutation = new Mutation(OrderStatus.PREPARING, targetStage, null, null, null, null, null, null, false);
        return applyAndRecord(current, mutation, "order.stage_changed", now, null);
    }

    @Transactional
    public OrderSnapshot ready(String orderId) {
        return moveTo(orderId, OrderStatus.READY, "mark ready", "order.ready", null);
    }

    @Transactional
    public OrderSnapshot pickup(String orderId) {
        return moveTo(orderId, OrderStatus.PICKED_UP, "pick up", "order.picked_up", null);
    }

    @Transactional
    public OrderSnapshot deliver(String orderId) {
        return moveTo(orderId, OrderStatus.DELIVERED, "deliver", "order.delivered", null);
    }

    @Transactional
    public OrderSnapshot cancel(String orderId, CancelInitiator initiatedBy) {
        OrderSnapshot cancelled = moveTo(orderId, OrderStatus.CANCELLED, "cancel", "order.cancelled", initiatedBy);
        meters.orderCancelled();
        return cancelled;
    }

    /** Test-only path: CANCELLED to ACCEPTED, for the SUPERSEDED demo. */
    @Transactional
    public OrderSnapshot reinstate(String orderId) {
        Instant now = clock.instant();
        OrderSnapshot current = lock(orderId);
        if (current.status() != OrderStatus.CANCELLED) {
            throw new IllegalTransitionException(orderId, current.status(), "reinstate");
        }
        Mutation mutation = new Mutation(OrderStatus.ACCEPTED, current.stage(), null, null, null, null, null, null, true);
        return applyAndRecord(current, mutation, "order.reinstated", now, null);
    }

    @Transactional(readOnly = true)
    public OrderSnapshot get(String orderId) {
        return orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderSnapshot> listForOutlet(String outletId, boolean activeOnly) {
        return orderRepository.listForOutlet(outletId, activeOnly, 200);
    }

    private OrderSnapshot moveTo(
            String orderId,
            OrderStatus target,
            String verb,
            String routingKey,
            @Nullable CancelInitiator initiatedBy) {
        Instant now = clock.instant();
        OrderSnapshot current = lock(orderId);
        if (!current.status().canMoveTo(target)) {
            throw new IllegalTransitionException(orderId, current.status(), verb);
        }
        Mutation mutation = mutationFor(current, target, initiatedBy, now);
        return applyAndRecord(current, mutation, routingKey, now, initiatedBy);
    }

    private Mutation mutationFor(OrderSnapshot current, OrderStatus target, @Nullable CancelInitiator initiatedBy, Instant now) {
        return switch (target) {
            case ACCEPTED -> new Mutation(target, current.stage(), now, null, null, null, null, null, false);
            case READY -> new Mutation(target, Stage.READY, null, now, null, null, null, null, false);
            case PICKED_UP -> new Mutation(target, current.stage(), null, null, now, null, null, null, false);
            case DELIVERED -> new Mutation(target, current.stage(), null, null, null, now, null, null, false);
            case CANCELLED -> new Mutation(target, current.stage(), null, null, null, null, now, initiatedBy, false);
            case PLACED, PREPARING -> throw new IllegalStateException("not a direct target: " + target);
        };
    }

    private OrderSnapshot lock(String orderId) {
        return orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private OrderSnapshot applyAndRecord(
            OrderSnapshot current, Mutation mutation, String routingKey, Instant now, @Nullable CancelInitiator initiatedBy) {
        OrderSnapshot updated = orderRepository.apply(current.orderId(), mutation, now);
        recordEvent(routingKey, updated, now, initiatedBy);
        return updated;
    }

    /** Inserts the outbox row (API.md section 3 payload plus "traceparent") and wakes the relay after commit. */
    private void recordEvent(String routingKey, OrderSnapshot order, Instant occurredAt, @Nullable CancelInitiator initiatedBy) {
        UUID eventId = ids.uuidV7();
        ObjectNode payload = jsonMapper.createObjectNode();
        payload.put("schemaVersion", 1);
        payload.put("eventId", eventId.toString());
        payload.put("type", routingKey);
        payload.put("orderId", order.orderId());
        payload.put("outletId", order.outletId());
        payload.put("orderVersion", order.version());
        payload.put("occurredAt", Timestamps.format(occurredAt));
        payload.put("initiatedBy", initiatedBy == null ? null : initiatedBy.name());
        payload.put("runId", order.runId());
        payload.put("synthetic", false);
        payload.put(OutboxRelay.TRACEPARENT_KEY, traceparent.current());
        outboxRepository.insert(eventId, routingKey, jsonMapper.writeValueAsString(payload));
        wakeRelayAfterCommit();
    }

    private void wakeRelayAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            outboxRelay.wake();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                outboxRelay.wake();
            }
        });
    }
}

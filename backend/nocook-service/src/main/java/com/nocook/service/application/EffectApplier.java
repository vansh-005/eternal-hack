package com.nocook.service.application;

import com.nocook.domain.effect.Effect;
import com.nocook.domain.effect.Fact;
import com.nocook.domain.effect.FactKind;
import com.nocook.domain.model.CardKind;
import com.nocook.domain.model.Timings;
import com.nocook.service.config.Iso;
import com.nocook.service.config.TraceIds;
import com.nocook.service.observability.NocookMeters;
import com.nocook.service.persistence.CallAttemptRepository;
import com.nocook.service.persistence.CaseEventRepository;
import com.nocook.service.persistence.DeadlineRepository;
import com.nocook.service.persistence.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns domain effects into repository writes (DOMAIN.md section 5). Must run inside the caller's transaction.
 * The switch is exhaustive over the sealed {@link Effect}, so a new effect type fails compilation here.
 */
@Component
public class EffectApplier {

    public static final String CALL_SCRIPT_TEMPLATE = "Kitchen cancellation alert. Order ending %s is cancelled. "
            + "Press 1 if you stopped preparing; press 2 if preparation had not started; press 3 if it was already cooked.";

    private final DeadlineRepository deadlines;
    private final OutboxRepository outbox;
    private final CallAttemptRepository callAttempts;
    private final CaseEventRepository caseEvents;
    private final NocookMeters meters;
    private final TraceIds traceIds;
    private final JsonMapper jsonMapper;
    private final Timings timings;

    public EffectApplier(
            DeadlineRepository deadlines,
            OutboxRepository outbox,
            CallAttemptRepository callAttempts,
            CaseEventRepository caseEvents,
            NocookMeters meters,
            TraceIds traceIds,
            JsonMapper jsonMapper,
            Timings timings) {
        this.deadlines = deadlines;
        this.outbox = outbox;
        this.callAttempts = callAttempts;
        this.caseEvents = caseEvents;
        this.meters = meters;
        this.traceIds = traceIds;
        this.jsonMapper = jsonMapper;
        this.timings = timings;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(CaseContext context, List<Effect> effects, Instant now) {
        for (Effect effect : effects) {
            applyOne(context, effect, now);
        }
    }

    private void applyOne(CaseContext context, Effect effect, Instant now) {
        switch (effect) {
            case Effect.ScheduleDeadline schedule ->
                    deadlines.insertIfAbsent(context.caseId(), schedule.kind(), schedule.dueAt());
            case Effect.RescheduleDeadline reschedule ->
                    deadlines.reschedule(context.caseId(), reschedule.kind(), reschedule.dueAt());
            case Effect.CancelPendingDeadlines ignored -> deadlines.cancelOpenForCase(context.caseId());
            case Effect.SendCard sendCard -> writeCardIntent(context, sendCard.kind(), now);
            case Effect.ReserveIvr ignored -> reserveIvr(context, now);
            case Effect.EndCall ignored -> writeEndCallIntent(context, now);
        }
    }

    /** Stable id of a card; equals the outbox action_key and the MQTT messageId. */
    public static String cardActionKey(UUID caseId, CardKind kind, int caseVersionAfter) {
        return "card:" + caseId + ":" + kind.name() + ":" + caseVersionAfter;
    }

    public static String placeCallActionKey(UUID caseId) {
        return "ivr:" + caseId;
    }

    public static String incomingCallActionKey(UUID caseId) {
        return "call:" + caseId;
    }

    public static String endCallActionKey(UUID caseId) {
        return "callend:" + caseId;
    }

    private void writeCardIntent(CaseContext context, CardKind kind, Instant now) {
        String actionKey = cardActionKey(context.caseId(), kind, context.caseVersionAfter());
        String payload = jsonMapper.writeValueAsString(cardPayload(context, kind, actionKey));
        outbox.insertIfAbsent(actionKey, OutboxRepository.TYPE_MQTT_CARD, context.caseId(), payload, now, now);
    }

    /** DOMAIN.md section 5 ReserveIvr: unique call_attempts row, then the PLACE_CALL intent; a duplicate adds nothing. */
    private void reserveIvr(CaseContext context, Instant now) {
        Optional<UUID> attemptId = callAttempts.reserve(context.caseId(), now);
        if (attemptId.isEmpty()) {
            caseEvents.insertSystem(
                    context.caseId(), new Fact(FactKind.IVR_DUPLICATE_PREVENTED, Map.of()), now, traceIds.current());
            meters.ivrDuplicatePrevented();
            return;
        }
        caseEvents.insertSystem(
                context.caseId(),
                new Fact(FactKind.IVR_RESERVED, Map.of("callAttemptId", attemptId.get().toString())),
                now,
                traceIds.current());
        meters.ivrReserved();
        ObjectNode payload = jsonMapper.createObjectNode();
        payload.put("caseId", context.caseId().toString());
        payload.put("outletId", context.outletId());
        payload.put("orderRef", context.orderRef());
        payload.put("callAttemptId", attemptId.get().toString());
        outbox.insertIfAbsent(
                placeCallActionKey(context.caseId()),
                OutboxRepository.TYPE_PLACE_CALL,
                context.caseId(),
                jsonMapper.writeValueAsString(payload),
                now,
                now);
    }

    private void writeEndCallIntent(CaseContext context, Instant now) {
        String providerCallId = callAttempts
                .findByCase(context.caseId())
                .map(CallAttemptRepository.CallAttempt::providerCallId)
                .orElse(null);
        String actionKey = endCallActionKey(context.caseId());
        ObjectNode call = callBase(context, actionKey, "CALL_ENDED", providerCallId);
        call.putNull("script");
        outbox.insertIfAbsent(
                actionKey,
                OutboxRepository.TYPE_MQTT_CALL,
                context.caseId(),
                jsonMapper.writeValueAsString(call),
                now,
                now);
    }

    /** CallMessage of API.md section 4 without {@code sentAt}. */
    public ObjectNode callBase(CaseContext context, String messageId, String kind, @Nullable String providerCallId) {
        ObjectNode call = jsonMapper.createObjectNode();
        call.put("messageId", messageId);
        call.put("kind", kind);
        call.put("caseId", context.caseId().toString());
        putNullable(call, "providerCallId", providerCallId);
        call.put("orderRef", context.orderRef());
        call.put("outletId", context.outletId());
        return call;
    }

    /**
     * CardMessage of API.md section 4 without {@code sentAt}: the dispatcher stamps that when it publishes to
     * {@code nocook/outlets/{outletId}/cards}.
     */
    private ObjectNode cardPayload(CaseContext context, CardKind kind, String messageId) {
        ObjectNode card = jsonMapper.createObjectNode();
        card.put("messageId", messageId);
        card.put("kind", kind.name());
        card.put("caseId", context.caseId().toString());
        card.put("caseVersion", context.caseVersionAfter());
        card.put("orderId", context.orderId());
        card.put("orderRef", context.orderRef());
        card.put("orderVersion", context.orderVersion());
        card.put("outletId", context.outletId());
        card.put("state", context.state().name());
        card.put("cancelledAt", Iso.format(context.cancelledAt()));
        card.put("openedAt", Iso.format(context.openedAt()));
        card.put("l1At", Iso.format(context.cancelledAt().plus(timings.l1())));
        card.put("ivrAt", Iso.format(context.cancelledAt().plus(timings.ivr())));
        card.put("expiresAt", Iso.format(context.cancelledAt().plus(timings.maxAge())));
        putNullable(card, "closeReason", context.closeReason() == null ? null : context.closeReason().name());
        putNullable(card, "ackChoice", context.ackChoice() == null ? null : context.ackChoice().name());
        putNullable(card, "ackChannel", context.ackChannel() == null ? null : context.ackChannel().name());
        return card;
    }

    private static void putNullable(ObjectNode node, String field, @Nullable String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
}

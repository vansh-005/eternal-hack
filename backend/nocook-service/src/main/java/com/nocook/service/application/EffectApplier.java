package com.nocook.service.application;

import com.nocook.domain.effect.Effect;
import com.nocook.domain.model.CardKind;
import com.nocook.domain.model.Timings;
import com.nocook.service.config.Iso;
import com.nocook.service.persistence.DeadlineRepository;
import com.nocook.service.persistence.OutboxRepository;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns domain effects into repository writes (DOMAIN.md section 5). Must run inside the caller's transaction.
 *
 * <p>Extension point for B-04 / B-06: add the body of the matching {@code case} in {@link #applyOne}
 * (RescheduleDeadline, CancelPendingDeadlines, ReserveIvr, EndCall). The switch is exhaustive over the sealed
 * {@link Effect}, so a new effect type fails compilation here.
 */
@Component
public class EffectApplier {

    private final DeadlineRepository deadlines;
    private final OutboxRepository outbox;
    private final JsonMapper jsonMapper;
    private final Timings timings;

    public EffectApplier(
            DeadlineRepository deadlines, OutboxRepository outbox, JsonMapper jsonMapper, Timings timings) {
        this.deadlines = deadlines;
        this.outbox = outbox;
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
            case Effect.SendCard sendCard -> writeCardIntent(context, sendCard.kind(), now);
            case Effect.RescheduleDeadline ignored ->
                    throw new UnsupportedOperationException("TODO B-04: RescheduleDeadline");
            case Effect.CancelPendingDeadlines ignored ->
                    throw new UnsupportedOperationException("TODO B-04: CancelPendingDeadlines");
            case Effect.ReserveIvr ignored -> throw new UnsupportedOperationException("TODO B-06: ReserveIvr");
            case Effect.EndCall ignored -> throw new UnsupportedOperationException("TODO B-06: EndCall");
        }
    }

    /** Stable id of a card; equals the outbox action_key and the MQTT messageId. */
    public static String cardActionKey(java.util.UUID caseId, CardKind kind, int caseVersionAfter) {
        return "card:" + caseId + ":" + kind.name() + ":" + caseVersionAfter;
    }

    private void writeCardIntent(CaseContext context, CardKind kind, Instant now) {
        String actionKey = cardActionKey(context.caseId(), kind, context.caseVersionAfter());
        String payload = jsonMapper.writeValueAsString(cardPayload(context, kind, actionKey));
        outbox.insertIfAbsent(actionKey, OutboxRepository.TYPE_MQTT_CARD, context.caseId(), payload, now, now);
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

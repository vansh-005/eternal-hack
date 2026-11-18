package com.nocook.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocook.domain.model.DeadlineKind;
import com.nocook.domain.model.Outcome;
import com.nocook.domain.oms.OmsView;
import com.nocook.service.application.EscalationService;
import com.nocook.service.application.EscalationService.EscalationResult;
import com.nocook.service.persistence.DeadlineRepository.ClaimedDeadline;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** B-04: EscalationService applies one claimed deadline; includes the "supersede while a call is active" gap. */
class EscalationServiceIT extends B0xBaseIT {

    @Autowired
    EscalationService escalation;

    private UUID caseInIvr() {
        UUID caseId = openCase("outlet-it");
        clock.advance(Duration.ofSeconds(75));
        deadlineWorker.tick();
        assertThat(state(caseId)).isEqualTo("ESCALATED_IVR");
        return caseId;
    }

    @Test
    void supersede_while_escalated_ivr_also_queues_call_ended() {
        UUID caseId = caseInIvr();
        markReady(orderIdOf(caseId));
        ClaimedDeadline stray = new ClaimedDeadline(UUID.randomUUID(), caseId, DeadlineKind.IVR, clock.instant(), 1);

        EscalationResult result = escalation.applyDeadline(stray, oms.get(orderIdOf(caseId)));

        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(state(caseId)).isEqualTo("SUPERSEDED");
        assertThat(outboxRows(caseId)).extracting(r -> r.get("action_key"))
                .contains("callend:" + caseId, "card:" + caseId + ":CLOSE:4");
    }

    @Test
    void deadline_on_a_closed_case_is_a_noop_and_changes_nothing() {
        UUID caseId = openCase("outlet-it");
        assertThat(ack(caseId, "outlet-it", "k1", "STOPPED").status()).isEqualTo(200);
        int versionBefore = jdbc.sql("select version from nocook.cases where id = :id").param("id", caseId)
                .query(Integer.class).single();
        ClaimedDeadline stray = new ClaimedDeadline(UUID.randomUUID(), caseId, DeadlineKind.L1, clock.instant(), 1);

        EscalationResult result = escalation.applyDeadline(stray, new OmsView.NotRead());

        assertThat(result.outcome()).isEqualTo(Outcome.NO_OP);
        assertThat(state(caseId)).isEqualTo("ACKNOWLEDGED");
        assertThat(jdbc.sql("select version from nocook.cases where id = :id").param("id", caseId)
                        .query(Integer.class).single()).isEqualTo(versionBefore);
        assertThat(factKinds(caseId)).doesNotContain("L1_TRIGGERED");
    }

    @Test
    void missing_case_is_a_noop() {
        ClaimedDeadline orphan =
                new ClaimedDeadline(UUID.randomUUID(), UUID.randomUUID(), DeadlineKind.L1, clock.instant(), 1);

        EscalationResult result = escalation.applyDeadline(orphan, new OmsView.NotRead());

        assertThat(result.outcome()).isEqualTo(Outcome.NO_OP);
        assertThat(result.previousState()).isNull();
    }

    @Test
    void version_advance_alone_records_a_fact_and_still_escalates() {
        UUID caseId = openCase("outlet-it");
        var snapshot = oms.snapshots.get(orderIdOf(caseId));
        oms.snapshots.put(snapshot.orderId(), new com.nocook.domain.model.OrderSnapshot(snapshot.orderId(),
                snapshot.outletId(), snapshot.version() + 1, snapshot.status(), "OVEN", snapshot.acceptedAt(), null, null,
                snapshot.cancelledAt(), snapshot.cancelInitiator(), snapshot.valuePaise(), snapshot.orderRef()));
        clock.advance(Duration.ofSeconds(30));

        deadlineWorker.tick();

        assertThat(state(caseId)).isEqualTo("ESCALATED_L1");
        assertThat(factKinds(caseId)).contains("ORDER_VERSION_ADVANCED", "L1_TRIGGERED");
    }
}

package com.nocook.service.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocook.domain.model.OrderStatus;
import com.nocook.service.scheduling.DeadlineWorker;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** R7: OMS says READY (or ACCEPTED after a reinstate) when L1 fires: case is SUPERSEDED, escalation blocked. */
class SupersededAtEscalationIT extends AbstractIT {

    @Autowired DeadlineWorker worker;

    @Test
    void order_ready_at_l1_supersedes_the_case_and_queues_a_close_card() {
        UUID caseId = openEligibleCase("ord-r7a");
        fakeOms.update("ord-r7a", s -> new com.nocook.domain.model.OrderSnapshot(s.orderId(), s.outletId(),
                s.version() + 1, OrderStatus.READY, s.stage(), s.acceptedAt(), clock.instant().plusSeconds(1),
                null, null, null, s.valuePaise(), s.orderRef()));
        clock.advance(Duration.ofSeconds(31));

        worker.tick();

        assertSuperseded(caseId);
    }

    @Test
    void order_reinstated_to_accepted_at_l1_supersedes_the_case() {
        UUID caseId = openEligibleCase("ord-r7b");
        fakeOms.update("ord-r7b", s -> new com.nocook.domain.model.OrderSnapshot(s.orderId(), s.outletId(),
                s.version() + 1, OrderStatus.ACCEPTED, s.stage(), s.acceptedAt(), null, null, null, null,
                s.valuePaise(), s.orderRef()));
        clock.advance(Duration.ofSeconds(31));

        worker.tick();

        assertSuperseded(caseId);
    }

    private void assertSuperseded(UUID caseId) {
        long closeCards = jdbc.sql("select count(*) from nocook.outbox where case_id = :id and payload ->> 'kind' = 'CLOSE'")
                .param("id", caseId).query(Long.class).single();
        long openDeadlines = jdbc.sql("select count(*) from nocook.deadlines where case_id = :id"
                + " and status in ('PENDING','LEASED')").param("id", caseId).query(Long.class).single();
        long cancelled = jdbc.sql("select count(*) from nocook.deadlines where case_id = :id and status = 'CANCELLED'")
                .param("id", caseId).query(Long.class).single();
        ItSummary.print("R7", Map.of("state", stateOf(caseId), "closeCards", closeCards, "cancelledDeadlines", cancelled),
                stateOf(caseId).equals("SUPERSEDED") && closeCards == 1);
        assertThat(stateOf(caseId)).isEqualTo("SUPERSEDED");
        assertThat(factKinds(caseId)).contains("ESCALATION_BLOCKED");
        assertThat(closeCards).isEqualTo(1);
        assertThat(openDeadlines).isZero();
        assertThat(cancelled).isGreaterThanOrEqualTo(1);
    }
}

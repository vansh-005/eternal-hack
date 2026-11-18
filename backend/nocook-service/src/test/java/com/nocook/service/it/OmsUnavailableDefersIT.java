package com.nocook.service.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocook.service.scheduling.DeadlineWorker;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** R5: OMS down at L1 means defer (fail closed); when it is back, the next tick escalates. */
class OmsUnavailableDefersIT extends AbstractIT {

    @Autowired DeadlineWorker worker;

    @Test
    void l1_is_deferred_by_two_seconds_while_oms_is_down_then_escalates_when_it_returns() {
        UUID caseId = openEligibleCase("ord-r5");
        clock.advance(Duration.ofSeconds(31));
        Instant tickAt = clock.instant();
        fakeOms.setUnavailable(true);

        worker.tick();

        Instant deferredTo = jdbc.sql("select due_at from nocook.deadlines where case_id = :id and kind = 'L1'")
                .param("id", caseId).query((rs, n) -> rs.getTimestamp("due_at").toInstant()).single();
        String deadlineStatus = jdbc.sql("select status from nocook.deadlines where case_id = :id and kind = 'L1'")
                .param("id", caseId).query(String.class).single();
        assertThat(stateOf(caseId)).isEqualTo("AWAITING_ACK");
        assertThat(deadlineStatus).isEqualTo("PENDING");
        assertThat(deferredTo).isEqualTo(tickAt.plusSeconds(2));
        assertThat(factKinds(caseId)).contains("ESCALATION_DEFERRED");

        fakeOms.setUnavailable(false);
        clock.advance(Duration.ofSeconds(2));
        worker.tick();

        ItSummary.print("R5", Map.of("deferredToMs", deferredTo.toEpochMilli() - tickAt.toEpochMilli(),
                "finalState", stateOf(caseId)), stateOf(caseId).equals("ESCALATED_L1"));
        assertThat(stateOf(caseId)).isEqualTo("ESCALATED_L1");
        assertThat(factKinds(caseId)).contains("L1_TRIGGERED");
    }
}

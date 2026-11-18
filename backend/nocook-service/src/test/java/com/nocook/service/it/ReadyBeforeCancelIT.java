package com.nocook.service.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocook.domain.model.CancelInitiator;
import com.nocook.domain.model.OrderSnapshot;
import com.nocook.domain.model.OrderStatus;
import com.nocook.service.application.CaseOpeningService.OpenOutcome;
import com.nocook.service.application.CaseOpeningService.OpenResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R6: food marked READY 5 ms before the cancel is not actionable: no deadlines, no outbox. */
class ReadyBeforeCancelIT extends AbstractIT {

    @Test
    void ready_five_ms_before_cancel_is_not_actionable_with_no_deadlines_and_no_outbox() {
        Instant cancelledAt = clock.instant().minusMillis(500);
        OrderSnapshot snapshot = new OrderSnapshot("ord-r6", "outlet-it", 2, OrderStatus.CANCELLED, "OVEN",
                clock.instant().minus(Duration.ofMinutes(2)), cancelledAt.minusMillis(5), null, cancelledAt,
                CancelInitiator.CUSTOMER, 45000, "4418");
        fakeOms.put(snapshot);

        OpenResult result = ingestor.ingest(eventFor(snapshot), null);

        String reason = jdbc.sql("select not_actionable_reason from nocook.cases where id = :id")
                .param("id", result.caseId()).query(String.class).single();
        long deadlines = count("nocook.deadlines");
        long outbox = count("nocook.outbox");
        ItSummary.print("R6", Map.of("deadlines", deadlines, "outbox", outbox, "reason", String.valueOf(reason)),
                result.outcome() == OpenOutcome.NOT_ACTIONABLE && deadlines == 0 && outbox == 0);

        assertThat(result.outcome()).isEqualTo(OpenOutcome.NOT_ACTIONABLE);
        assertThat(stateOf(result.caseId())).isEqualTo("NOT_ACTIONABLE");
        assertThat(deadlines).isZero();
        assertThat(outbox).isZero();
        assertThat(cards.all()).isEmpty();
    }
}

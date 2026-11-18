package com.nocook.service.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocook.service.scheduling.OutboxDispatcher;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** R9: case + outbox committed, dispatcher "crashes" before dispatching; later it publishes exactly once. */
class OutboxCrashAfterCommitIT extends AbstractIT {

    @Autowired OutboxDispatcher dispatcher;

    @Test
    void card_is_published_exactly_once_with_the_outbox_action_key_as_message_id() {
        UUID caseId = openEligibleCase("ord-r9");
        String actionKey = jdbc.sql("select action_key from nocook.outbox where case_id = :id")
                .param("id", caseId).query(String.class).single();
        assertThat(cards.all()).as("nothing dispatched before the dispatcher runs").isEmpty();

        dispatcher.tick();
        dispatcher.tick();

        List<String> ids = cards.messageIds();
        String status = jdbc.sql("select status from nocook.outbox where case_id = :id").param("id", caseId)
                .query(String.class).single();
        ItSummary.print("R9", Map.of("published", ids.size(), "status", status),
                ids.size() == 1 && status.equals("SENT"));
        assertThat(ids).containsExactly(actionKey);
        assertThat(status).isEqualTo("SENT");
        assertThat(factKinds(caseId)).contains("CARD_DISPATCHED");
    }

    @Test
    void broker_failure_reschedules_the_row_and_still_ends_with_one_publish() {
        UUID caseId = openEligibleCase("ord-r9b");
        cards.failNext(1);

        dispatcher.tick();
        assertThat(cards.all()).isEmpty();
        assertThat(jdbc.sql("select status from nocook.outbox where case_id = :id").param("id", caseId)
                .query(String.class).single()).isEqualTo("PENDING");

        clock.advance(Duration.ofSeconds(2));
        dispatcher.tick();
        dispatcher.tick();

        assertThat(cards.all()).hasSize(1);
    }
}

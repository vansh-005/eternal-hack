package com.nocook.domain.policy;

import com.nocook.domain.event.CaseEvent;
import com.nocook.domain.event.CaseEvent.DeadlineDue;
import com.nocook.domain.event.CaseEvent.MerchantAck;
import com.nocook.domain.model.*;
import com.nocook.domain.oms.OmsView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.nocook.domain.policy.Fx.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TransitionTableCoverageTest {

    static List<CaseEvent> events() {
        List<CaseEvent> l = new ArrayList<>();
        for (DeadlineKind k : DeadlineKind.values()) l.add(new DeadlineDue(k, at(30)));
        l.add(new MerchantAck(AckChoice.STOPPED, AckChannel.TABLET, "dev", "key-1"));
        l.add(new MerchantAck(AckChoice.NOT_STARTED, AckChannel.IVR, "call", "other"));
        return l;
    }

    static List<OmsView> views() {
        return List.of(new OmsView.Available(cancelledSnapshot()), new OmsView.Unavailable("down"), new OmsView.NotRead());
    }

    @Test
    void every_state_event_view_combination_has_a_defined_outcome() {
        int n = 0;
        for (CaseState s : CaseState.values()) {
            for (CaseEvent ev : events()) {
                for (OmsView v : views()) {
                    final CaseState st = s;
                    assertThatCode(() -> {
                        TransitionResult r = CasePolicy.transition(aCase(st), ev, v, at(30), T);
                        assertThat(r).isNotNull();
                        assertThat(r.outcome()).isNotNull();
                        assertThat(r.nextState()).isNotNull();
                        assertThat(r.facts()).isNotNull();
                        assertThat(r.effects()).isNotNull();
                        if (r.outcome() == Outcome.REJECTED) assertThat(r.rejectionCode()).isNotNull();
                        if (r.outcome() != Outcome.APPLIED) assertThat(r.nextState()).isEqualTo(st);
                    }).as("%s %s %s", s, ev, v).doesNotThrowAnyException();
                    n++;
                }
            }
        }
        assertThat(n).isEqualTo(7 * 5 * 3);
    }
}

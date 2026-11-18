package com.nocook.sim;

import com.nocook.domain.model.CaseState;
import com.nocook.domain.model.Timings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatePathTest {
    @Test
    void noAckEndsExpiredThroughRealCasePolicy() throws Exception {
        Assumptions a = TestSupport.assumptions();
        NoCookArm.Result r = new NoCookArm(a, Timings.defaults()).run(TestSupport.silentDraws(0.1, 0.99));
        assertThat(r.eligible()).isTrue();
        assertThat(r.finalState()).isEqualTo(CaseState.EXPIRED);
        assertThat(r.statePath()).isEqualTo("AWAITING_ACK>ESCALATED_L1>ESCALATED_IVR>EXPIRED");
        assertThat(r.ivrCalled()).isTrue();
        assertThat(r.ackAtSeconds()).isNull();
    }

    @Test
    void readyBeforeL1EndsSuperseded() throws Exception {
        Assumptions base = TestSupport.assumptions();
        Assumptions.KitchenSeconds fastKitchen = new Assumptions.KitchenSeconds(
                base.kitchenSecondsToNextStage().notStartedToPrep(),
                base.kitchenSecondsToNextStage().prepToOven(),
                new Assumptions.Range(5, 10));
        Assumptions a = new Assumptions(base.version(), base.stageAtCancel(), base.initiator(), base.orderValueRupees(),
                fastKitchen, base.stageCostRupees(), base.lateNoticeRate(), base.lateNoticeSensitivity(),
                base.lateDiscoveryThresholdSeconds(), base.baselineDiscovery(), base.cardAckBeforeL1(),
                base.ackAfterTakeover(), base.ivr(), base.stopDelayAfterAckSeconds(), base.deviceOffline(),
                base.openLatencySeconds(), base.ivrCostPerCallPaise());
        // stageU 0.8 = OVEN; ready 5-10 s after cancel, before the L1 deadline at 30 s.
        NoCookArm.Result r = new NoCookArm(a, Timings.defaults()).run(TestSupport.silentDraws(0.8, 0.5));
        assertThat(r.finalState()).isEqualTo(CaseState.SUPERSEDED);
        assertThat(r.statePath()).isEqualTo("AWAITING_ACK>SUPERSEDED");
        assertThat(r.ivrCalled()).isFalse();
    }

    @Test
    void ackOnCardClosesAcknowledged() throws Exception {
        Assumptions a = TestSupport.assumptions();
        ScenarioDraws d = new ScenarioDraws(2, 0.1, 0.1, 0.5, 0.99, 0.99, 0.99, 0.99, 0.5, 0.5,
                0.1, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.99, 0.5);
        NoCookArm.Result r = new NoCookArm(a, Timings.defaults()).run(d);
        assertThat(r.statePath()).isEqualTo("AWAITING_ACK>ACKNOWLEDGED");
        assertThat(r.ackLabel()).isEqualTo("CARD_ACK");
    }

    @Test
    void restaurantInitiatedAndReadyAreNotActionable() throws Exception {
        Assumptions a = TestSupport.assumptions();
        ScenarioDraws restaurant = new ScenarioDraws(3, 0.1, 0.99, 0.5, 0.5, 0.5, 0.5, 0.99, 0.5, 0.5,
                0.1, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.99, 0.5);
        ScenarioDraws ready = new ScenarioDraws(4, 0.95, 0.1, 0.5, 0.5, 0.5, 0.5, 0.99, 0.5, 0.5,
                0.1, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.99, 0.5);
        NoCookArm arm = new NoCookArm(a, Timings.defaults());
        assertThat(arm.run(restaurant).statePath()).isEqualTo("NOT_ACTIONABLE");
        assertThat(arm.run(ready).statePath()).isEqualTo("NOT_ACTIONABLE");
    }
}

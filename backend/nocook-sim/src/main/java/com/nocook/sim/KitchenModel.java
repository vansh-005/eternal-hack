package com.nocook.sim;

import com.nocook.domain.model.CancelInitiator;
import com.nocook.domain.model.OrderSnapshot;
import com.nocook.domain.model.OrderStatus;

import java.time.Instant;
import java.util.Map;

/** Kitchen timeline of one order, in seconds after the cancel. Stage changes are monotone. */
public final class KitchenModel {
    public static final Instant CANCEL_AT = Instant.parse("2026-01-01T12:00:00Z");

    private final KitchenStage stageAtCancel;
    private final double prepAtSeconds;
    private final double ovenAtSeconds;
    private final double readyAtSeconds;
    private final Map<KitchenStage, Integer> stageCostRupees;

    private KitchenModel(KitchenStage stageAtCancel, double prepAt, double ovenAt, double readyAt,
                         Map<KitchenStage, Integer> stageCostRupees) {
        this.stageAtCancel = stageAtCancel;
        this.prepAtSeconds = prepAt;
        this.ovenAtSeconds = ovenAt;
        this.readyAtSeconds = readyAt;
        this.stageCostRupees = stageCostRupees;
    }

    public static KitchenModel from(ScenarioDraws d, Assumptions a) {
        KitchenStage stage = Dist.pick(a.stageAtCancel(), d.stageU());
        double toPrep = Dist.uniform(d.notStartedToPrepU(), a.kitchenSecondsToNextStage().notStartedToPrep());
        double toOven = Dist.uniform(d.prepToOvenU(), a.kitchenSecondsToNextStage().prepToOven());
        double toReady = Dist.uniform(d.ovenToReadyU(), a.kitchenSecondsToNextStage().ovenToReady());
        return switch (stage) {
            case NOT_STARTED -> new KitchenModel(stage, toPrep, toPrep + toOven, toPrep + toOven + toReady, a.stageCostRupees());
            case PREP -> new KitchenModel(stage, 0, toOven, toOven + toReady, a.stageCostRupees());
            case OVEN -> new KitchenModel(stage, 0, 0, toReady, a.stageCostRupees());
            case READY -> new KitchenModel(stage, 0, 0, 0, a.stageCostRupees());
        };
    }

    public KitchenStage stageAtCancel() { return stageAtCancel; }

    public KitchenStage stageAt(double secondsAfterCancel) {
        if (secondsAfterCancel >= readyAtSeconds) return KitchenStage.READY;
        if (secondsAfterCancel >= ovenAtSeconds) return KitchenStage.OVEN;
        if (secondsAfterCancel >= prepAtSeconds) return KitchenStage.PREP;
        return KitchenStage.NOT_STARTED;
    }

    public int costRupeesAt(double secondsAfterCancel) {
        return stageCostRupees.get(stageAt(secondsAfterCancel));
    }

    /** What a fresh OMS read returns at that virtual time: still CANCELLED, ready once the kitchen got there. */
    public OrderSnapshot snapshotAt(ScenarioDraws d, CancelInitiator initiator, double secondsAfterCancel, long valuePaise) {
        boolean ready = stageAt(secondsAfterCancel) == KitchenStage.READY;
        Instant readyAt = ready ? CANCEL_AT.plusMillis(Math.round(readyAtSeconds * 1000)) : null;
        return new OrderSnapshot("ord-" + d.id(), "outlet-sim", 1, OrderStatus.CANCELLED,
                stageAt(secondsAfterCancel).name(), CANCEL_AT.minusSeconds(600), readyAt, null,
                CANCEL_AT, initiator, valuePaise, "SIM-" + d.id());
    }
}

package com.nocook.domain.policy;

import com.nocook.domain.effect.Effect;
import com.nocook.domain.effect.Fact;
import com.nocook.domain.model.CaseState;
import com.nocook.domain.model.NotActionableReason;
import org.jspecify.annotations.Nullable;

import java.util.List;

public record OpenDecision(CaseState initialState, @Nullable NotActionableReason reason,
                           List<Fact> facts, List<Effect> effects) {
    public OpenDecision {
        facts = List.copyOf(facts);
        effects = List.copyOf(effects);
    }
}

package com.nocook.domain.policy;

import com.nocook.domain.effect.Effect;
import com.nocook.domain.effect.Fact;
import com.nocook.domain.model.CaseState;
import com.nocook.domain.model.CloseReason;
import com.nocook.domain.model.Outcome;
import org.jspecify.annotations.Nullable;

import java.util.List;

public record TransitionResult(Outcome outcome, CaseState nextState, @Nullable CloseReason closeReason,
                               List<Fact> facts, List<Effect> effects, @Nullable String rejectionCode) {
    public TransitionResult {
        facts = List.copyOf(facts);
        effects = List.copyOf(effects);
    }
}

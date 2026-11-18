package com.nocook.domain.model;

public enum CaseState {
    NOT_ACTIONABLE, AWAITING_ACK, ESCALATED_L1, ESCALATED_IVR, ACKNOWLEDGED, SUPERSEDED, EXPIRED;

    public boolean isOpen() {
        return this == AWAITING_ACK || this == ESCALATED_L1 || this == ESCALATED_IVR;
    }
}

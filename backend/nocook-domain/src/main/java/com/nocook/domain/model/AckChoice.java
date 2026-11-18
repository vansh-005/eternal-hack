package com.nocook.domain.model;

import java.util.Optional;

/** DTMF 1, 2, 3. */
public enum AckChoice {
    STOPPED, NOT_STARTED, ALREADY_COOKED;

    public static Optional<AckChoice> fromDigit(String digit) {
        if (digit == null) return Optional.empty();
        return switch (digit.trim()) {
            case "1" -> Optional.of(STOPPED);
            case "2" -> Optional.of(NOT_STARTED);
            case "3" -> Optional.of(ALREADY_COOKED);
            default -> Optional.empty();
        };
    }

    public String digit() {
        return switch (this) {
            case STOPPED -> "1";
            case NOT_STARTED -> "2";
            case ALREADY_COOKED -> "3";
        };
    }
}

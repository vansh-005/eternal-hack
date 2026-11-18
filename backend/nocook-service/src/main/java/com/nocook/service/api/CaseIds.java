package com.nocook.service.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;

final class CaseIds {

    private CaseIds() {}

    /** A malformed id cannot exist, so it is a 404 CASE_NOT_FOUND, not a 400. */
    static UUID parse(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND", "No such case").with("caseId", raw);
        }
    }
}

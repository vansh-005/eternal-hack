package com.nocook.service.telephony;

import java.util.UUID;

/**
 * Port to the telephony provider. Production = Exotel/Plivo adapter; here {@link MockTelephonyGateway}.
 * {@link #placeCall} is called at most once per case; any exception means the outcome is unknown and the caller never
 * retries (UNKNOWN beats a duplicate call).
 */
public interface TelephonyGateway {

    record CallRequest(UUID caseId, String outletId, String orderRef, String script) {}

    record CallResult(String providerCallId) {}

    CallResult placeCall(CallRequest request);

    /** Best effort; idempotent. */
    void hangup(String providerCallId);
}

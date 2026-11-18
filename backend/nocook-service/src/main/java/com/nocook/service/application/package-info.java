/**
 * Use cases; one {@code @Transactional} boundary each. {@link com.nocook.service.application.CaseOpeningService#open}
 * returns an {@code OpenResult(outcome, caseId, openedAt)}.
 *
 * <p>Next tasks:
 * <ul>
 *   <li>{@link com.nocook.service.application.EffectApplier#apply} (MANDATORY tx) takes a
 *       {@link com.nocook.service.application.CaseContext} describing the case AFTER the transition. B-04/B-06 replace
 *       the UnsupportedOperationException branches in {@code applyOne}.
 *   <li>{@link com.nocook.service.application.OutboxWaker}: implement it on the dispatcher bean; services pick it up
 *       through {@code ObjectProvider} and call {@code wake()} after commit.
 *   <li>Card outbox rows: type MQTT_CARD, action_key {@code card:{caseId}:{KIND}:{caseVersionAfter}}, payload is the
 *       CardMessage JSON (API.md section 4) without {@code sentAt}; publish to {@code nocook/outlets/{outletId}/cards}.
 *   <li>A new case starts at {@code cases.version = 1}; every APPLIED transition bumps it (CaseRepository.applyTransition).
 * </ul>
 */
package com.nocook.service.application;

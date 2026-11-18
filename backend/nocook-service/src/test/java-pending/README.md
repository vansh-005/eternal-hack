# java-pending (not on the build path)

R-tests of Q-02 that need main code from B-03..B-06 (not yet merged): R2 R3 R4 R5 R7 R8 R9 R10 R13.
They extend `AbstractIT` (in `src/test/java/.../it`) and are written against docs/BACKEND.md section 3 names.

To enable after merging B-03..B-06: move `java-pending/com/nocook/service/it/*.java` into
`src/test/java/com/nocook/service/it/`, then fix API names. Assumed names:

- `com.nocook.service.scheduling.DeadlineWorker`: bean with `tick()`. R2 builds a second instance via
  `PendingSupport.newWorker(id)` (constructor signature unknown: adjust that ONE method).
- `com.nocook.service.scheduling.LeaseReaper.tick()` (R4; spec says reap()/tick()).
- `com.nocook.service.scheduling.OutboxDispatcher.tick()` (R9).
- `com.nocook.service.application.AckService.ack(UUID caseId, String outletId, MerchantAck ack)` (R3) with
  `MerchantAck(AckChoice, AckChannel, String actor, String idempotencyKey)` (domain event, package
  `com.nocook.domain.event`, check CaseEvent.java) and result record exposing no fields we read.
- REST per API.md: `POST /api/cases/{id}/ack`, `POST /api/webhooks/telephony/{status|dtmf}` (R8, R10, R13),
  HMAC secret `dev-secret` header `X-Mock-Signature`.
- Meters: `nocook.lease.takeovers{table=deadlines}` (R4), `nocook.acks.rejected` unused.
- Facts: `L1_TRIGGERED`, `LEASE_TAKEOVER`, `ESCALATION_DEFERRED`, `ESCALATION_BLOCKED`, `LATE_ACK_IGNORED`,
  `IVR_RESERVED`, `CASE_EXPIRED`, `CARD_DISPATCHED`, `DTMF_ACKNOWLEDGED`.
- Outbox card kinds in payload: `payload->>'kind'` in OPEN / TAKEOVER / CLOSE.

import type { InvariantCheck, RunBundle } from '../api/opsTypes'

export interface FaultSpec {
  id: string
  fault: string
  how: string
  expected: string
  /** invariants.json checks that decide this fault; empty = judged by notes/Prometheus, not invariants. */
  checks: string[]
  /** TESTED by a Testcontainers test instead of a run. */
  testId?: string
}

/** docs/LOAD_AND_CHAOS.md section 6. */
export const FAULTS: FaultSpec[] = [
  { id: 'F1', fault: 'Scale-out', how: 'REPLICAS=1 make run P=P4, then make run P=P4', expected: 'Report both: case-open p99, queue depth peak, cases/s at knee (replicas share one CPU)', checks: [] },
  { id: 'F2', fault: 'Kill a replica', how: 'kill-replica nocook-2 at t=3 min', expected: 'lost_cases = 0, max_ivr_per_case <= 1, escalations_after_close = 0; queue drains; p99 back under 2 s within 60 s', checks: ['lost_cases', 'max_ivr_per_case', 'escalations_after_close'] },
  { id: 'F3', fault: 'Pause consumers 60 s', how: 'listener STOPPED, 60 s, RUNNING at t=3 min', expected: 'queue depth rises then drains to 0; lost_cases = 0; escalations_after_max_age = 0', checks: ['lost_cases', 'escalations_after_max_age'] },
  { id: 'F4', fault: 'OMS down 30 s', how: 'oms-down true, 30 s, false at t=3 min', expected: '0 L1/IVR facts inside the down window; deferred escalations > 0; lost_cases = 0 after recovery', checks: ['fault_window_escalations', 'lost_cases'] },
  { id: 'F5', fault: 'Slow DB +300 ms for 60 s', how: 'db-latency 300, 60 s, 0 at t=3 min', expected: 'timer-lateness p99 rises then recovers below 3 s within 60 s; all invariants 0', checks: ['duplicate_cases', 'max_ivr_per_case', 'escalations_after_close', 'escalations_after_max_age', 'escalations_on_not_actionable', 'lost_cases', 'outbox_stuck'] },
  { id: 'F6', fault: 'Duplicate storm x20', how: 'duplicate-storm {lastN:50, times:20} at t=2 min', expected: 'duplicates received about 1,000; duplicate_cases = 0', checks: ['duplicate_cases'] },
  { id: 'F7', fault: 'Ready-before-cancel race x500', how: 'POST /oms/api/test/race/ready-then-cancel {count:500} at t=2 min', expected: 'the 500 cases are NOT_ACTIONABLE (READY_OR_PICKED_UP); 0 escalation facts on them', checks: ['escalations_on_not_actionable'] },
  { id: 'F8', fault: 'ACK vs IVR race x1,000', how: 'Testcontainers test R3', expected: 'max_ivr_per_case <= 1; when ACK commits first, no call attempt', checks: [], testId: 'R3' },
  { id: 'F9', fault: 'Forged / replayed webhooks', how: 'Testcontainers test R10', expected: '401 on bad signature; replay has no effect', checks: [], testId: 'R10' },
]

/** docs/TESTING.md R1-R13. */
export const R_TESTS: Array<{ id: string; name: string; what: string }> = [
  { id: 'R1', name: 'DuplicateStormIT', what: '20 concurrent copies of one cancellation: exactly 1 case, 19 duplicate facts' },
  { id: 'R2', name: 'TwoWorkersOneTimerIT', what: 'two deadline workers, 200 due deadlines: each processed once' },
  { id: 'R3', name: 'AckVsIvrRaceIT', what: '1,000 ACK-vs-IVR races: max 1 call per case, none after ACK' },
  { id: 'R4', name: 'LeaseTakeoverIT', what: 'abandoned lease is taken over by another worker, once' },
  { id: 'R5', name: 'OmsUnavailableDefersIT', what: 'OMS unavailable at L1: escalation deferred, not skipped' },
  { id: 'R6', name: 'ReadyBeforeCancelIT', what: 'ready before cancel: NOT_ACTIONABLE, no deadlines, no outbox' },
  { id: 'R7', name: 'SupersededAtEscalationIT', what: 'OMS shows ready/reinstated at L1: SUPERSEDED, escalation blocked' },
  { id: 'R8', name: 'ExpiryIT', what: 'no ACK for 10 min: EXPIRED; late ACK gets 409 CASE_CLOSED' },
  { id: 'R9', name: 'OutboxCrashAfterCommitIT', what: 'crash before dispatch: card sent exactly once' },
  { id: 'R10', name: 'WebhookSecurityIT', what: 'bad/missing signature 401; replayed event has no effect' },
  { id: 'R11', name: 'NocookRoleCannotWriteOmsIT', what: 'nocook_app cannot read or write schema oms' },
  { id: 'R12', name: 'CrashAfterCommitBeforeAckIT', what: 'redelivery after crash: still 1 case, nothing lost' },
  { id: 'R13', name: 'AckIdempotencyIT', what: 'same key replays 200 duplicate; different key gets 409' },
]

export interface FaultObservation {
  runId: string
  checks: InvariantCheck[]
  pass: boolean
}

/** Latest bundle whose manifest.fault equals the fault id, with the checks that decide it. */
export function observeFault(spec: FaultSpec, bundles: RunBundle[]): FaultObservation | null {
  if (spec.checks.length === 0) {
    return null
  }
  const match = [...bundles].reverse().find((bundle) => bundle.manifest?.fault === spec.id && bundle.invariants !== null)
  if (match === undefined || match.invariants === null) {
    return null
  }
  const checks = match.invariants.checks.filter((check) => spec.checks.includes(check.name))
  return { runId: match.summary.runId, checks, pass: checks.length > 0 && checks.every((check) => check.pass) }
}

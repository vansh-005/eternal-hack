// Payload shapes copied from docs/API.md. Do not reshape.

export type IsoTimestamp = string

export interface ErrorBody {
  error: string
  message: string
  caseId?: string
  state?: string
  [extra: string]: unknown
}

// ---- Domain enums -------------------------------------------------------

export type AckChoice = 'STOPPED' | 'NOT_STARTED' | 'ALREADY_COOKED'
export type AckChannel = 'TABLET' | 'IVR' | 'SYSTEM'
export type CaseState = string // state names are owned by docs/DOMAIN.md
export type OrderStatus = string // status names are owned by docs/DATA_MODEL.md
export type OrderStage = 'PREP' | 'OVEN'
// Stage as stored by the OMS (DATA_MODEL.md §1); the stage request only sends PREP | OVEN.
export type OrderSnapshotStage = 'NOT_STARTED' | 'PREP' | 'OVEN' | 'READY'

// ---- 1.1 ACK ------------------------------------------------------------

export interface AckRequest {
  choice: AckChoice
  idempotencyKey: string
  caseVersionSeen: number
}

export interface AckResponse {
  caseId: string
  state: 'ACKNOWLEDGED'
  choice: AckChoice
  channel: 'TABLET'
  ackedAt: IsoTimestamp
  duplicate: boolean
}

// 409 / 404 / 403 / 400 bodies use ErrorBody; ALREADY_ACKNOWLEDGED adds ackChoice + ackedAt,
// CASE_CLOSED adds state + closeReason.
export interface AckConflictBody extends ErrorBody {
  ackChoice?: AckChoice
  ackedAt?: IsoTimestamp
  closeReason?: string
}

// ---- 1.2 Receipts -------------------------------------------------------

export type ReceiptKind = 'DELIVERED' | 'RENDERED' | 'DISMISSED'

export interface ReceiptRequest {
  kind: ReceiptKind
  deviceId: string
  messageId: string
  deviceAt: IsoTimestamp
}

// ---- 1.3 Open cases -----------------------------------------------------

export type CallStatus = string // RINGING, ANSWERED, ... (API.md 1.8)

export interface OpenCaseCall {
  providerCallId: string
  status: CallStatus
}

export interface OpenCase {
  caseId: string
  orderId: string
  orderRef: string
  orderVersion: number
  state: CaseState
  caseVersion: number
  cancelledAt: IsoTimestamp
  openedAt: IsoTimestamp
  l1At: IsoTimestamp
  ivrAt: IsoTimestamp
  expiresAt: IsoTimestamp
  call: OpenCaseCall | null
}

// ---- 1.4 Timeline -------------------------------------------------------

export interface CaseSummary {
  caseId: string
  orderId: string
  orderRef: string
  outletId: string
  state: CaseState
  caseVersion: number
  cancelledAt: IsoTimestamp
  openedAt: IsoTimestamp
  closedAt: IsoTimestamp | null
  closeReason: string | null
  ackChoice: AckChoice | null
  ackChannel: AckChannel | null
  runId: string | null
}

export interface TimelineEvent {
  at: IsoTimestamp
  kind: string
  channel: string
  deviceId: string | null
  metadata: Record<string, string>
  traceId: string | null
}

export interface TimelineDerived {
  caseOpenMs: number | null
  firstDeliveredMs: number | null
  firstRenderedMs: number | null
  ackMs: number | null
}

export interface CaseTimeline {
  case: CaseSummary
  events: TimelineEvent[]
  derived: TimelineDerived
}

// ---- 1.5 Ops read -------------------------------------------------------

export interface ReplicaStatus {
  id: string
  up: boolean
  listener: string
}

export interface OpsSummary {
  openCases: number
  openedLast5m: number
  ackedLast5m: number
  expiredLast5m: number
  queueReady: number
  dlqReady: number
  outboxPending: number
  deadlinesOverdue: number
  replicas: ReplicaStatus[]
}

export type RunVerdict = 'PASS' | 'VOID' | 'FAIL'

// Header of results/*/summary.json; the full file shape is defined by docs/OUTPUT.md.
export interface RunHeader {
  runId: string
  profile: string
  startedAt: IsoTimestamp
  verdict: RunVerdict
}

export type RunDetail = Record<string, unknown>
export type SimulationSummary = Record<string, unknown>

export interface ChaosState {
  dbLatencyMs: number
  omsDown: boolean
  listener: string
}

// ---- 1.6 Chaos ----------------------------------------------------------

export type ChaosAction =
  | 'db-latency'
  | 'oms-down'
  | 'listener'
  | 'kill-replica'
  | 'duplicate-storm'
  | 'reset'

export interface DbLatencyBody {
  ms: number
}
export interface OmsDownBody {
  down: boolean
}
export interface ListenerBody {
  state: 'STOPPED' | 'RUNNING'
}
export interface KillReplicaBody {
  replica: string
}
export interface DuplicateStormBody {
  lastN: number
  times: number
}
export type ResetBody = Record<string, never>

export interface ChaosResponse {
  ok: boolean
  at: IsoTimestamp
}

// ---- 1.7 / 1.8 Telephony ------------------------------------------------

export type TelephonyAction = 'answer' | 'reject' | 'busy' | 'no-answer' | 'fail' | 'dtmf'

export interface DtmfBody {
  digit: string
}

export type TelephonyStatus =
  | 'RINGING'
  | 'ANSWERED'
  | 'COMPLETED'
  | 'NO_ANSWER'
  | 'BUSY'
  | 'FAILED'
  | 'UNKNOWN'

export interface TelephonyStatusWebhook {
  providerCallId: string
  providerEventId: string
  status: TelephonyStatus
  at: IsoTimestamp
}

export interface TelephonyDtmfWebhook {
  providerCallId: string
  providerEventId: string
  digits: string
  at: IsoTimestamp
}

// ---- 2. Mock OMS --------------------------------------------------------

export interface OrderItem {
  name: string
  qty: number
  pricePaise: number
}

export interface CreateOrderRequest {
  outletId: string
  items: OrderItem[]
}

export interface StageRequest {
  stage: OrderStage
}

export interface CancelRequest {
  initiatedBy: string
}

export interface AcceptedOrderLoadRequest {
  outletId: string
  stage: OrderStage
  acceptedSecondsAgo: number
  valuePaise: number
}

export interface FirehoseStartRequest {
  eventsPerSecond: number
}

export interface FirehoseStartResponse {
  running: boolean
  eventsPerSecond: number
}

export interface FirehoseStopResponse {
  running: boolean
  published: number
}

export interface FirehoseStatus {
  running: boolean
  eventsPerSecond: number
  achievedPerSecond: number
  published: number
}

export interface ReplayCancellationsRequest {
  lastN: number
  times: number
}

export interface ReplayCancellationsResponse {
  republished: number
}

export interface ReadyThenCancelRequest {
  count: number
  outletId: string
  gapMillis: number
}

export interface ReadyThenCancelResponse {
  orderIds: string[]
}

export interface OrderSnapshot {
  orderId: string
  outletId: string
  version: number
  status: OrderStatus
  stage: OrderSnapshotStage | null
  valuePaise: number
  orderRef: string
  acceptedAt: IsoTimestamp | null
  readyAt: IsoTimestamp | null
  pickedUpAt: IsoTimestamp | null
  deliveredAt?: IsoTimestamp | null
  cancelledAt: IsoTimestamp | null
  cancelInitiator: string | null
  items: OrderItem[]
}

// ---- 3. AMQP payload ----------------------------------------------------

export interface OrderEvent {
  schemaVersion: 1
  eventId: string
  type: string
  orderId: string
  outletId: string
  orderVersion: number
  occurredAt: IsoTimestamp
  initiatedBy: string | null
  runId: string | null
  synthetic: boolean
}

// ---- 4. MQTT ------------------------------------------------------------

export type CardKind = 'OPEN' | 'TAKEOVER' | 'CLOSE'

export interface CardMessage {
  messageId: string
  kind: CardKind
  caseId: string
  caseVersion: number
  orderId: string
  orderRef: string
  orderVersion: number
  outletId: string
  state: CaseState
  cancelledAt: IsoTimestamp
  openedAt: IsoTimestamp
  l1At: IsoTimestamp
  ivrAt: IsoTimestamp
  expiresAt: IsoTimestamp
  closeReason: string | null
  ackChoice: AckChoice | null
  ackChannel: AckChannel | null
  sentAt: IsoTimestamp
}

export type CallMessageKind = 'INCOMING_CALL' | 'CALL_ENDED'

export interface CallMessage {
  messageId: string
  kind: CallMessageKind
  caseId: string
  providerCallId: string
  orderRef: string
  outletId: string
  script: string
  sentAt: IsoTimestamp
}

export interface ControlMessage {
  command: 'LISTENER_STOP' | 'LISTENER_START'
  at: IsoTimestamp
  by: string
}

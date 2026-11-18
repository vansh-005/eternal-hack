import { useQuery } from '@tanstack/react-query'
import { ApiError, apiDelete, apiGet, apiPost, tabletHeaders } from './client'
import type {
  AckChoice,
  AckRequest,
  AckResponse,
  CaseSummary,
  ChaosResponse,
  CreateOrderRequest,
  DuplicateStormBody,
  FirehoseStartRequest,
  FirehoseStartResponse,
  FirehoseStatus,
  FirehoseStopResponse,
  OpenCase,
  OrderSnapshot,
  OrderStage,
  ReadyThenCancelRequest,
  ReadyThenCancelResponse,
  ReceiptKind,
  ReceiptRequest,
} from './types'

// ---- Tablet -------------------------------------------------------------

export function fetchOpenCases(outletId: string, deviceId: string): Promise<OpenCase[]> {
  return apiGet<OpenCase[]>(`/api/outlets/${encodeURIComponent(outletId)}/open-cases`, {
    headers: tabletHeaders(outletId, deviceId),
  })
}

export function useOutletOrders(outletId: string) {
  return useQuery({
    queryKey: ['outlet-orders', outletId],
    queryFn: () =>
      apiGet<OrderSnapshot[]>(`/oms/api/outlets/${encodeURIComponent(outletId)}/orders?active=true`),
    refetchInterval: 2000,
  })
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function isRetryable(error: unknown): boolean {
  return !(error instanceof ApiError) || error.status >= 500
}

export interface AckArgs {
  outletId: string
  deviceId: string
  caseId: string
  choice: AckChoice
  caseVersionSeen: number
  /** Same key is reused across network retries of one click attempt. */
  idempotencyKey: string
}

/** POST /ack, retrying network/5xx failures with the SAME idempotency key. */
export async function postAck(args: AckArgs, maxAttempts = 3): Promise<AckResponse> {
  const body: AckRequest = {
    choice: args.choice,
    idempotencyKey: args.idempotencyKey,
    caseVersionSeen: args.caseVersionSeen,
  }
  let lastError: unknown = null
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    try {
      return await apiPost<AckResponse>(`/api/cases/${args.caseId}/ack`, body, {
        headers: tabletHeaders(args.outletId, args.deviceId),
      })
    } catch (error) {
      lastError = error
      if (!isRetryable(error) || attempt === maxAttempts) {
        break
      }
      await sleep(300 * attempt)
    }
  }
  throw lastError
}

/** Fire-and-forget receipt, retried up to 3 times. Never throws. */
export function sendReceipt(
  outletId: string,
  deviceId: string,
  caseId: string,
  kind: ReceiptKind,
  messageId: string,
): void {
  const body: ReceiptRequest = { kind, deviceId, messageId, deviceAt: new Date().toISOString() }
  const run = async (): Promise<void> => {
    for (let attempt = 1; attempt <= 3; attempt += 1) {
      try {
        await apiPost<unknown>(`/api/cases/${caseId}/receipts`, body, {
          headers: tabletHeaders(outletId, deviceId),
        })
        return
      } catch {
        await sleep(250 * attempt)
      }
    }
  }
  void run()
}

// ---- Simulator ----------------------------------------------------------

export function createOrder(outletId: string, items: CreateOrderRequest['items']): Promise<OrderSnapshot> {
  return apiPost<OrderSnapshot>('/oms/api/orders', { outletId, items })
}

export type OrderAction =
  | { kind: 'accept' }
  | { kind: 'stage'; stage: OrderStage }
  | { kind: 'ready' }
  | { kind: 'pickup' }
  | { kind: 'deliver' }
  | { kind: 'cancel'; initiatedBy: 'CUSTOMER' | 'RESTAURANT' }
  | { kind: 'reinstate' }

export function applyOrderAction(orderId: string, action: OrderAction): Promise<OrderSnapshot> {
  const base = `/oms/api/orders/${orderId}`
  switch (action.kind) {
    case 'accept':
      return apiPost<OrderSnapshot>(`${base}/accept`)
    case 'stage':
      return apiPost<OrderSnapshot>(`${base}/stage`, { stage: action.stage })
    case 'ready':
      return apiPost<OrderSnapshot>(`${base}/ready`)
    case 'pickup':
      return apiPost<OrderSnapshot>(`${base}/pickup`)
    case 'deliver':
      return apiPost<OrderSnapshot>(`${base}/deliver`)
    case 'cancel':
      return apiPost<OrderSnapshot>(`${base}/cancel`, { initiatedBy: action.initiatedBy })
    case 'reinstate':
      return apiPost<OrderSnapshot>(`${base}/reinstate`)
  }
}

export function useOrderSnapshot(orderId: string | null) {
  return useQuery({
    queryKey: ['order', orderId],
    queryFn: () => apiGet<OrderSnapshot>(`/oms/api/orders/${orderId}`),
    enabled: orderId !== null,
    refetchInterval: 1000,
  })
}

/** Looks the case up in the ops list (API.md 1.5) once the order is cancelled. */
export function useCaseForOrder(orderId: string | null, outletId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['case-for-order', orderId, outletId],
    queryFn: async (): Promise<CaseSummary | null> => {
      const cases = await apiGet<CaseSummary[]>(
        `/api/ops/cases?outlet=${encodeURIComponent(outletId)}&limit=50`,
      )
      return cases.find((item) => item.orderId === orderId) ?? null
    },
    enabled: enabled && orderId !== null,
    refetchInterval: (query) => (query.state.data == null ? 1000 : false),
    retry: false,
  })
}

export function useFirehoseStatus() {
  return useQuery({
    queryKey: ['firehose'],
    queryFn: () => apiGet<FirehoseStatus>('/oms/api/firehose'),
    refetchInterval: 2000,
    retry: false,
  })
}

export function startFirehose(eventsPerSecond: number): Promise<FirehoseStartResponse> {
  const body: FirehoseStartRequest = { eventsPerSecond }
  return apiPost<FirehoseStartResponse>('/oms/api/firehose', body)
}

export function stopFirehose(): Promise<FirehoseStopResponse> {
  return apiDelete<FirehoseStopResponse>('/oms/api/firehose')
}

export function runReadyThenCancel(count: number): Promise<ReadyThenCancelResponse> {
  const body: ReadyThenCancelRequest = { count, outletId: 'outlet-race', gapMillis: 5 }
  return apiPost<ReadyThenCancelResponse>('/oms/api/test/race/ready-then-cancel', body)
}

export function runDuplicateStorm(): Promise<ChaosResponse> {
  const body: DuplicateStormBody = { lastN: 50, times: 20 }
  return apiPost<ChaosResponse>('/api/ops/chaos/duplicate-storm', body)
}

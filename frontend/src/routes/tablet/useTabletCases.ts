import { useCallback, useEffect, useReducer, useRef } from 'react'
import { fetchOpenCases, sendReceipt } from '../../api/queries'
import type { CallMessage, CardMessage, CardKind, CaseState, OpenCase } from '../../api/types'
import { callsTopic, cardsTopic } from '../../mqtt/topics'
import type { UseMqtt } from '../../mqtt/useMqtt'

export const CLOSE_TOAST_MS = 1500

export interface ClosingInfo {
  reason: string
  /** CLOSE message id, used for the DISMISSED receipt. Null for local closes. */
  messageId: string | null
}

export interface TabletCase {
  caseId: string
  orderId: string
  orderRef: string
  caseVersion: number
  state: CaseState
  kind: CardKind
  cancelledAt: string
  openedAt: string
  l1At: string
  ivrAt: string
  expiresAt: string
  messageId: string
  /** Sequence number of the last event applied (see SNAPSHOT race handling). */
  seq: number
  closing: ClosingInfo | null
}

export interface TabletCasesState {
  cases: Record<string, TabletCase>
  /** Case ids that were closed/expired: never shown again. */
  tombstones: Record<string, true>
}

export type TabletCasesAction =
  | { type: 'CARD'; message: CardMessage; seq: number }
  | { type: 'SNAPSHOT'; openCases: OpenCase[]; sinceSeq: number; seq: number }
  | { type: 'LOCAL_CLOSE'; caseId: string; reason: string }
  | { type: 'REMOVE'; caseId: string }

export const initialCasesState: TabletCasesState = { cases: {}, tombstones: {} }

export function isTakeover(entry: TabletCase): boolean {
  return entry.kind === 'TAKEOVER' || entry.state === 'ESCALATED_L1'
}

export function closeReasonText(message: CardMessage): string {
  if (message.ackChannel === 'IVR') {
    return 'Closed: acknowledged via IVR'
  }
  if (message.ackChannel === 'TABLET') {
    return `Closed: acknowledged on tablet${message.ackChoice === null ? '' : ` (${message.ackChoice})`}`
  }
  if (message.closeReason === 'EXPIRED') {
    return 'Closed: expired'
  }
  return `Closed: ${message.closeReason?.toLowerCase().replaceAll('_', ' ') ?? 'case closed'}`
}

function withEntry(state: TabletCasesState, entry: TabletCase): TabletCasesState {
  return { ...state, cases: { ...state.cases, [entry.caseId]: entry } }
}

function withoutEntry(state: TabletCasesState, caseId: string, tombstone: boolean): TabletCasesState {
  const cases = { ...state.cases }
  delete cases[caseId]
  const tombstones = tombstone ? { ...state.tombstones, [caseId]: true as const } : state.tombstones
  return { cases, tombstones }
}

function entryFromMessage(message: CardMessage, seq: number): TabletCase {
  return {
    caseId: message.caseId,
    orderId: message.orderId,
    orderRef: message.orderRef,
    caseVersion: message.caseVersion,
    state: message.state,
    kind: message.kind,
    cancelledAt: message.cancelledAt,
    openedAt: message.openedAt,
    l1At: message.l1At,
    ivrAt: message.ivrAt,
    expiresAt: message.expiresAt,
    messageId: message.messageId,
    seq,
    closing: null,
  }
}

function entryFromOpenCase(openCase: OpenCase, seq: number): TabletCase {
  return {
    caseId: openCase.caseId,
    orderId: openCase.orderId,
    orderRef: openCase.orderRef,
    caseVersion: openCase.caseVersion,
    state: openCase.state,
    kind: openCase.state === 'ESCALATED_L1' ? 'TAKEOVER' : 'OPEN',
    cancelledAt: openCase.cancelledAt,
    openedAt: openCase.openedAt,
    l1At: openCase.l1At,
    ivrAt: openCase.ivrAt,
    expiresAt: openCase.expiresAt,
    messageId: `snapshot:${openCase.caseId}:${openCase.caseVersion}`,
    seq,
    closing: null,
  }
}

function applyCard(state: TabletCasesState, message: CardMessage, seq: number): TabletCasesState {
  const stored = state.cases[message.caseId]
  const tombstoned = state.tombstones[message.caseId] === true

  if (message.kind === 'CLOSE') {
    if (stored !== undefined && stored.closing !== null) {
      // Locally closed already; remember the CLOSE id for DISMISSED.
      if (stored.closing.messageId === null) {
        return withEntry(state, { ...stored, closing: { ...stored.closing, messageId: message.messageId } })
      }
      return state
    }
    if (stored === undefined || tombstoned) {
      return { ...state, tombstones: { ...state.tombstones, [message.caseId]: true } }
    }
    if (message.caseVersion < stored.caseVersion) {
      return state
    }
    const closing: ClosingInfo = { reason: closeReasonText(message), messageId: message.messageId }
    const next = withEntry(state, { ...stored, caseVersion: message.caseVersion, seq, closing })
    return { ...next, tombstones: { ...next.tombstones, [message.caseId]: true } }
  }

  if (tombstoned) {
    return state
  }
  if (stored !== undefined && message.caseVersion < stored.caseVersion) {
    return state
  }
  return withEntry(state, entryFromMessage(message, seq))
}

function applySnapshot(
  state: TabletCasesState,
  openCases: OpenCase[],
  sinceSeq: number,
  seq: number,
): TabletCasesState {
  let next = state
  const presentIds = new Set<string>()
  for (const openCase of openCases) {
    presentIds.add(openCase.caseId)
    if (next.tombstones[openCase.caseId] === true) {
      continue
    }
    const stored = next.cases[openCase.caseId]
    if (stored !== undefined && stored.caseVersion > openCase.caseVersion) {
      continue
    }
    if (stored !== undefined && stored.caseVersion === openCase.caseVersion) {
      continue
    }
    next = withEntry(next, entryFromOpenCase(openCase, seq))
  }
  // The server no longer lists these as open (closed / expired while we were away).
  // Only drop entries last touched before the snapshot request started.
  for (const entry of Object.values(next.cases)) {
    if (!presentIds.has(entry.caseId) && entry.closing === null && entry.seq <= sinceSeq) {
      next = withoutEntry(next, entry.caseId, true)
    }
  }
  return next
}

export function tabletCasesReducer(state: TabletCasesState, action: TabletCasesAction): TabletCasesState {
  switch (action.type) {
    case 'CARD':
      return applyCard(state, action.message, action.seq)
    case 'SNAPSHOT':
      return applySnapshot(state, action.openCases, action.sinceSeq, action.seq)
    case 'LOCAL_CLOSE': {
      const stored = state.cases[action.caseId]
      const tombstoned = { ...state.tombstones, [action.caseId]: true as const }
      if (stored === undefined || stored.closing !== null) {
        return { ...state, tombstones: tombstoned }
      }
      const closing: ClosingInfo = { reason: action.reason, messageId: null }
      return { cases: { ...state.cases, [action.caseId]: { ...stored, closing } }, tombstones: tombstoned }
    }
    case 'REMOVE':
      return withoutEntry(state, action.caseId, true)
  }
}

function isCardMessage(value: unknown): value is CardMessage {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const record = value as Record<string, unknown>
  return (
    typeof record.caseId === 'string' &&
    typeof record.caseVersion === 'number' &&
    typeof record.kind === 'string' &&
    typeof record.messageId === 'string'
  )
}

function isCallMessage(value: unknown): value is CallMessage {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const record = value as Record<string, unknown>
  return typeof record.caseId === 'string' && typeof record.kind === 'string'
}

function parseJson(payload: string): unknown {
  try {
    return JSON.parse(payload) as unknown
  } catch {
    return null
  }
}

export interface UseTabletCasesOptions {
  outletId: string
  deviceId: string
  mqtt: UseMqtt
  /** Called when a case arrives or escalates (beep). */
  onAlert?: () => void
  /** Hook for the call screen (F-02). Receives every CallMessage on the calls topic. */
  onCallMessage?: (message: CallMessage) => void
}

export interface UseTabletCases {
  cases: TabletCase[]
  /** Close a case locally after an ACK 200 / 409. */
  closeLocally: (caseId: string, reason: string) => void
}

function sortCases(cases: Record<string, TabletCase>): TabletCase[] {
  return Object.values(cases).sort((a, b) => a.cancelledAt.localeCompare(b.cancelledAt))
}

export function useTabletCases(options: UseTabletCasesOptions): UseTabletCases {
  const { outletId, deviceId, mqtt, onAlert, onCallMessage } = options
  const { subscribe, connectCount } = mqtt
  const [state, dispatch] = useReducer(tabletCasesReducer, initialCasesState)

  const seqRef = useRef(0)
  const stateRef = useRef(state)
  const pendingRendered = useRef(new Map<string, { caseId: string; messageId: string }>())
  const removalTimers = useRef(new Map<string, ReturnType<typeof setTimeout>>())
  const onAlertRef = useRef(onAlert)
  const onCallRef = useRef(onCallMessage)

  useEffect(() => {
    stateRef.current = state
    onAlertRef.current = onAlert
    onCallRef.current = onCallMessage
  })

  // Live cards.
  useEffect(() => {
    return subscribe(cardsTopic(outletId), (_topic, payload) => {
      const parsed = parseJson(payload)
      if (!isCardMessage(parsed)) {
        return
      }
      const message = parsed
      sendReceipt(outletId, deviceId, message.caseId, 'DELIVERED', message.messageId)
      const known = stateRef.current.cases[message.caseId]
      const fresh = known === undefined && stateRef.current.tombstones[message.caseId] !== true
      seqRef.current += 1
      dispatch({ type: 'CARD', message, seq: seqRef.current })
      if (message.kind !== 'CLOSE') {
        pendingRendered.current.set(message.messageId, { caseId: message.caseId, messageId: message.messageId })
        if ((message.kind === 'OPEN' && fresh) || message.kind === 'TAKEOVER') {
          onAlertRef.current?.()
        }
      }
    })
  }, [subscribe, outletId, deviceId])

  // Call messages: hook only (F-02 renders the call screen).
  useEffect(() => {
    return subscribe(callsTopic(outletId), (_topic, payload) => {
      const parsed = parseJson(payload)
      if (isCallMessage(parsed)) {
        onCallRef.current?.(parsed)
      }
    })
  }, [subscribe, outletId])

  // Snapshot on mount and on every (re)connect.
  useEffect(() => {
    let cancelled = false
    const sinceSeq = seqRef.current
    const load = async (): Promise<void> => {
      for (let attempt = 1; attempt <= 5 && !cancelled; attempt += 1) {
        try {
          const openCases = await fetchOpenCases(outletId, deviceId)
          if (!cancelled) {
            seqRef.current += 1
            dispatch({ type: 'SNAPSHOT', openCases, sinceSeq, seq: seqRef.current })
          }
          return
        } catch {
          await new Promise((resolve) => setTimeout(resolve, 1000 * attempt))
        }
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [outletId, deviceId, connectCount])

  // After commit: RENDERED receipts in rAF; closing toasts schedule removal + DISMISSED.
  useEffect(() => {
    pendingRendered.current.forEach((pending, key) => {
      pendingRendered.current.delete(key)
      const shown = state.cases[pending.caseId]
      if (shown !== undefined && shown.closing === null) {
        requestAnimationFrame(() => sendReceipt(outletId, deviceId, pending.caseId, 'RENDERED', pending.messageId))
      }
    })
    for (const entry of Object.values(state.cases)) {
      if (entry.closing !== null && !removalTimers.current.has(entry.caseId)) {
        const caseId = entry.caseId
        const timer = setTimeout(() => {
          removalTimers.current.delete(caseId)
          const closingMessageId = stateRef.current.cases[caseId]?.closing?.messageId ?? null
          dispatch({ type: 'REMOVE', caseId })
          if (closingMessageId !== null) {
            sendReceipt(outletId, deviceId, caseId, 'DISMISSED', closingMessageId)
          }
        }, CLOSE_TOAST_MS)
        removalTimers.current.set(caseId, timer)
      }
    }
  }, [state, outletId, deviceId])

  useEffect(() => {
    const timers = removalTimers.current
    return () => {
      timers.forEach((timer) => clearTimeout(timer))
      timers.clear()
    }
  }, [])

  const closeLocally = useCallback((caseId: string, reason: string) => {
    dispatch({ type: 'LOCAL_CLOSE', caseId, reason })
  }, [])

  return { cases: sortCases(state.cases), closeLocally }
}

import type { CallMessage, OpenCase } from '../../api/types'

export interface ActiveCall {
  caseId: string
  providerCallId: string
  orderRef: string
  script: string
  /** True once this tablet pressed Answer (or the server reports ANSWERED). */
  answered: boolean
}

export interface CallState {
  call: ActiveCall | null
  /** Provider call ids that ended: never shown again (out-of-order delivery). */
  endedCalls: Record<string, true>
}

export type CallAction =
  | { type: 'MESSAGE'; message: CallMessage }
  | { type: 'SNAPSHOT'; openCases: OpenCase[] }
  | { type: 'ANSWERED'; providerCallId: string }
  | { type: 'DISMISS'; providerCallId: string }

export const initialCallState: CallState = { call: null, endedCalls: {} }

const TERMINAL_STATUSES = new Set(['COMPLETED', 'NO_ANSWER', 'BUSY', 'FAILED', 'UNKNOWN'])

export function isTerminalCallStatus(status: string): boolean {
  return TERMINAL_STATUSES.has(status)
}

export function defaultScript(orderRef: string): string {
  return `Kitchen cancellation alert. Order ending ${orderRef} is cancelled. Press 1 if you stopped preparing; press 2 if preparation had not started; press 3 if it was already cooked.`
}

function ended(state: CallState, providerCallId: string): CallState {
  const endedCalls = { ...state.endedCalls, [providerCallId]: true as const }
  const call = state.call?.providerCallId === providerCallId ? null : state.call
  return { call, endedCalls }
}

export function callReducer(state: CallState, action: CallAction): CallState {
  switch (action.type) {
    case 'MESSAGE': {
      const { message } = action
      if (message.kind === 'CALL_ENDED') {
        return ended(state, message.providerCallId)
      }
      if (state.endedCalls[message.providerCallId] === true) {
        return state
      }
      if (state.call?.providerCallId === message.providerCallId) {
        return state
      }
      return {
        ...state,
        call: {
          caseId: message.caseId,
          providerCallId: message.providerCallId,
          orderRef: message.orderRef,
          script: message.script,
          answered: false,
        },
      }
    }
    case 'SNAPSHOT': {
      const live = action.openCases.find(
        (openCase) =>
          openCase.call !== null &&
          !isTerminalCallStatus(openCase.call.status) &&
          state.endedCalls[openCase.call.providerCallId] !== true,
      )
      if (live === undefined || live.call === null) {
        // Server shows no live call: drop one we hold for a case that is no longer listed.
        const held = state.call
        if (held !== null && !action.openCases.some((openCase) => openCase.caseId === held.caseId)) {
          return ended(state, held.providerCallId)
        }
        return state
      }
      if (state.call?.providerCallId === live.call.providerCallId) {
        return state
      }
      return {
        ...state,
        call: {
          caseId: live.caseId,
          providerCallId: live.call.providerCallId,
          orderRef: live.orderRef,
          script: defaultScript(live.orderRef),
          answered: live.call.status === 'ANSWERED',
        },
      }
    }
    case 'ANSWERED':
      if (state.call?.providerCallId !== action.providerCallId) {
        return state
      }
      return { ...state, call: { ...state.call, answered: true } }
    case 'DISMISS':
      return ended(state, action.providerCallId)
  }
}

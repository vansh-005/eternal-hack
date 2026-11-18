import { describe, expect, it } from 'vitest'
import type { CallMessage, OpenCase } from '../../api/types'
import { callReducer, initialCallState, isTerminalCallStatus } from './callState'

function message(overrides: Partial<CallMessage> = {}): CallMessage {
  return {
    messageId: 'call:1',
    kind: 'INCOMING_CALL',
    caseId: 'c1',
    providerCallId: 'mock-1',
    orderRef: '4417',
    outletId: 'o',
    script: 'script',
    sentAt: '2026-11-21T14:01:15Z',
    ...overrides,
  }
}

function openCase(call: OpenCase['call'], caseId = 'c1'): OpenCase {
  return {
    caseId,
    orderId: 'ord-1',
    orderRef: '4417',
    orderVersion: 4,
    state: 'ESCALATED_L1',
    caseVersion: 2,
    cancelledAt: '',
    openedAt: '',
    l1At: '',
    ivrAt: '',
    expiresAt: '',
    call,
  }
}

describe('callReducer', () => {
  it('shows the call on INCOMING_CALL and clears it on CALL_ENDED', () => {
    const shown = callReducer(initialCallState, {
      type: 'MESSAGE',
      message: message(),
    })
    expect(shown.call?.providerCallId).toBe('mock-1')
    const gone = callReducer(shown, {
      type: 'MESSAGE',
      message: message({ kind: 'CALL_ENDED' }),
    })
    expect(gone.call).toBeNull()
  })

  it('ignores a late INCOMING_CALL after CALL_ENDED', () => {
    const gone = callReducer(initialCallState, {
      type: 'MESSAGE',
      message: message({ kind: 'CALL_ENDED' }),
    })
    expect(callReducer(gone, { type: 'MESSAGE', message: message() }).call).toBeNull()
  })

  it('keeps answered state on duplicate INCOMING_CALL', () => {
    let state = callReducer(initialCallState, {
      type: 'MESSAGE',
      message: message(),
    })
    state = callReducer(state, { type: 'ANSWERED', providerCallId: 'mock-1' })
    state = callReducer(state, { type: 'MESSAGE', message: message() })
    expect(state.call?.answered).toBe(true)
  })

  it('snapshot with RINGING call shows the call screen', () => {
    const state = callReducer(initialCallState, {
      type: 'SNAPSHOT',
      openCases: [openCase({ providerCallId: 'mock-2', status: 'RINGING' })],
    })
    expect(state.call?.providerCallId).toBe('mock-2')
    expect(state.call?.answered).toBe(false)
    expect(state.call?.script).toContain('4417')
  })

  it('snapshot with ANSWERED call restores keypad state', () => {
    const state = callReducer(initialCallState, {
      type: 'SNAPSHOT',
      openCases: [openCase({ providerCallId: 'mock-2', status: 'ANSWERED' })],
    })
    expect(state.call?.answered).toBe(true)
  })

  it('snapshot with terminal call does not show a call', () => {
    const state = callReducer(initialCallState, {
      type: 'SNAPSHOT',
      openCases: [openCase({ providerCallId: 'mock-2', status: 'NO_ANSWER' })],
    })
    expect(state.call).toBeNull()
  })

  it('snapshot dismisses a held call whose case is no longer open', () => {
    const shown = callReducer(initialCallState, {
      type: 'MESSAGE',
      message: message(),
    })
    const state = callReducer(shown, { type: 'SNAPSHOT', openCases: [] })
    expect(state.call).toBeNull()
  })

  it('classifies terminal statuses', () => {
    expect(isTerminalCallStatus('COMPLETED')).toBe(true)
    expect(isTerminalCallStatus('RINGING')).toBe(false)
  })
})

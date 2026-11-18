import { describe, expect, it } from 'vitest'
import type { CardMessage, OpenCase } from '../../api/types'
import {
  closeReasonText,
  initialCasesState,
  isTakeover,
  tabletCasesReducer,
  type TabletCase,
  type TabletCasesState,
} from './useTabletCases'

function at(state: TabletCasesState): TabletCase {
  const entry = state.cases['c1']
  if (entry === undefined) {
    throw new Error('case c1 missing')
  }
  return entry
}

function card(overrides: Partial<CardMessage> = {}): CardMessage {
  return {
    messageId: 'card:c1:OPEN:1',
    kind: 'OPEN',
    caseId: 'c1',
    caseVersion: 1,
    orderId: 'ord-1',
    orderRef: '0001',
    orderVersion: 4,
    outletId: 'outlet-demo',
    state: 'AWAITING_ACK',
    cancelledAt: '2026-11-21T14:00:00.000Z',
    openedAt: '2026-11-21T14:00:00.200Z',
    l1At: '2026-11-21T14:00:30.000Z',
    ivrAt: '2026-11-21T14:01:15.000Z',
    expiresAt: '2026-11-21T14:10:00.000Z',
    closeReason: null,
    ackChoice: null,
    ackChannel: null,
    sentAt: '2026-11-21T14:00:00.300Z',
    ...overrides,
  }
}

function openCase(overrides: Partial<OpenCase> = {}): OpenCase {
  return {
    caseId: 'c1',
    orderId: 'ord-1',
    orderRef: '0001',
    orderVersion: 4,
    state: 'AWAITING_ACK',
    caseVersion: 1,
    cancelledAt: '2026-11-21T14:00:00.000Z',
    openedAt: '2026-11-21T14:00:00.200Z',
    l1At: '2026-11-21T14:00:30.000Z',
    ivrAt: '2026-11-21T14:01:15.000Z',
    expiresAt: '2026-11-21T14:10:00.000Z',
    call: null,
    ...overrides,
  }
}

describe('tabletCasesReducer version ordering', () => {
  it('applies a message with a higher or equal caseVersion', () => {
    let state = tabletCasesReducer(initialCasesState, {
      type: 'CARD',
      message: card(),
      seq: 1,
    })
    state = tabletCasesReducer(state, {
      type: 'CARD',
      message: card({
        kind: 'TAKEOVER',
        state: 'ESCALATED_L1',
        caseVersion: 2,
        messageId: 'card:c1:TAKEOVER:2',
      }),
      seq: 2,
    })
    expect(at(state).caseVersion).toBe(2)
    expect(isTakeover(at(state))).toBe(true)
  })

  it('ignores a message with a lower caseVersion (out-of-order delivery)', () => {
    let state = tabletCasesReducer(initialCasesState, {
      type: 'CARD',
      message: card({
        kind: 'TAKEOVER',
        state: 'ESCALATED_L1',
        caseVersion: 2,
      }),
      seq: 1,
    })
    state = tabletCasesReducer(state, {
      type: 'CARD',
      message: card({ caseVersion: 1 }),
      seq: 2,
    })
    expect(at(state).caseVersion).toBe(2)
    expect(at(state).state).toBe('ESCALATED_L1')
  })
})

describe('tabletCasesReducer CLOSE', () => {
  it('keeps the card as a closing toast, then REMOVE deletes it', () => {
    let state = tabletCasesReducer(initialCasesState, {
      type: 'CARD',
      message: card(),
      seq: 1,
    })
    state = tabletCasesReducer(state, {
      type: 'CARD',
      message: card({
        kind: 'CLOSE',
        caseVersion: 3,
        state: 'ACKNOWLEDGED',
        closeReason: 'ACKNOWLEDGED',
        ackChannel: 'IVR',
        ackChoice: 'STOPPED',
        messageId: 'card:c1:CLOSE:3',
      }),
      seq: 2,
    })
    expect(at(state).closing?.reason).toBe('Closed: acknowledged via IVR')
    expect(at(state).closing?.messageId).toBe('card:c1:CLOSE:3')
    state = tabletCasesReducer(state, { type: 'REMOVE', caseId: 'c1' })
    expect(state.cases['c1']).toBeUndefined()
  })

  it('never resurrects a closed case from a late OPEN or snapshot', () => {
    let state = tabletCasesReducer(initialCasesState, {
      type: 'CARD',
      message: card(),
      seq: 1,
    })
    state = tabletCasesReducer(state, {
      type: 'CARD',
      message: card({
        kind: 'CLOSE',
        caseVersion: 3,
        state: 'EXPIRED',
        closeReason: 'EXPIRED',
      }),
      seq: 2,
    })
    state = tabletCasesReducer(state, { type: 'REMOVE', caseId: 'c1' })
    state = tabletCasesReducer(state, {
      type: 'CARD',
      message: card({ caseVersion: 1 }),
      seq: 3,
    })
    state = tabletCasesReducer(state, {
      type: 'SNAPSHOT',
      openCases: [openCase({ caseVersion: 2 })],
      sinceSeq: 3,
      seq: 4,
    })
    expect(state.cases['c1']).toBeUndefined()
  })

  it('tombstones a CLOSE that arrives before any OPEN', () => {
    let state = tabletCasesReducer(initialCasesState, {
      type: 'CARD',
      message: card({ kind: 'CLOSE', caseVersion: 3 }),
      seq: 1,
    })
    state = tabletCasesReducer(state, {
      type: 'CARD',
      message: card(),
      seq: 2,
    })
    expect(state.cases['c1']).toBeUndefined()
  })

  it('formats close reasons', () => {
    expect(closeReasonText(card({ closeReason: 'EXPIRED' }))).toBe('Closed: expired')
    expect(
      closeReasonText(
        card({
          ackChannel: 'TABLET',
          ackChoice: 'STOPPED',
          closeReason: 'ACKNOWLEDGED',
        }),
      ),
    ).toBe('Closed: acknowledged on tablet (STOPPED)')
  })
})

describe('tabletCasesReducer snapshot merge', () => {
  it('adds cases missed while offline', () => {
    const state = tabletCasesReducer(initialCasesState, {
      type: 'SNAPSHOT',
      openCases: [openCase({ state: 'ESCALATED_L1', caseVersion: 2 })],
      sinceSeq: 0,
      seq: 1,
    })
    expect(at(state).caseVersion).toBe(2)
    expect(isTakeover(at(state))).toBe(true)
  })

  it('does not downgrade a newer card held locally', () => {
    let state = tabletCasesReducer(initialCasesState, {
      type: 'CARD',
      message: card({
        caseVersion: 2,
        kind: 'TAKEOVER',
        state: 'ESCALATED_L1',
      }),
      seq: 1,
    })
    state = tabletCasesReducer(state, {
      type: 'SNAPSHOT',
      openCases: [openCase({ caseVersion: 1 })],
      sinceSeq: 1,
      seq: 2,
    })
    expect(at(state).caseVersion).toBe(2)
  })

  it('drops cards absent from the snapshot (closed/expired while offline) and tombstones them', () => {
    let state = tabletCasesReducer(initialCasesState, {
      type: 'CARD',
      message: card(),
      seq: 1,
    })
    state = tabletCasesReducer(state, {
      type: 'SNAPSHOT',
      openCases: [],
      sinceSeq: 1,
      seq: 2,
    })
    expect(state.cases['c1']).toBeUndefined()
    state = tabletCasesReducer(state, {
      type: 'CARD',
      message: card(),
      seq: 3,
    })
    expect(state.cases['c1']).toBeUndefined()
  })

  it('keeps cards that arrived over MQTT after the snapshot fetch began', () => {
    let state = tabletCasesReducer(initialCasesState, {
      type: 'CARD',
      message: card(),
      seq: 5,
    })
    state = tabletCasesReducer(state, {
      type: 'SNAPSHOT',
      openCases: [],
      sinceSeq: 4,
      seq: 6,
    })
    expect(state.cases['c1']).toBeDefined()
  })
})

import { describe, expect, it } from 'vitest'
import { legalActions, NO_ACTIONS } from './transitions'

describe('legalActions', () => {
  it('disables everything without an order', () => {
    expect(legalActions(null)).toEqual(NO_ACTIONS)
  })

  it('follows the OMS transition table', () => {
    expect(legalActions({ status: 'PLACED', stage: 'NOT_STARTED' })).toMatchObject({
      accept: true,
      ready: false,
      cancel: true,
    })
    expect(legalActions({ status: 'ACCEPTED', stage: 'NOT_STARTED' })).toMatchObject({
      startPrep: true,
      intoOven: false,
      ready: true,
    })
    expect(legalActions({ status: 'PREPARING', stage: 'PREP' }).intoOven).toBe(true)
    expect(legalActions({ status: 'READY', stage: 'READY' })).toMatchObject({ pickup: true, ready: false })
    expect(legalActions({ status: 'PICKED_UP', stage: 'READY' }).deliver).toBe(true)
  })

  it('allows only reinstate once cancelled, nothing once delivered', () => {
    expect(legalActions({ status: 'CANCELLED', stage: 'PREP' })).toEqual({ ...NO_ACTIONS, reinstate: true })
    expect(legalActions({ status: 'DELIVERED', stage: 'READY' })).toEqual(NO_ACTIONS)
  })
})

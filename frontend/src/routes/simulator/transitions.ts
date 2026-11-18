import type { OrderSnapshot } from '../../api/types'

// Legal OMS transitions, DATA_MODEL.md §1.
export interface LegalActions {
  accept: boolean
  startPrep: boolean
  intoOven: boolean
  ready: boolean
  pickup: boolean
  deliver: boolean
  cancel: boolean
  reinstate: boolean
}

export const NO_ACTIONS: LegalActions = {
  accept: false,
  startPrep: false,
  intoOven: false,
  ready: false,
  pickup: false,
  deliver: false,
  cancel: false,
  reinstate: false,
}

export function legalActions(order: Pick<OrderSnapshot, 'status' | 'stage'> | null): LegalActions {
  if (order === null) {
    return NO_ACTIONS
  }
  const { status, stage } = order
  return {
    accept: status === 'PLACED',
    startPrep: status === 'ACCEPTED',
    intoOven: status === 'PREPARING' && stage === 'PREP',
    ready: status === 'ACCEPTED' || status === 'PREPARING',
    pickup: status === 'READY',
    deliver: status === 'PICKED_UP',
    cancel: status !== 'DELIVERED' && status !== 'CANCELLED',
    reinstate: status === 'CANCELLED',
  }
}

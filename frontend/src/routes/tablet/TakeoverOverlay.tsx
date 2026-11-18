import type { ReactNode } from 'react'
import { Countdown } from '../../components/Countdown'
import { AckButtons } from './AckButtons'
import type { TabletCase } from './useTabletCases'

export interface TakeoverOverlayProps {
  entry: TabletCase
  more: number
  outletId: string
  deviceId: string
  onClosed: (caseId: string, reason: string) => void
  /** Order queue strip rendered at the bottom. */
  queueStrip: ReactNode
}

export function TakeoverOverlay({ entry, more, outletId, deviceId, onClosed, queueStrip }: TakeoverOverlayProps) {
  return (
    <div
      role="alertdialog"
      aria-label="Manager takeover"
      data-testid="takeover-overlay"
      className="fixed inset-0 z-50 flex flex-col bg-red-700 text-white"
    >
      <div className="flex flex-1 flex-col items-center justify-center gap-4 overflow-y-auto p-4 text-center">
        <p className="text-xl font-semibold uppercase tracking-widest">Manager alerted</p>
        <h2 className="text-6xl font-black sm:text-8xl">#{entry.orderRef}</h2>
        <p className="text-2xl font-bold">Order CANCELLED. Stop it now.</p>
        <p className="text-lg">
          Call to kitchen in <Countdown to={entry.ivrAt} className="text-3xl font-bold" />
        </p>
        {more > 0 && <p className="text-sm">+{more} more cancelled order{more === 1 ? '' : 's'} waiting</p>}
        <div className="w-full max-w-3xl">
          <AckButtons entry={entry} outletId={outletId} deviceId={deviceId} onClosed={onClosed} large />
        </div>
      </div>
      <div className="border-t border-red-900 bg-red-900/80 p-2">
        <p className="mb-1 text-xs uppercase tracking-wide text-red-100">Active orders</p>
        {queueStrip}
      </div>
    </div>
  )
}

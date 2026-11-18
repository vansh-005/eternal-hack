import { Countdown } from '../../components/Countdown'
import { formatClock, msUntil, useNow } from '../../lib/time'
import { AckButtons } from './AckButtons'
import { isTakeover, type TabletCase } from './useTabletCases'

export interface CancelCardProps {
  entry: TabletCase
  outletId: string
  deviceId: string
  onClosed: (caseId: string, reason: string) => void
}

function CountdownLine({ entry }: { entry: TabletCase }) {
  const now = useNow(500)
  if (!isTakeover(entry) && msUntil(entry.l1At, now) > 0) {
    return (
      <p className="text-lg">
        Manager alert in <Countdown to={entry.l1At} className="text-2xl font-bold" />
      </p>
    )
  }
  if (msUntil(entry.ivrAt, now) > 0) {
    return (
      <p className="text-lg">
        Call to kitchen in <Countdown to={entry.ivrAt} className="text-2xl font-bold" />
      </p>
    )
  }
  return (
    <p className="text-lg">
      Case expires in <Countdown to={entry.expiresAt} className="text-2xl font-bold" />
    </p>
  )
}

export function CancelCard({ entry, outletId, deviceId, onClosed }: CancelCardProps) {
  if (entry.closing !== null) {
    return (
      <article
        data-testid="cancel-card-closing"
        className="rounded-xl border-4 border-green-500 bg-green-950 p-4 text-lg font-semibold text-green-100"
      >
        Order #{entry.orderRef} — {entry.closing.reason}
      </article>
    )
  }
  return (
    <article
      data-testid="cancel-card"
      data-case-id={entry.caseId}
      className="rounded-xl border-4 border-red-600 bg-red-950/60 p-4"
    >
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-3xl font-bold">Order #{entry.orderRef} CANCELLED</h2>
        <span className="text-sm text-red-200">Cancelled {formatClock(entry.cancelledAt)}</span>
      </div>
      <p className="mt-1 text-sm text-red-200">Confirm what the kitchen did:</p>
      <div className="my-3">
        <CountdownLine entry={entry} />
      </div>
      <AckButtons entry={entry} outletId={outletId} deviceId={deviceId} onClosed={onClosed} />
    </article>
  )
}

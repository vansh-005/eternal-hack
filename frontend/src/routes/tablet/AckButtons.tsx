import { useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import { postAck } from '../../api/queries'
import type { AckChoice, AckConflictBody } from '../../api/types'
import { newId } from '../../lib/ids'
import type { TabletCase } from './useTabletCases'

const CHOICES: { choice: AckChoice; label: string }[] = [
  { choice: 'STOPPED', label: 'Stopped' },
  { choice: 'NOT_STARTED', label: "Hadn't started" },
  { choice: 'ALREADY_COOKED', label: 'Already cooked' },
]

export interface AckButtonsProps {
  entry: TabletCase
  outletId: string
  deviceId: string
  onClosed: (caseId: string, reason: string) => void
  large?: boolean
}

function conflictReason(error: ApiError): string {
  const body = error.body as AckConflictBody | null
  if (body?.error === 'ALREADY_ACKNOWLEDGED') {
    return `Already acknowledged${body.ackChoice ? ` (${body.ackChoice})` : ''}`
  }
  if (body?.error === 'CASE_CLOSED') {
    return `Case closed${body.closeReason ? `: ${body.closeReason.toLowerCase().replaceAll('_', ' ')}` : ''}`
  }
  return body?.message ?? error.message
}

export function AckButtons({ entry, outletId, deviceId, onClosed, large = false }: AckButtonsProps) {
  const [pending, setPending] = useState<AckChoice | null>(null)
  const [error, setError] = useState<string | null>(null)
  // A key is minted per click attempt and kept after a network failure so a retry of
  // the same choice reuses it (server de-duplicates).
  const attemptRef = useRef<{ choice: AckChoice; key: string } | null>(null)

  const click = async (choice: AckChoice): Promise<void> => {
    if (pending !== null) {
      return
    }
    const previous = attemptRef.current
    const key = previous !== null && previous.choice === choice ? previous.key : newId()
    attemptRef.current = { choice, key }
    setPending(choice)
    setError(null)
    try {
      await postAck({
        outletId,
        deviceId,
        caseId: entry.caseId,
        choice,
        caseVersionSeen: entry.caseVersion,
        idempotencyKey: key,
      })
      attemptRef.current = null
      onClosed(entry.caseId, `Acknowledged: ${choice.replaceAll('_', ' ').toLowerCase()}`)
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 409) {
        attemptRef.current = null
        onClosed(entry.caseId, conflictReason(caught))
        return
      }
      if (caught instanceof ApiError && caught.status < 500) {
        attemptRef.current = null
      }
      setError(caught instanceof ApiError ? `${caught.code}: ${caught.message}` : 'Network error, tap again to retry')
      setPending(null)
    }
  }

  const size = large ? 'min-h-24 text-2xl' : 'min-h-16 text-lg'
  return (
    <div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        {CHOICES.map(({ choice, label }) => (
          <button
            key={choice}
            type="button"
            disabled={pending !== null}
            onClick={() => void click(choice)}
            className={`rounded-lg font-semibold ${size} ${
              large ? 'bg-white text-red-800' : 'bg-slate-100 text-slate-900'
            } active:scale-95 disabled:opacity-50`}
          >
            {pending === choice ? (
              <span className="inline-flex items-center gap-2">
                <span className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" />
                Sending
              </span>
            ) : (
              label
            )}
          </button>
        ))}
      </div>
      {error !== null && (
        <p role="alert" className="mt-2 rounded bg-black/40 px-3 py-2 text-sm text-amber-200">
          {error}
        </p>
      )}
    </div>
  )
}

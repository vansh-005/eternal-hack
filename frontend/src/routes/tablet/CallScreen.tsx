import { useState } from 'react'
import { ApiError, apiPost } from '../../api/client'
import type { ActiveCall } from './callState'

type CallButtonAction = 'answer' | 'reject' | 'busy' | 'no-answer' | 'fail'

export interface CallScreenProps {
  call: ActiveCall
  onAnswered: (providerCallId: string) => void
}

const ACTIONS: { action: CallButtonAction; label: string; style: string }[] = [
  {
    action: 'answer',
    label: 'Answer',
    style: 'bg-green-600 hover:bg-green-500',
  },
  { action: 'reject', label: 'Reject', style: 'bg-red-600 hover:bg-red-500' },
  { action: 'busy', label: 'Busy', style: 'bg-amber-600 hover:bg-amber-500' },
  {
    action: 'no-answer',
    label: 'No answer',
    style: 'bg-slate-600 hover:bg-slate-500',
  },
  {
    action: 'fail',
    label: 'Network fail',
    style: 'bg-purple-700 hover:bg-purple-600',
  },
]

const DIGITS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0']
const ENABLED_DIGITS = new Set(['1', '2', '3'])

function callPath(providerCallId: string, action: string): string {
  return `/api/mock-telephony/calls/${encodeURIComponent(providerCallId)}/${action}`
}

export function CallScreen({ call, onAnswered }: CallScreenProps) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sent, setSent] = useState<string | null>(null)

  const run = async (action: CallButtonAction | 'dtmf', body?: { digit: string }): Promise<void> => {
    setBusy(true)
    setError(null)
    try {
      await apiPost(callPath(call.providerCallId, action), body)
      if (action === 'answer') {
        onAnswered(call.providerCallId)
      } else if (action === 'dtmf') {
        setSent(body?.digit ?? null)
      } else {
        setSent(action)
      }
    } catch (failure) {
      setError(failure instanceof ApiError ? `${failure.code}: ${failure.message}` : 'Network error, try again')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div
      role="dialog"
      aria-label="Incoming call"
      data-testid="call-screen"
      className="fixed inset-0 z-[60] flex items-center justify-center overflow-y-auto bg-slate-950/90 p-4"
    >
      <div className="w-full max-w-sm rounded-3xl border border-slate-600 bg-slate-900 p-6 text-center shadow-2xl">
        <div className="relative mx-auto mb-4 flex h-20 w-20 items-center justify-center">
          {!call.answered && <span className="absolute inset-0 animate-ping rounded-full bg-green-500/50" />}
          <span className="relative flex h-16 w-16 items-center justify-center rounded-full bg-green-600 text-3xl">
            {'☎'}
          </span>
        </div>
        <p className="text-sm uppercase tracking-widest text-slate-400">
          {call.answered ? 'Call connected' : 'Incoming call'}
        </p>
        <h2 className="mb-2 text-2xl font-bold">NoCook IVR #{call.orderRef}</h2>
        <p data-testid="call-script" className="mb-4 rounded bg-slate-800 p-3 text-left text-sm italic text-slate-200">
          {call.script}
        </p>

        {!call.answered && (
          <div className="grid grid-cols-2 gap-2">
            {ACTIONS.map((entry) => (
              <button
                key={entry.action}
                type="button"
                disabled={busy || sent !== null}
                onClick={() => void run(entry.action)}
                className={`rounded-xl px-3 py-3 text-lg font-semibold text-white disabled:opacity-50 ${entry.style} ${
                  entry.action === 'answer' ? 'col-span-2' : ''
                }`}
              >
                {entry.label}
              </button>
            ))}
          </div>
        )}

        {call.answered && (
          <div className="grid grid-cols-3 gap-2" data-testid="keypad">
            {DIGITS.map((digit) => (
              <button
                key={digit}
                type="button"
                disabled={busy || sent !== null || !ENABLED_DIGITS.has(digit)}
                onClick={() => void run('dtmf', { digit })}
                className={`rounded-xl bg-slate-700 py-4 text-2xl font-bold text-white hover:bg-slate-600 disabled:opacity-40 ${
                  digit === '0' ? 'col-start-2' : ''
                }`}
              >
                {digit}
              </button>
            ))}
          </div>
        )}

        {sent !== null && <p className="mt-3 text-sm text-slate-300">Sent: {sent}. Waiting for the server...</p>}
        {error !== null && (
          <p role="alert" className="mt-3 rounded bg-red-900 p-2 text-sm text-red-100">
            {error}
          </p>
        )}
      </div>
    </div>
  )
}

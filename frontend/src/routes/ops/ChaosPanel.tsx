import { useState } from 'react'
import { ApiError } from '../../api/client'
import { useChaosMutation, useChaosState } from '../../api/opsQueries'
import type { ChaosCall } from '../../api/opsQueries'
import type { ChaosResponse } from '../../api/types'

const BTN = 'rounded px-3 py-2 text-sm font-medium text-white disabled:opacity-40'

function toastText(call: ChaosCall, response: ChaosResponse): string {
  const time = new Date(Date.parse(response.at)).toLocaleTimeString('en-GB', { hour12: false })
  return `${call.action} ${JSON.stringify(call.body)} fired at ${time}`
}

export function ChaosPanel() {
  const [toast, setToast] = useState<string | null>(null)
  const state = useChaosState()
  const mutation = useChaosMutation((call, response) => setToast(toastText(call, response)))
  const current = state.data
  const dbOn = (current?.dbLatencyMs ?? 0) > 0
  const omsDown = current?.omsDown ?? false
  const paused = current?.listener === 'STOPPED'

  const fire = (call: ChaosCall): void => mutation.mutate(call)
  const toggleClass = (active: boolean): string => `${BTN} ${active ? 'bg-red-600' : 'bg-slate-600'}`

  return (
    <section aria-label="Chaos" className="space-y-3 rounded border border-slate-700 p-3">
      <h2 className="text-lg font-semibold">Chaos</h2>
      {state.isError && (
        <p className="text-sm text-yellow-300" data-testid="chaos-unavailable">
          Chaos state unavailable (GET /api/ops/chaos/state failed). Buttons may not work until the ops API is up.
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          className={toggleClass(dbOn)}
          aria-pressed={dbOn}
          disabled={mutation.isPending}
          onClick={() => fire({ action: 'db-latency', body: { ms: dbOn ? 0 : 300 } })}
        >
          DB +300 ms {dbOn ? `(ON ${current?.dbLatencyMs} ms)` : '(off)'}
        </button>
        <button
          type="button"
          className={toggleClass(omsDown)}
          aria-pressed={omsDown}
          disabled={mutation.isPending}
          onClick={() => fire({ action: 'oms-down', body: { down: !omsDown } })}
        >
          OMS down {omsDown ? '(ON)' : '(off)'}
        </button>
        <button
          type="button"
          className={toggleClass(paused)}
          aria-pressed={paused}
          disabled={mutation.isPending}
          onClick={() => fire({ action: 'listener', body: { state: paused ? 'RUNNING' : 'STOPPED' } })}
        >
          Pause consumers {paused ? '(PAUSED)' : '(running)'}
        </button>
        <button
          type="button"
          className={`${BTN} bg-amber-600`}
          disabled={mutation.isPending}
          onClick={() => fire({ action: 'kill-replica', body: { replica: 'nocook-2' } })}
        >
          Kill nocook-2
        </button>
        <button
          type="button"
          className={`${BTN} bg-amber-600`}
          disabled={mutation.isPending}
          onClick={() => fire({ action: 'duplicate-storm', body: { lastN: 50, times: 20 } })}
        >
          Duplicate storm ×20
        </button>
        <button
          type="button"
          className={`${BTN} bg-sky-700`}
          disabled={mutation.isPending}
          onClick={() => fire({ action: 'reset', body: {} })}
        >
          Reset all
        </button>
      </div>
      {mutation.isError && (
        <p role="alert" className="text-sm text-red-300">
          {mutation.error instanceof ApiError ? `${mutation.error.code}: ${mutation.error.message}` : 'network error'}
        </p>
      )}
      {toast !== null && (
        <p role="status" data-testid="chaos-toast" className="rounded bg-slate-800 p-2 text-sm text-green-300">
          {toast} (Grafana annotation lands at the same instant)
        </p>
      )}
    </section>
  )
}

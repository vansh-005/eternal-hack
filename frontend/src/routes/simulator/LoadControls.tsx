import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import {
  runDuplicateStorm,
  runReadyThenCancel,
  startFirehose,
  stopFirehose,
  useFirehoseStatus,
} from '../../api/queries'

function describeError(error: unknown): string {
  return error instanceof ApiError ? `${error.code}: ${error.message}` : 'network error'
}

function RaceDemos() {
  const [busy, setBusy] = useState<string | null>(null)
  const [result, setResult] = useState<string | null>(null)

  const run = async (name: string, work: () => Promise<string>): Promise<void> => {
    setBusy(name)
    setResult(null)
    const startedAt = performance.now()
    try {
      const text = await work()
      setResult(`${name}: ${text} (${Math.round(performance.now() - startedAt)} ms)`)
    } catch (error) {
      setResult(`${name} failed: ${describeError(error)}`)
    } finally {
      setBusy(null)
    }
  }

  return (
    <section aria-label="Race demos" className="space-y-2 rounded border border-slate-700 p-3">
      <h2 className="text-lg font-semibold">Race demos</h2>
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          disabled={busy !== null}
          className="rounded bg-amber-600 px-3 py-2 text-white disabled:opacity-40"
          onClick={() =>
            void run('Ready-then-cancel ×500', async () => {
              const response = await runReadyThenCancel(500)
              return `${response.orderIds.length} orders raced`
            })
          }
        >
          Ready-then-cancel ×500
        </button>
        <button
          type="button"
          disabled={busy !== null}
          className="rounded bg-amber-600 px-3 py-2 text-white disabled:opacity-40"
          onClick={() =>
            void run('Duplicate storm ×20', async () => {
              const response = await runDuplicateStorm()
              return response.ok ? `fired at ${response.at}` : 'not ok'
            })
          }
        >
          Duplicate storm ×20
        </button>
      </div>
      {busy !== null && <p className="text-sm text-slate-400">Running {busy}…</p>}
      {result !== null && (
        <p role="status" className="text-sm text-slate-200">
          {result}
        </p>
      )}
    </section>
  )
}

function BackgroundLoad() {
  const queryClient = useQueryClient()
  const status = useFirehoseStatus()
  const [rate, setRate] = useState(5000)
  const [error, setError] = useState<string | null>(null)

  const change = async (work: () => Promise<unknown>): Promise<void> => {
    setError(null)
    try {
      await work()
      await queryClient.invalidateQueries({ queryKey: ['firehose'] })
    } catch (caught) {
      setError(describeError(caught))
    }
  }

  const data = status.data
  return (
    <section aria-label="Background load" className="space-y-2 rounded border border-slate-700 p-3">
      <h2 className="text-lg font-semibold">Background load</h2>
      {status.isError && <p className="text-sm text-amber-300">Firehose status unavailable.</p>}
      {data !== undefined && (
        <p data-testid="firehose-status" className="text-sm">
          {data.running ? 'RUNNING' : 'stopped'} · target {data.eventsPerSecond.toLocaleString('en-US')}/s ·
          achieved {Math.round(data.achievedPerSecond).toLocaleString('en-US')}/s · published{' '}
          {data.published.toLocaleString('en-US')}
        </p>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <label className="text-sm" htmlFor="firehose-rate">
          events/s
        </label>
        <input
          id="firehose-rate"
          type="number"
          min={1}
          value={rate}
          onChange={(event) => setRate(Number(event.target.value))}
          className="w-28 rounded bg-slate-800 px-2 py-1"
        />
        <button
          type="button"
          className="rounded bg-sky-600 px-3 py-1 text-white"
          onClick={() => void change(() => startFirehose(rate))}
        >
          Start
        </button>
        <button
          type="button"
          className="rounded bg-slate-600 px-3 py-1 text-white"
          onClick={() => void change(() => stopFirehose())}
        >
          Stop
        </button>
      </div>
      {error !== null && <p className="text-sm text-amber-300">{error}</p>}
      <p className="text-xs text-slate-400">
        Hint: start full profiles with <code>make run P=P3</code>.
      </p>
    </section>
  )
}

export function LoadControls() {
  return (
    <div className="space-y-4">
      <RaceDemos />
      <BackgroundLoad />
    </div>
  )
}

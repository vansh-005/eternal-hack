export type ProofKind = 'MEASURED' | 'TESTED' | 'SIMULATED'

export const P99_MIN_N = 1000

const KIND_CLASSES: Record<ProofKind, string> = {
  MEASURED: 'bg-blue-600/20 text-blue-300 border-blue-500',
  TESTED: 'bg-green-600/20 text-green-300 border-green-500',
  SIMULATED: 'bg-purple-600/20 text-purple-300 border-purple-500',
}

export interface ProofBadgeProps {
  kind: ProofKind
  n: number
  runId: string
}

export function ProofBadge({ kind, n, runId }: ProofBadgeProps) {
  return (
    <span
      className={`inline-block rounded border px-2 py-0.5 font-mono text-xs ${KIND_CLASSES[kind]}`}
      data-testid="proof-badge"
    >
      {kind} · N={n.toLocaleString('en-US')} · {runId}
    </span>
  )
}

export interface P99ValueProps {
  valueMs: number
  n: number
}

/** Renders a p99 in ms, or an em dash with a tooltip when N is below 1,000. */
export function P99Value({ valueMs, n }: P99ValueProps) {
  if (n < P99_MIN_N) {
    return (
      <span title="p99 hidden below N = 1,000" data-testid="p99-hidden">
        —
      </span>
    )
  }
  return <span data-testid="p99-value">{Math.round(valueMs)} ms</span>
}

import { formatMmSs, msUntil, useNow } from '../lib/time'

export interface CountdownProps {
  /** Server timestamp (ISO) the countdown runs to. */
  to: string
  /** Override the clock (tests). */
  nowMs?: number
  className?: string
}

/** mm:ss derived from the server timestamp `to`, red under 10 s. */
export function Countdown({ to, nowMs, className = '' }: CountdownProps) {
  const tick = useNow(250)
  const remaining = msUntil(to, nowMs ?? tick)
  const urgent = remaining < 10_000
  return (
    <span
      data-testid="countdown"
      className={`font-mono tabular-nums ${urgent ? 'text-red-400' : ''} ${className}`}
    >
      {formatMmSs(remaining)}
    </span>
  )
}

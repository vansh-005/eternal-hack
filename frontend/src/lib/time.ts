import { useEffect, useState } from 'react'

export function parseIso(timestamp: string): number {
  return Date.parse(timestamp)
}

/** Milliseconds from `nowMs` until the server timestamp `to` (negative once passed). */
export function msUntil(to: string, nowMs: number): number {
  return parseIso(to) - nowMs
}

/** mm:ss, clamped at 00:00. */
export function formatMmSs(ms: number): string {
  const totalSeconds = Math.max(0, Math.ceil(ms / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

export function formatClock(timestamp: string | number): string {
  const date = typeof timestamp === 'number' ? new Date(timestamp) : new Date(parseIso(timestamp))
  return date.toLocaleTimeString('en-GB', { hour12: false })
}

/** "3m 12s" style elapsed time since a server timestamp. */
export function formatElapsed(since: string, nowMs: number): string {
  const totalSeconds = Math.max(0, Math.floor((nowMs - parseIso(since)) / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return minutes > 0 ? `${minutes}m ${seconds}s` : `${seconds}s`
}

/** Re-renders on an interval and returns Date.now(). */
export function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const handle = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(handle)
  }, [intervalMs])
  return now
}

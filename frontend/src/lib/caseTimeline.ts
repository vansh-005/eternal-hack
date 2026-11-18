import type { TimelineEvent } from '../api/types'

export type RowTone = 'grey' | 'amber' | 'orange' | 'green' | 'red' | 'blue' | 'plain'

export interface RowStyle {
  tone: RowTone
  /** Extra label rendered next to the kind, if any. */
  note: string | null
}

/** Highlight rules from docs/FRONTEND.md 5.2. */
export function classifyEvent(kind: string): RowStyle {
  if (kind === 'CARD_RENDERED') {
    return { tone: 'grey', note: 'not an acknowledgement' }
  }
  if (kind === 'L1_TRIGGERED') {
    return { tone: 'amber', note: null }
  }
  if (kind === 'IVR_RESERVED') {
    return { tone: 'orange', note: null }
  }
  if (kind.endsWith('_ACKNOWLEDGED')) {
    return { tone: 'green', note: null }
  }
  if (kind === 'CASE_CLOSED') {
    return { tone: 'blue', note: null }
  }
  return { tone: 'plain', note: null }
}

export const TONE_CLASSES: Record<RowTone, string> = {
  grey: 'border-slate-500 bg-slate-800/40 text-slate-400',
  amber: 'border-amber-400 bg-amber-500/10 text-amber-200',
  orange: 'border-orange-500 bg-orange-500/10 text-orange-200',
  green: 'border-green-500 bg-green-500/10 text-green-200',
  red: 'border-red-500 bg-red-500/10 text-red-200',
  blue: 'border-sky-500 bg-sky-500/10 text-sky-200',
  plain: 'border-slate-700 text-slate-200',
}

/** Milliseconds from the cancel timestamp to the event. */
export function offsetMs(eventAt: string, cancelledAt: string): number {
  return Date.parse(eventAt) - Date.parse(cancelledAt)
}

/** "+1,020 ms" / "+87.0 s" / "-5 ms". */
export function formatOffset(ms: number): string {
  const sign = ms < 0 ? '-' : '+'
  const abs = Math.abs(ms)
  if (abs >= 10_000) {
    return `${sign}${(abs / 1000).toFixed(1)} s`
  }
  return `${sign}${Math.round(abs).toLocaleString('en-US')} ms`
}

/** HH:mm:ss.SSS in local time. */
export function formatClockMs(timestamp: string): string {
  const date = new Date(Date.parse(timestamp))
  const pad = (value: number, width: number): string => String(value).padStart(width, '0')
  return `${pad(date.getHours(), 2)}:${pad(date.getMinutes(), 2)}:${pad(date.getSeconds(), 2)}.${pad(date.getMilliseconds(), 3)}`
}

export function describeSource(event: TimelineEvent): string {
  return event.deviceId === null ? event.channel : `${event.channel}/${event.deviceId}`
}

export function firstTraceId(events: TimelineEvent[]): string | null {
  for (const event of events) {
    if (event.traceId !== null && event.traceId !== '') {
      return event.traceId
    }
  }
  return null
}

const CASE_STATE_CLASSES: Record<string, string> = {
  AWAITING_ACK: 'bg-sky-600/30 text-sky-200',
  ESCALATED_L1: 'bg-amber-500/30 text-amber-200',
  ESCALATED_IVR: 'bg-orange-500/30 text-orange-200',
  ACKNOWLEDGED: 'bg-green-600/30 text-green-200',
  EXPIRED: 'bg-red-600/30 text-red-200',
  SUPERSEDED: 'bg-slate-600/40 text-slate-300',
  NOT_ACTIONABLE: 'bg-slate-600/40 text-slate-300',
}

export function stateChipClass(state: string): string {
  return CASE_STATE_CLASSES[state] ?? 'bg-slate-700 text-slate-200'
}

import type { InvariantsFile, RunBundle, RunManifest, RunSummary } from '../api/opsTypes'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * GET /api/ops/runs/{id} returns "summary + invariants + manifest" (API.md 1.5). The doc does not fix the
 * envelope, so accept both {summary, invariants, manifest} and a flat summary with the other two nested.
 */
export function normalizeRunBundle(raw: unknown): RunBundle | null {
  if (!isRecord(raw)) {
    return null
  }
  const nestedSummary = raw['summary']
  const summary = (isRecord(nestedSummary) ? nestedSummary : raw) as unknown as RunSummary
  if (typeof summary.runId !== 'string' || !isRecord(summary.latency)) {
    return null
  }
  const invariants = raw['invariants']
  const manifest = raw['manifest']
  return {
    summary,
    invariants: isRecord(invariants) ? (invariants as unknown as InvariantsFile) : null,
    manifest: isRecord(manifest) ? (manifest as RunManifest) : null,
  }
}

export function formatSeconds(seconds: number | undefined): string {
  if (seconds === undefined) {
    return '—'
  }
  if (seconds < 1) {
    return `${Math.round(seconds * 1000)} ms`
  }
  return `${seconds.toFixed(2)} s`
}

export function formatPercent(fraction: number | undefined, digits = 2): string {
  return fraction === undefined ? '—' : `${(fraction * 100).toFixed(digits)}%`
}

/** Grafana dashboard link with the run window as from/to epoch ms. */
export function runWindowUrl(
  base: string,
  uid: string,
  startedAt: string | undefined,
  durationSeconds: number | undefined,
): string {
  const url = `${base}/d/${uid}`
  if (startedAt === undefined || durationSeconds === undefined) {
    return url
  }
  const from = Date.parse(startedAt)
  const to = from + durationSeconds * 1000
  return `${url}?orgId=1&from=${from}&to=${to}`
}

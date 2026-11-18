import type { CdfPoint } from '../api/opsTypes'

export interface CdfRow {
  seconds: number
  baseline: number | null
  nocook: number | null
}

/** Merge two [seconds, fraction] series into one row per x, fractions as percent, clipped to maxSeconds. */
export function mergeCdf(baseline: CdfPoint[], nocook: CdfPoint[], maxSeconds = 600): CdfRow[] {
  const rows = new Map<number, CdfRow>()
  const put = (points: CdfPoint[], key: 'baseline' | 'nocook'): void => {
    for (const [seconds, fraction] of points) {
      if (seconds < 0 || seconds > maxSeconds) {
        continue
      }
      const row = rows.get(seconds) ?? { seconds, baseline: null, nocook: null }
      row[key] = Math.round(fraction * 10000) / 100
      rows.set(seconds, row)
    }
  }
  put(baseline, 'baseline')
  put(nocook, 'nocook')
  return [...rows.values()].sort((left, right) => left.seconds - right.seconds)
}

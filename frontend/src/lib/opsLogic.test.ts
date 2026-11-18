import { describe, expect, it } from 'vitest'
import { classifyEvent, formatOffset, offsetMs } from './caseTimeline'
import { mergeCdf } from './cdf'
import { flattenAssumptions } from './assumptions'
import { FAULTS, observeFault } from './faults'
import { normalizeRunBundle } from './runs'
import { FIXTURE_BUNDLE } from '../api/opsQueries'

describe('classifyEvent', () => {
  it('greys CARD_RENDERED with a not-an-acknowledgement note', () => {
    expect(classifyEvent('CARD_RENDERED')).toEqual({ tone: 'grey', note: 'not an acknowledgement' })
  })
  it('maps escalations and acks', () => {
    expect(classifyEvent('L1_TRIGGERED').tone).toBe('amber')
    expect(classifyEvent('IVR_RESERVED').tone).toBe('orange')
    expect(classifyEvent('TABLET_ACKNOWLEDGED').tone).toBe('green')
    expect(classifyEvent('DTMF_ACKNOWLEDGED').tone).toBe('green')
    expect(classifyEvent('CASE_CLOSED').tone).toBe('blue')
    expect(classifyEvent('CASE_OPENED').tone).toBe('plain')
  })
})

describe('offsets', () => {
  it('computes ms from cancel and formats', () => {
    const cancelled = '2026-11-19T10:00:00.000Z'
    expect(offsetMs('2026-11-19T10:00:01.020Z', cancelled)).toBe(1020)
    expect(formatOffset(1020)).toBe('+1,020 ms')
    expect(formatOffset(87000)).toBe('+87.0 s')
  })
})

describe('mergeCdf', () => {
  it('merges series by x as percent and clips to 600 s', () => {
    const rows = mergeCdf(
      [[0, 0], [5, 0.01], [700, 1]],
      [[0, 0], [5, 0.0121], [10, 0.5]],
    )
    expect(rows.map((row) => row.seconds)).toEqual([0, 5, 10])
    expect(rows[1]).toEqual({ seconds: 5, baseline: 1, nocook: 1.21 })
    expect(rows[2]).toEqual({ seconds: 10, baseline: null, nocook: 50 })
  })
})

describe('flattenAssumptions', () => {
  it('flattens nested {value, assumed} leaves', () => {
    const rows = flattenAssumptions({
      lateNoticeRate: { value: 0.1, assumed: true },
      ivr: { dtmfSigma: { value: 0.4, assumed: true } },
      sens: { value: [0.05, 0.1], assumed: true },
    })
    expect(rows).toEqual([
      { path: 'lateNoticeRate', value: '0.1', assumed: true },
      { path: 'ivr.dtmfSigma', value: '0.4', assumed: true },
      { path: 'sens', value: '0.05, 0.1', assumed: true },
    ])
  })
})

describe('runs and faults', () => {
  it('normalizes both envelope shapes', () => {
    expect(normalizeRunBundle(FIXTURE_BUNDLE.summary)?.summary.runId).toBe(FIXTURE_BUNDLE.summary.runId)
    const wrapped = normalizeRunBundle({ summary: FIXTURE_BUNDLE.summary, invariants: FIXTURE_BUNDLE.invariants, manifest: {} })
    expect(wrapped?.invariants?.checks.length).toBeGreaterThan(0)
    expect(normalizeRunBundle({ nope: 1 })).toBeNull()
  })
  it('observes F2 from a tagged run and leaves untagged faults empty', () => {
    const f2 = FAULTS.find((fault) => fault.id === 'F2')
    const f3 = FAULTS.find((fault) => fault.id === 'F3')
    if (f2 === undefined || f3 === undefined) {
      throw new Error('missing fault spec')
    }
    expect(observeFault(f2, [FIXTURE_BUNDLE])?.pass).toBe(true)
    expect(observeFault(f3, [FIXTURE_BUNDLE])).toBeNull()
  })
})

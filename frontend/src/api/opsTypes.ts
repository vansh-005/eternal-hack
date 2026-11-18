// Shapes of results/*/summary.json, invariants.json (docs/OUTPUT.md 3-4) and sim-summary.json
// (docs/SIMULATION.md 5, plus the extra keys the real sim writes). Fields the doc marks
// "shape placeholders" are optional so a partial file never crashes a page.

export interface LatencyBlock {
  n: number
  p50: number
  p95: number
  p99: number
  slo: number
  shareWithinSlo: number
}

export interface RunLatency {
  caseOpen: LatencyBlock
  cardDelivery?: LatencyBlock
  cardRender?: LatencyBlock
  ackApi?: LatencyBlock
  timerLateness?: Record<string, LatencyBlock>
}

export interface RunKnee {
  casesPerSecond: number
  p99AtKnee: number
  windowStart?: string
}

export interface RunSummary {
  runId: string
  profile: string
  kind?: string
  startedAt?: string
  verdict: 'PASS' | 'FAIL' | 'VOID'
  verdictReasons?: string[]
  latency: RunLatency
  throughput?: {
    casesOpenedPerSecond?: { mean: number; max30s: number }
    firehoseAchieved?: number
    k6?: { iterations: number; dropped: number; httpP99: number }
  }
  backlog?: {
    queuePeak: number
    drainSeconds: number
    outboxPendingPeak: number
    deadlinesOverduePeak: number
  }
  knee?: RunKnee | null
  bottleneck?: string | null
  invariantsPass?: boolean
  durationSeconds?: number
  targetRate?: number
  headline?: string
  grafana?: Record<string, string>
}

export interface InvariantCheck {
  name: string
  value: number
  rule: string
  pass: boolean
}

export interface InvariantsFile {
  runId: string
  checkedAt: string
  checks: InvariantCheck[]
  counts?: Record<string, unknown>
}

export interface RunManifest {
  fault?: string | null
  startedAt?: string
  endedAt?: string
  [extra: string]: unknown
}

export interface RunBundle {
  summary: RunSummary
  invariants: InvariantsFile | null
  manifest: RunManifest | null
}

// ---- sim-summary.json ------------------------------------------------------

export type CdfPoint = [number, number]

export interface SimDistribution {
  p50: number
  p95: number
  p99: number
  cdf: CdfPoint[]
}

export interface SimSummary {
  kind: 'SIMULATED'
  runId: string
  seed: number
  n: number
  assumptionsVersion: string
  assumptionsSha256?: string
  eligible: number
  timeToStopSeconds: { baseline: SimDistribution; nocook: SimDistribution }
  lateDiscoveryRate: { thresholdSeconds: number; baseline: number; nocook: number }
  rupeesAvoided: {
    total: number
    per1000Cancellations: number
    sensitivity: Array<{ lateNoticeRate: number; per1000: number }>
    breakEven?: {
      ivrCostPerCallPaise: number
      ivrCostRupeesPer1000Cancellations: number
      lateNoticeRate: number
    }
  }
  funnel: {
    opened: number
    ackedOnCard: number
    ackedAfterL1: number
    ivrCalled: number
    ackedByIvr: number
    ackedTabletAfterIvr?: number
    expired: number
    superseded?: number
  }
  ivrCallsPer1000: number
  ivrOutcomes?: Record<string, number>
  statePaths?: Record<string, number>
  assumptions: AssumptionNode
  commit?: string
  wallMs?: number
}

export interface AssumptionLeaf {
  value: unknown
  assumed: boolean
}
export type AssumptionNode = AssumptionLeaf | { [key: string]: AssumptionNode }

export interface SimManifest {
  startedAt?: string
  commit?: string
  wallMs?: number
  assumptionsFile?: string
  assumptionsSha256?: string
  seed?: number
  n?: number
}

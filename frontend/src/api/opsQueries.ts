import { useMutation, useQuery, useQueryClient, useQueries } from '@tanstack/react-query'
import { apiGet, apiPost } from './client'
import type { CaseSummary, CaseTimeline, ChaosAction, ChaosResponse, ChaosState, OpsSummary, RunHeader } from './types'
import type { RunBundle, SimManifest, SimSummary } from './opsTypes'
import { normalizeRunBundle } from '../lib/runs'
import summaryFixture from '../fixtures/summary.sample.json'
import invariantsFixture from '../fixtures/invariants.sample.json'
import simFixture from '../fixtures/sim-summary.sample.json'
import type { InvariantsFile, RunSummary } from './opsTypes'

export const FIXTURE_RUN_ID = (summaryFixture as { runId: string }).runId

/** Sample bundle used only when the API errors. Fault F2 is tagged so the Faults page can show a sample row. */
export const FIXTURE_BUNDLE: RunBundle = {
  summary: summaryFixture as unknown as RunSummary,
  invariants: invariantsFixture as unknown as InvariantsFile,
  manifest: { fault: 'F2' },
}
export const FIXTURE_SIM = simFixture as unknown as SimSummary

// ---- Live ------------------------------------------------------------------

export function useOpsSummary() {
  return useQuery({
    queryKey: ['ops-summary'],
    queryFn: () => apiGet<OpsSummary>('/api/ops/summary'),
    refetchInterval: 2000,
    retry: false,
  })
}

export function useOpsCases(outlet: string | null) {
  const query = outlet === null ? '' : `&outlet=${encodeURIComponent(outlet)}`
  return useQuery({
    queryKey: ['ops-cases', outlet],
    queryFn: () => apiGet<CaseSummary[]>(`/api/ops/cases?limit=50${query}`),
    refetchInterval: 2000,
    retry: false,
  })
}

export function useCaseTimeline(caseId: string) {
  return useQuery({
    queryKey: ['case-timeline', caseId],
    queryFn: () => apiGet<CaseTimeline>(`/api/cases/${caseId}/timeline`),
    refetchInterval: (query) => (query.state.data?.case.closedAt != null ? false : 1000),
    retry: false,
  })
}

export function useChaosState() {
  return useQuery({
    queryKey: ['chaos-state'],
    queryFn: () => apiGet<ChaosState>('/api/ops/chaos/state'),
    refetchInterval: 2000,
    retry: false,
  })
}

export interface ChaosCall {
  action: ChaosAction
  body: unknown
}

export function useChaosMutation(onFired: (call: ChaosCall, response: ChaosResponse) => void) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (call: ChaosCall) => apiPost<ChaosResponse>(`/api/ops/chaos/${call.action}`, call.body),
    onSuccess: (response, call) => {
      onFired(call, response)
      void client.invalidateQueries({ queryKey: ['chaos-state'] })
    },
  })
}

// ---- Runs / simulation (fixture fallback only on API error) -----------------

export interface WithFixture<T> {
  data: T | undefined
  usingFixture: boolean
  loading: boolean
}

export function useRunBundles(): WithFixture<RunBundle[]> {
  const list = useQuery({
    queryKey: ['ops-runs'],
    queryFn: () => apiGet<RunHeader[]>('/api/ops/runs'),
    retry: false,
  })
  const headers = list.data ?? []
  const details = useQueries({
    queries: headers.map((header) => ({
      queryKey: ['ops-run', header.runId],
      queryFn: async (): Promise<RunBundle | null> =>
        normalizeRunBundle(await apiGet<unknown>(`/api/ops/runs/${encodeURIComponent(header.runId)}`)),
      retry: false,
    })),
  })
  if (list.isError) {
    return { data: [FIXTURE_BUNDLE], usingFixture: true, loading: false }
  }
  if (list.isPending) {
    return { data: undefined, usingFixture: false, loading: true }
  }
  const bundles: RunBundle[] = []
  details.forEach((detail) => {
    if (detail.data) {
      bundles.push(detail.data)
    }
  })
  return { data: bundles, usingFixture: false, loading: details.some((detail) => detail.isPending) }
}

export function useSimulation(): WithFixture<SimSummary> {
  const query = useQuery({
    queryKey: ['ops-sim'],
    queryFn: () => apiGet<SimSummary>('/api/ops/simulations/latest'),
    retry: false,
  })
  if (query.isError) {
    return { data: FIXTURE_SIM, usingFixture: true, loading: false }
  }
  return { data: query.data, usingFixture: false, loading: query.isPending }
}

export type { SimManifest }

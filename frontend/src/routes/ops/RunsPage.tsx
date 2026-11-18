import { Fragment, useState } from 'react'
import type { RunBundle } from '../../api/opsTypes'
import { FIXTURE_RUN_ID, useRunBundles } from '../../api/opsQueries'
import { FixtureBanner } from '../../components/Banner'
import { grafanaBase } from '../../components/GrafanaPanel'
import { P99Value, ProofBadge } from '../../components/ProofBadge'
import { formatPercent, formatSeconds, runWindowUrl } from '../../lib/runs'

const VERDICT_CLASS: Record<string, string> = {
  PASS: 'bg-green-600/30 text-green-200',
  VOID: 'bg-yellow-600/30 text-yellow-200',
  FAIL: 'bg-red-600/30 text-red-200',
}

function P99Cell({ block }: { block: { p99: number; n: number } | undefined }) {
  return block === undefined ? <span>—</span> : <P99Value valueMs={block.p99 * 1000} n={block.n} />
}

function InvariantsDetail({ bundle }: { bundle: RunBundle }) {
  const { summary, invariants } = bundle
  const base = grafanaBase()
  const links = (['liveSlos', 'reliability', 'load'] as const).map((key) => ({
    key,
    url: summary.grafana?.[key] ?? null,
  }))
  const uid: Record<string, string> = { liveSlos: 'nocook-live-slos', reliability: 'nocook-reliability', load: 'nocook-load' }
  return (
    <div className="space-y-3 p-3">
      <div className="flex flex-wrap gap-3 text-sm">
        {links.map((link) => (
          <a
            key={link.key}
            className="text-sky-400 underline"
            href={runWindowUrl(base, uid[link.key] ?? link.key, summary.startedAt, summary.durationSeconds)}
            target="_blank"
            rel="noreferrer"
          >
            Grafana: {link.key} (run window)
          </a>
        ))}
      </div>
      {summary.verdictReasons !== undefined && summary.verdictReasons.length > 0 && (
        <p className="text-sm text-yellow-300">Reasons: {summary.verdictReasons.join('; ')}</p>
      )}
      {invariants === null ? (
        <p className="text-sm text-slate-400">No invariants.json for this run.</p>
      ) : (
        <>
          <table className="text-sm" data-testid="invariants-table">
            <thead className="text-left text-slate-400">
              <tr>
                <th className="pr-4">Check</th>
                <th className="pr-4">Value</th>
                <th className="pr-4">Rule</th>
                <th>Result</th>
              </tr>
            </thead>
            <tbody>
              {invariants.checks.map((check) => (
                <tr key={check.name}>
                  <td className="pr-4 font-mono">{check.name}</td>
                  <td className="pr-4">{check.value}</td>
                  <td className="pr-4 font-mono">{check.rule}</td>
                  <td className={check.pass ? 'text-green-400' : 'text-red-400'}>{check.pass ? 'PASS' : 'FAIL'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <details>
            <summary className="cursor-pointer text-sm text-slate-400">Raw invariants.json</summary>
            <pre className="overflow-x-auto rounded bg-slate-900 p-3 text-xs">{JSON.stringify(invariants, null, 2)}</pre>
          </details>
        </>
      )}
    </div>
  )
}

function KneeCard({ bundle }: { bundle: RunBundle }) {
  const { summary } = bundle
  const n = summary.latency.caseOpen.n
  return (
    <section className="rounded border border-slate-700 p-3" data-testid="knee-card">
      <h3 className="font-semibold">Knee (P6) · {summary.runId}</h3>
      {summary.knee ? (
        <p>
          Highest sustained {summary.knee.casesPerSecond} cases/s with case-open p99 ≤ 2 s (p99 at knee{' '}
          {formatSeconds(summary.knee.p99AtKnee)}).
        </p>
      ) : (
        <p className="text-slate-400">No knee recorded for this run.</p>
      )}
      <p className="text-sm text-slate-300">Bottleneck: {summary.bottleneck ?? 'not recorded in run notes'}</p>
      <ProofBadge kind="MEASURED" n={n} runId={summary.runId} />
    </section>
  )
}

export default function RunsPage() {
  const runs = useRunBundles()
  const [open, setOpen] = useState<string | null>(null)
  const bundles = runs.data ?? []
  const kneeRuns = bundles.filter((bundle) => bundle.summary.profile === 'P6')
  return (
    <main className="space-y-4 p-6">
      <h1 className="text-2xl font-semibold">Runs</h1>
      {runs.usingFixture && <FixtureBanner what={`Showing ${FIXTURE_RUN_ID}.`} />}
      {runs.loading && <p className="text-slate-400">Loading runs…</p>}
      {!runs.loading && bundles.length === 0 && <p className="text-slate-400">No runs exported yet.</p>}
      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm" data-testid="runs-table">
          <thead className="text-slate-400">
            <tr>
              <th className="p-1">Run</th>
              <th className="p-1">Profile</th>
              <th className="p-1">Target / achieved</th>
              <th className="p-1">Duration</th>
              <th className="p-1">Open p50/p95/p99</th>
              <th className="p-1">≤ 2 s</th>
              <th className="p-1">Card p99</th>
              <th className="p-1">ACK p99</th>
              <th className="p-1">Lateness p99</th>
              <th className="p-1">Queue peak / drain</th>
              <th className="p-1">k6 dropped</th>
              <th className="p-1">Verdict</th>
            </tr>
          </thead>
          <tbody>
            {bundles.map((bundle) => {
              const { summary } = bundle
              const caseOpen = summary.latency.caseOpen
              const lateness = summary.latency.timerLateness
              const worstLateness = lateness
                ? Object.values(lateness).reduce((worst, block) => (block.p99 > worst.p99 ? block : worst))
                : undefined
              const expanded = open === summary.runId
              return (
                <Fragment key={summary.runId}>
                  <tr
                    className="cursor-pointer border-t border-slate-800 hover:bg-slate-800/50"
                    onClick={() => setOpen(expanded ? null : summary.runId)}
                  >
                    <td className="p-1">
                      <ProofBadge kind="MEASURED" n={caseOpen.n} runId={summary.runId} />
                    </td>
                    <td className="p-1">{summary.profile}</td>
                    <td className="p-1">
                      {summary.targetRate ?? '—'} / {summary.throughput?.casesOpenedPerSecond?.mean ?? '—'} /s
                    </td>
                    <td className="p-1">{summary.durationSeconds === undefined ? '—' : `${summary.durationSeconds} s`}</td>
                    <td className="p-1">
                      {formatSeconds(caseOpen.p50)} / {formatSeconds(caseOpen.p95)} /{' '}
                      <P99Value valueMs={caseOpen.p99 * 1000} n={caseOpen.n} />
                    </td>
                    <td className="p-1">{formatPercent(caseOpen.shareWithinSlo)}</td>
                    <td className="p-1">
                      <P99Cell block={summary.latency.cardDelivery} />
                    </td>
                    <td className="p-1">
                      <P99Cell block={summary.latency.ackApi} />
                    </td>
                    <td className="p-1">
                      <P99Cell block={worstLateness} />
                    </td>
                    <td className="p-1">
                      {summary.backlog?.queuePeak ?? '—'} / {summary.backlog?.drainSeconds ?? '—'} s
                    </td>
                    <td className="p-1">{summary.throughput?.k6?.dropped ?? '—'}</td>
                    <td className="p-1">
                      <span className={`rounded px-2 py-0.5 ${VERDICT_CLASS[summary.verdict] ?? ''}`}>{summary.verdict}</span>
                    </td>
                  </tr>
                  {expanded && (
                    <tr>
                      <td colSpan={12} className="bg-slate-900/60">
                        <InvariantsDetail bundle={bundle} />
                      </td>
                    </tr>
                  )}
                </Fragment>
              )
            })}
          </tbody>
        </table>
      </div>
      {kneeRuns.map((bundle) => (
        <KneeCard key={bundle.summary.runId} bundle={bundle} />
      ))}
    </main>
  )
}

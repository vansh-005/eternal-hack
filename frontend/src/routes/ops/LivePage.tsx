import { useState } from 'react'
import { Link } from 'react-router'
import { useOpsCases, useOpsSummary } from '../../api/opsQueries'
import { GrafanaPanel, grafanaOpenUrl } from '../../components/GrafanaPanel'
import { stateChipClass } from '../../lib/caseTimeline'
import { formatClock, formatElapsed, useNow } from '../../lib/time'
import { ChaosPanel } from './ChaosPanel'

const DEMO_OUTLET = 'outlet-demo'
const PANELS: ReadonlyArray<{ id: number; title: string }> = [
  { id: 1, title: 'Case open p50/p95/p99' },
  { id: 4, title: 'Card delivery' },
  { id: 7, title: 'ACK API' },
  { id: 10, title: 'Timer lateness' },
]

function Kpi({ label, value, warn }: { label: string; value: number | string; warn?: boolean }) {
  return (
    <div className="rounded border border-slate-700 px-3 py-2">
      <div className="text-xs uppercase text-slate-400">{label}</div>
      <div className={`text-2xl font-bold ${warn === true ? 'text-red-400' : ''}`} data-testid={`kpi-${label}`}>
        {value}
      </div>
    </div>
  )
}

function KpiStrip() {
  const summary = useOpsSummary()
  const data = summary.data
  if (data === undefined) {
    return (
      <p className="text-sm text-yellow-300" data-testid="summary-unavailable">
        {summary.isError
          ? 'GET /api/ops/summary unavailable (backend ops endpoints pending). No KPI values shown.'
          : 'Loading KPIs…'}
      </p>
    )
  }
  return (
    <div className="space-y-2">
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-8">
        <Kpi label="open cases" value={data.openCases} />
        <Kpi label="opened 5m" value={data.openedLast5m} />
        <Kpi label="acked 5m" value={data.ackedLast5m} />
        <Kpi label="expired 5m" value={data.expiredLast5m} />
        <Kpi label="queue ready" value={data.queueReady} />
        <Kpi label="DLQ" value={data.dlqReady} warn={data.dlqReady > 0} />
        <Kpi label="outbox pending" value={data.outboxPending} />
        <Kpi label="overdue deadlines" value={data.deadlinesOverdue} warn={data.deadlinesOverdue > 0} />
      </div>
      <div className="flex flex-wrap gap-2">
        {data.replicas.map((replica) => (
          <span key={replica.id} className="rounded bg-slate-800 px-2 py-1 text-sm">
            {replica.id}{' '}
            <span className={replica.up ? 'text-green-400' : 'text-red-400'}>● {replica.up ? 'RUNNING' : 'DOWN'}</span>{' '}
            <span className="text-slate-400">listener {replica.listener}</span>
          </span>
        ))}
      </div>
    </div>
  )
}

function CaseList() {
  const [demoOnly, setDemoOnly] = useState(true)
  const cases = useOpsCases(demoOnly ? DEMO_OUTLET : null)
  const now = useNow(1000)
  return (
    <section aria-label="Cases" className="space-y-2">
      <div className="flex items-center gap-4">
        <h2 className="text-lg font-semibold">Cases</h2>
        <label className="text-sm text-slate-300">
          <input type="checkbox" checked={demoOnly} onChange={(event) => setDemoOnly(event.target.checked)} /> demo
          outlet only
        </label>
      </div>
      {cases.isError && (
        <p className="text-sm text-yellow-300" data-testid="cases-unavailable">
          GET /api/ops/cases unavailable (backend ops endpoints pending).
        </p>
      )}
      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead className="text-slate-400">
            <tr>
              <th className="p-1">Cancelled</th>
              <th className="p-1">Order</th>
              <th className="p-1">Outlet</th>
              <th className="p-1">State</th>
              <th className="p-1">Age</th>
              <th className="p-1">Close reason</th>
            </tr>
          </thead>
          <tbody>
            {(cases.data ?? []).map((item) => (
              <tr key={item.caseId} className="border-t border-slate-800 hover:bg-slate-800/50">
                <td className="p-1">{formatClock(item.cancelledAt)}</td>
                <td className="p-1">
                  <Link className="text-sky-400 underline" to={`/ops/cases/${item.caseId}`}>
                    #{item.orderRef}
                  </Link>
                </td>
                <td className="p-1">{item.outletId}</td>
                <td className="p-1">
                  <span className={`rounded px-2 py-0.5 text-xs ${stateChipClass(item.state)}`}>{item.state}</span>
                </td>
                <td className="p-1">{formatElapsed(item.cancelledAt, now)}</td>
                <td className="p-1">{item.closeReason ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {cases.data?.length === 0 && <p className="p-2 text-slate-400">No cases.</p>}
      </div>
    </section>
  )
}

export default function LivePage() {
  return (
    <main className="space-y-6 p-6">
      <h1 className="text-2xl font-semibold">Ops live</h1>
      <KpiStrip />
      <section aria-label="Grafana panels" className="grid gap-4 md:grid-cols-2">
        {PANELS.map((panel) => (
          <div key={panel.id} className="space-y-1">
            <div className="flex items-center justify-between text-sm">
              <span className="font-medium">{panel.title}</span>
              <a
                className="text-sky-400 underline"
                href={grafanaOpenUrl('nocook-live-slos', panel.id)}
                target="_blank"
                rel="noreferrer"
              >
                Open in Grafana
              </a>
            </div>
            <GrafanaPanel
              dashboardUid="nocook-live-slos"
              panelId={panel.id}
              title={`Grafana: ${panel.title}`}
              height={240}
            />
          </div>
        ))}
      </section>
      <ChaosPanel />
      <CaseList />
    </main>
  )
}

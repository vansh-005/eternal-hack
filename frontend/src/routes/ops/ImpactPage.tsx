import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { SimSummary } from '../../api/opsTypes'
import { useSimulation } from '../../api/opsQueries'
import { FixtureBanner } from '../../components/Banner'
import { ProofBadge } from '../../components/ProofBadge'
import { flattenAssumptions } from '../../lib/assumptions'
import { mergeCdf } from '../../lib/cdf'

const rupees = (value: number): string => `₹${Math.round(value).toLocaleString('en-IN')}`
const pct = (fraction: number): string => `${(fraction * 100).toFixed(2)}%`

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="space-y-2 rounded border border-slate-700 p-4">
      <h2 className="text-lg font-semibold">{title}</h2>
      {children}
    </section>
  )
}

function Cdf({ sim }: { sim: SimSummary }) {
  const rows = mergeCdf(sim.timeToStopSeconds.baseline.cdf, sim.timeToStopSeconds.nocook.cdf, 600)
  return (
    <Card title="Time to kitchen stop (CDF)">
      <div style={{ height: 300 }}>
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={rows}>
            <CartesianGrid stroke="#334155" strokeDasharray="3 3" />
            <XAxis dataKey="seconds" type="number" domain={[0, 600]} unit=" s" stroke="#94a3b8" />
            <YAxis domain={[0, 100]} unit="%" stroke="#94a3b8" />
            <Tooltip contentStyle={{ background: '#0f172a', border: '1px solid #475569' }} />
            <Legend />
            <Line type="monotone" dataKey="baseline" name="Baseline" stroke="#f87171" dot={false} isAnimationActive={false} />
            <Line type="monotone" dataKey="nocook" name="NoCook" stroke="#4ade80" dot={false} isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
      <p className="text-sm text-slate-300">
        p50 {sim.timeToStopSeconds.baseline.p50.toFixed(1)} s → {sim.timeToStopSeconds.nocook.p50.toFixed(1)} s · p95{' '}
        {sim.timeToStopSeconds.baseline.p95.toFixed(1)} s → {sim.timeToStopSeconds.nocook.p95.toFixed(1)} s · p99{' '}
        {sim.timeToStopSeconds.baseline.p99.toFixed(1)} s → {sim.timeToStopSeconds.nocook.p99.toFixed(1)} s (baseline →
        NoCook, simulated)
      </p>
    </Card>
  )
}

function Money({ sim }: { sim: SimSummary }) {
  const avoided = sim.rupeesAvoided
  const sensitivity = avoided.sensitivity.map((point) => ({
    label: `${Math.round(point.lateNoticeRate * 100)}% late notice`,
    per1000: point.per1000,
  }))
  const breakEven = avoided.breakEven
  return (
    <Card title="₹ prep cost avoided">
      <p className="text-3xl font-bold">
        {rupees(avoided.total)} <span className="text-base font-normal text-slate-400">over {sim.n.toLocaleString('en-US')} scenarios</span>
      </p>
      <p className="text-xl">{rupees(avoided.per1000Cancellations)} per 1,000 cancellations</p>
      <div style={{ height: 220 }}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={sensitivity}>
            <CartesianGrid stroke="#334155" strokeDasharray="3 3" />
            <XAxis dataKey="label" stroke="#94a3b8" />
            <YAxis stroke="#94a3b8" tickFormatter={rupees} />
            <Tooltip formatter={(value) => rupees(Number(value))} contentStyle={{ background: '#0f172a', border: '1px solid #475569' }} />
            <Bar dataKey="per1000" name="₹ avoided per 1,000" fill="#a78bfa" isAnimationActive={false} />
          </BarChart>
        </ResponsiveContainer>
      </div>
      {breakEven !== undefined && (
        <p className="rounded bg-slate-800 p-2 text-sm" data-testid="break-even">
          Break-even late-notice rate: {pct(breakEven.lateNoticeRate)}. This is a real result, not a missing value: the
          modelled IVR spend is only {rupees(breakEven.ivrCostRupeesPer1000Cancellations)} per 1,000 cancellations (₹
          {(breakEven.ivrCostPerCallPaise / 100).toFixed(2)} per call, assumed), and the avoided prep cost exceeds it
          at any late-notice rate above zero, so NoCook pays for its IVR calls even if almost no cancellations reach the
          kitchen late.
        </p>
      )}
    </Card>
  )
}

function LateDiscovery({ sim }: { sim: SimSummary }) {
  const late = sim.lateDiscoveryRate
  const rows = [
    { name: 'Baseline', rate: late.baseline * 100 },
    { name: 'NoCook', rate: late.nocook * 100 },
  ]
  return (
    <Card title={`Late discovery (> ${late.thresholdSeconds} s)`}>
      <div style={{ height: 200 }}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={rows}>
            <CartesianGrid stroke="#334155" strokeDasharray="3 3" />
            <XAxis dataKey="name" stroke="#94a3b8" />
            <YAxis unit="%" stroke="#94a3b8" />
            <Tooltip formatter={(value) => `${Number(value).toFixed(2)}%`} contentStyle={{ background: '#0f172a', border: '1px solid #475569' }} />
            <Bar dataKey="rate" name="Late discovery rate" fill="#f59e0b" isAnimationActive={false} />
          </BarChart>
        </ResponsiveContainer>
      </div>
      <p className="text-sm text-slate-300">
        {pct(late.baseline)} baseline vs {pct(late.nocook)} NoCook.
      </p>
    </Card>
  )
}

function Funnel({ sim }: { sim: SimSummary }) {
  const funnel = sim.funnel
  const rows = [
    { name: 'Opened', count: funnel.opened },
    { name: 'Acked on card', count: funnel.ackedOnCard },
    { name: 'Acked after L1', count: funnel.ackedAfterL1 },
    { name: 'IVR called', count: funnel.ivrCalled },
    { name: 'Acked by IVR', count: funnel.ackedByIvr },
    ...(funnel.ackedTabletAfterIvr === undefined ? [] : [{ name: 'Acked on tablet after IVR', count: funnel.ackedTabletAfterIvr }]),
    { name: 'Expired', count: funnel.expired },
  ]
  return (
    <Card title="Funnel">
      <div style={{ height: 300 }}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={rows} layout="vertical" margin={{ left: 100 }}>
            <CartesianGrid stroke="#334155" strokeDasharray="3 3" />
            <XAxis type="number" stroke="#94a3b8" />
            <YAxis type="category" dataKey="name" stroke="#94a3b8" width={150} />
            <Tooltip contentStyle={{ background: '#0f172a', border: '1px solid #475569' }} />
            <Bar dataKey="count" fill="#38bdf8" isAnimationActive={false} />
          </BarChart>
        </ResponsiveContainer>
      </div>
      <p className="text-xs text-slate-400">
        Eligible cases only ({sim.eligible.toLocaleString('en-US')} of {sim.n.toLocaleString('en-US')}); the rest were
        not actionable (e.g. already ready).
      </p>
    </Card>
  )
}

function Ivr({ sim }: { sim: SimSummary }) {
  return (
    <Card title="IVR calls per 1,000 cases">
      <p className="text-3xl font-bold" data-testid="ivr-per-1000">
        {sim.ivrCallsPer1000.toFixed(1)}
      </p>
      {sim.ivrOutcomes !== undefined && (
        <ul className="text-sm text-slate-300">
          {Object.entries(sim.ivrOutcomes).map(([outcome, count]) => (
            <li key={outcome}>
              {outcome}: {count.toLocaleString('en-US')}
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

function Assumptions({ sim }: { sim: SimSummary }) {
  const rows = flattenAssumptions(sim.assumptions)
  return (
    <Card title={`Assumptions (${sim.assumptionsVersion})`}>
      <div className="max-h-96 overflow-auto">
        <table className="w-full text-left text-sm" data-testid="assumptions-table">
          <thead className="text-slate-400">
            <tr>
              <th className="p-1">Parameter</th>
              <th className="p-1">Value</th>
              <th className="p-1">Source</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.path} className="border-t border-slate-800">
                <td className="p-1 font-mono text-xs">{row.path}</td>
                <td className="p-1">{row.value}</td>
                <td className="p-1">
                  {row.assumed && <span className="rounded bg-purple-600/30 px-2 py-0.5 text-xs text-purple-200">assumed</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  )
}

function Manifest({ sim }: { sim: SimSummary }) {
  const entries: Array<[string, string]> = [
    ['runId', sim.runId],
    ['seed', String(sim.seed)],
    ['n', sim.n.toLocaleString('en-US')],
    ['assumptions version', sim.assumptionsVersion],
    ['assumptions sha256', sim.assumptionsSha256 ?? '—'],
    ['commit', sim.commit ?? '—'],
    ['wall time', sim.wallMs === undefined ? '—' : `${sim.wallMs} ms`],
  ]
  return (
    <Card title="Manifest">
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 text-sm">
        {entries.map(([key, value]) => (
          <div key={key} className="contents">
            <dt className="text-slate-400">{key}</dt>
            <dd className="break-all font-mono">{value}</dd>
          </div>
        ))}
      </dl>
    </Card>
  )
}

export default function ImpactPage() {
  const simulation = useSimulation()
  const sim = simulation.data
  return (
    <main className="space-y-4 p-6">
      <h1 className="text-2xl font-semibold">Impact</h1>
      {simulation.usingFixture && <FixtureBanner what="Showing a copy of results/sim-20261119-0321." />}
      {simulation.loading && <p className="text-slate-400">Loading simulation…</p>}
      {sim !== undefined && (
        <>
          <div
            className="rounded border-2 border-purple-500 bg-purple-600/20 p-4 text-lg font-semibold text-purple-100"
            data-testid="sim-banner"
          >
            SIMULATED — modelled behaviour with assumptions {sim.assumptionsVersion}, seed {sim.seed}, N ={' '}
            {sim.n.toLocaleString('en-US')} paired scenarios. Not measured.
            <div className="mt-2">
              <ProofBadge kind="SIMULATED" n={sim.n} runId={sim.runId} />
            </div>
          </div>
          <div className="grid gap-4 lg:grid-cols-2">
            <Cdf sim={sim} />
            <Money sim={sim} />
            <LateDiscovery sim={sim} />
            <Funnel sim={sim} />
            <Ivr sim={sim} />
            <Manifest sim={sim} />
          </div>
          <Assumptions sim={sim} />
        </>
      )}
    </main>
  )
}

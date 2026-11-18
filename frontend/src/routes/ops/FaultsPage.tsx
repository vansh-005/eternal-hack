import { FIXTURE_RUN_ID, useRunBundles } from '../../api/opsQueries'
import { FixtureBanner } from '../../components/Banner'
import { ProofBadge } from '../../components/ProofBadge'
import { FAULTS, R_TESTS, observeFault } from '../../lib/faults'

/**
 * No endpoint exposes results/tests-*.json yet, so every R-test is "unknown" until the coordinator
 * names one. Pass a map here to light them up.
 */
const TEST_STATUS: Record<string, 'PASS' | 'FAIL'> = {}

export default function FaultsPage() {
  const runs = useRunBundles()
  const bundles = runs.data ?? []
  return (
    <main className="space-y-6 p-6">
      <h1 className="text-2xl font-semibold">Faults</h1>
      {runs.usingFixture && <FixtureBanner what={`F2 row is filled from ${FIXTURE_RUN_ID}.`} />}
      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm" data-testid="faults-table">
          <thead className="text-slate-400">
            <tr>
              <th className="p-1">ID</th>
              <th className="p-1">Fault</th>
              <th className="p-1">How injected</th>
              <th className="p-1">Expected</th>
              <th className="p-1">Observed</th>
              <th className="p-1">Result</th>
            </tr>
          </thead>
          <tbody>
            {FAULTS.map((spec) => {
              const observed = observeFault(spec, bundles)
              const testStatus = spec.testId === undefined ? undefined : TEST_STATUS[spec.testId]
              return (
                <tr key={spec.id} className="border-t border-slate-800 align-top">
                  <td className="p-1 font-mono">{spec.id}</td>
                  <td className="p-1">{spec.fault}</td>
                  <td className="p-1">{spec.how}</td>
                  <td className="p-1">{spec.expected}</td>
                  <td className="p-1">
                    {observed !== null ? (
                      <div className="space-y-1">
                        {observed.checks.map((check) => (
                          <div key={check.name} className="font-mono text-xs">
                            {check.name} = {check.value} ({check.rule})
                          </div>
                        ))}
                        <ProofBadge kind="TESTED" n={bundles.find((b) => b.summary.runId === observed.runId)?.summary.latency.caseOpen.n ?? 0} runId={observed.runId} />
                      </div>
                    ) : (
                      <span className="text-slate-500">
                        {spec.testId !== undefined ? `see ${spec.testId} below` : 'no run for this fault yet'}
                      </span>
                    )}
                  </td>
                  <td className="p-1">
                    {observed !== null ? (
                      <span className={observed.pass ? 'text-green-400' : 'text-red-400'}>
                        {observed.pass ? 'PASS' : 'FAIL'}
                      </span>
                    ) : (
                      <span className="text-slate-500">{testStatus ?? 'unknown'}</span>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <section aria-label="Tested" className="space-y-2">
        <h2 className="text-lg font-semibold">
          TESTED — Testcontainers suite R1–R13 <span className="text-sm font-normal text-slate-400">(TESTED, no N: pass/fail tests)</span>
        </h2>
        <p className="text-sm text-yellow-300">
          Status is &quot;unknown&quot; because no API endpoint exposes results/tests-*.json yet.
        </p>
        <ul className="grid gap-1 text-sm md:grid-cols-2" data-testid="r-tests">
          {R_TESTS.map((test) => (
            <li key={test.id} className="flex gap-2 rounded border border-slate-800 p-2">
              <span className="font-mono font-semibold">{test.id}</span>
              <span className="flex-1">
                <span className="font-mono text-xs text-slate-400">{test.name}</span>
                <br />
                {test.what}
              </span>
              <span className="text-slate-400">{TEST_STATUS[test.id] ?? 'unknown'}</span>
            </li>
          ))}
        </ul>
      </section>
    </main>
  )
}

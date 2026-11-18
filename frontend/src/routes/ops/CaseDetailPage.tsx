import { Link, useParams } from 'react-router'
import { useCaseTimeline } from '../../api/opsQueries'
import { tempoTraceUrl } from '../../components/GrafanaPanel'
import {
  TONE_CLASSES,
  classifyEvent,
  describeSource,
  firstTraceId,
  formatClockMs,
  formatOffset,
  offsetMs,
  stateChipClass,
} from '../../lib/caseTimeline'

function ms(value: number | null): string {
  return value === null ? '—' : `${value.toLocaleString('en-US')} ms`
}

export default function CaseDetailPage() {
  const { caseId = '' } = useParams()
  const timeline = useCaseTimeline(caseId)
  const data = timeline.data
  if (data === undefined) {
    return (
      <main className="p-6">
        <Link to="/ops" className="text-sky-400 underline">
          ← Ops
        </Link>
        <p className="mt-4 text-slate-400" data-testid="timeline-status">
          {timeline.isError ? `Timeline unavailable for case ${caseId}.` : 'Loading timeline…'}
        </p>
      </main>
    )
  }
  const trace = firstTraceId(data.events)
  return (
    <main className="space-y-4 p-6">
      <Link to="/ops" className="text-sky-400 underline">
        ← Ops
      </Link>
      <header className="space-y-2">
        <h1 className="text-2xl font-semibold">
          Case #{data.case.orderRef}{' '}
          <span className={`rounded px-2 py-0.5 text-base ${stateChipClass(data.case.state)}`}>{data.case.state}</span>
        </h1>
        <p className="font-mono text-xs text-slate-400">
          {data.case.caseId} · order {data.case.orderId} · {data.case.outletId}
        </p>
        <dl className="grid grid-cols-2 gap-2 text-sm sm:grid-cols-4" data-testid="derived">
          <div>
            <dt className="text-slate-400">case open</dt>
            <dd>{ms(data.derived.caseOpenMs)}</dd>
          </div>
          <div>
            <dt className="text-slate-400">first delivered</dt>
            <dd>{ms(data.derived.firstDeliveredMs)}</dd>
          </div>
          <div>
            <dt className="text-slate-400">first rendered</dt>
            <dd>{ms(data.derived.firstRenderedMs)}</dd>
          </div>
          <div>
            <dt className="text-slate-400">ack</dt>
            <dd>{ms(data.derived.ackMs)}</dd>
          </div>
        </dl>
        {trace !== null && (
          <a className="text-sky-400 underline" href={tempoTraceUrl(trace)} target="_blank" rel="noreferrer">
            Trace in Tempo
          </a>
        )}
      </header>
      <ol className="space-y-1" data-testid="timeline">
        {data.events.map((event, index) => {
          const style = classifyEvent(event.kind)
          const metadata = Object.entries(event.metadata)
            .map(([key, value]) => `${key}=${value}`)
            .join(' ')
          return (
            <li
              key={`${event.at}-${event.kind}-${index}`}
              data-tone={style.tone}
              className={`flex flex-wrap items-baseline gap-x-4 border-l-4 px-3 py-1 font-mono text-sm ${TONE_CLASSES[style.tone]}`}
            >
              <span>{formatClockMs(event.at)}</span>
              <span className="font-semibold">{event.kind}</span>
              <span className="text-slate-400">{describeSource(event)}</span>
              {style.note !== null && <span className="italic">({style.note})</span>}
              <span className="text-xs text-slate-400">{metadata}</span>
              <span className="ml-auto">{formatOffset(offsetMs(event.at, data.case.cancelledAt))}</span>
            </li>
          )
        })}
      </ol>
    </main>
  )
}

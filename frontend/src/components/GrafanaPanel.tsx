export function grafanaBase(): string {
  return import.meta.env.VITE_GRAFANA_URL ?? `http://${location.hostname}:3000`
}

export interface GrafanaPanelProps {
  dashboardUid: string
  panelId: number
  height?: number
  from?: string
  to?: string
  slug?: string
  title?: string
}

export function grafanaPanelUrl(props: GrafanaPanelProps, base: string = grafanaBase()): string {
  const { dashboardUid, panelId, slug = 'live-slos', from = 'now-15m', to = 'now' } = props
  const query = new URLSearchParams({
    orgId: '1',
    panelId: String(panelId),
    refresh: '5s',
    theme: 'dark',
    from,
    to,
  })
  return `${base}/d-solo/${dashboardUid}/${slug}?${query.toString()}`
}

export function GrafanaPanel(props: GrafanaPanelProps) {
  const { height = 260, title = `Grafana panel ${props.panelId}` } = props
  return (
    <iframe
      src={grafanaPanelUrl(props)}
      title={title}
      loading="lazy"
      className="w-full rounded border border-slate-700"
      style={{ height }}
    />
  )
}

export function grafanaOpenUrl(
  dashboardUid: string,
  panelId: number,
  slug = 'live-slos',
  base: string = grafanaBase(),
): string {
  return `${base}/d/${dashboardUid}/${slug}?orgId=1&viewPanel=${panelId}&from=now-15m&to=now`
}

/** Explore link for a trace id (TraceQL). Tempo datasource uid in grafana/otel-lgtm is "tempo". */
export function tempoTraceUrl(traceId: string, base: string = grafanaBase()): string {
  const left = {
    datasource: 'tempo',
    queries: [{ refId: 'A', queryType: 'traceql', query: `{ trace:id = "${traceId}" }` }],
    range: { from: 'now-6h', to: 'now' },
  }
  return `${base}/explore?orgId=1&left=${encodeURIComponent(JSON.stringify(left))}`
}
